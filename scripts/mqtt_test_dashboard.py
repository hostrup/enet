#!/usr/bin/env python3
import sys
import os
import json
import time
import threading
import queue
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import paho.mqtt.client as mqtt

# Broker config
MQTT_HOST = "127.0.0.1"
MQTT_PORT = 11883
HTTP_PORT = 8086

# State registry
devices = {}         # key: unique_id, val: device_dict
button_events = []   # list of recent button presses
gateway_status = "Offline"
sse_clients = []     # list of queues to push SSE events to
lock = threading.Lock()

class DashboardHTTPHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"HTTP: {format % args}")

    def do_GET(self):
        global gateway_status
        if self.path == "/":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(HTML_TEMPLATE.encode('utf-8'))
        elif self.path == "/api/devices":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            with lock:
                payload = json.dumps({
                    "gateway": gateway_status,
                    "devices": list(devices.values()),
                    "buttons": button_events[-10:]
                })
            self.wfile.write(payload.encode('utf-8'))
        elif self.path == "/events":
            # Server-Sent Events for live synchronization
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            
            q = queue.Queue()
            with lock:
                sse_clients.append(q)
            
            # Send initial state
            with lock:
                initial_data = json.dumps({
                    "type": "init",
                    "gateway": gateway_status,
                    "devices": list(devices.values())
                })
            self.wfile.write(f"data: {initial_data}\n\n".encode('utf-8'))
            self.wfile.flush()
            
            try:
                while True:
                    try:
                        event_data = q.get(timeout=10)
                        self.wfile.write(f"data: {event_data}\n\n".encode('utf-8'))
                        self.wfile.flush()
                    except queue.Empty:
                        # Keep-alive ping
                        self.wfile.write(": ping\n\n".encode('utf-8'))
                        self.wfile.flush()
            except Exception:
                pass
            finally:
                with lock:
                    if q in sse_clients:
                        sse_clients.remove(q)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path == "/api/control":
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            try:
                cmd = json.loads(post_data.decode('utf-8'))
                topic = cmd.get("topic")
                payload = cmd.get("payload")
                if topic:
                    # Publish to MQTT broker
                    mqtt_client.publish(topic, payload, qos=0, retain=False)
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps({"status": "ok"}).encode('utf-8'))
                    return
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(str(e).encode('utf-8'))
                return
        self.send_response(400)
        self.end_headers()

def broadcast_sse(event_type, **kwargs):
    payload = json.dumps({"type": event_type, **kwargs})
    with lock:
        for q in sse_clients:
            q.put(payload)

# MQTT Callbacks
def on_connect(client, userdata, flags, rc, properties=None):
    print(f"Connected to MQTT Broker with code {rc}")
    # Subscribe to homeassistant discovery configs
    client.subscribe("homeassistant/+/+/config")
    # Subscribe to gateway status
    client.subscribe("enet/gateway/status")
    # Subscribe to all eNet button states
    client.subscribe("enet/button/+/state")

def on_message(client, userdata, msg):
    global gateway_status
    topic = msg.topic
    payload = msg.payload.decode('utf-8', errors='ignore')

    # Gateway status topic
    if topic == "enet/gateway/status":
        gateway_status = payload
        broadcast_sse("gateway_status", status=payload)
        return

    # Physical button press topic
    if topic.startswith("enet/button/") and topic.endswith("/state"):
        btn_uid = topic.split("/")[2]
        if payload == "PRESSED":
            btn_name = btn_uid
            # Try to resolve friendly name
            with lock:
                for dev in devices.values():
                    if dev.get("unique_id") == f"enet_btn_{btn_uid}":
                        btn_name = dev.get("name")
                        break
            
            timestamp = time.strftime('%H:%M:%S')
            event = {"name": btn_name, "uid": btn_uid, "time": timestamp}
            with lock:
                button_events.append(event)
                if len(button_events) > 20:
                    button_events.pop(0)
            broadcast_sse("button_press", event=event)
        return

    # Discovery Config topic
    if topic.startswith("homeassistant/") and topic.endswith("/config"):
        try:
            config = json.loads(payload)
            unique_id = config.get("unique_id")
            if not unique_id:
                return

            domain = topic.split("/")[1]
            if domain not in ["light", "switch", "cover", "sensor", "binary_sensor", "scene"]:
                return

            name = config.get("name", "Unknown")
            state_topic = config.get("state_topic")
            # Resolve relative topic if tilde is used
            tilde = config.get("~")
            if tilde and state_topic and state_topic.startswith("~/"):
                state_topic = tilde + state_topic[1:]
            
            command_topic = config.get("command_topic")
            if tilde and command_topic and command_topic.startswith("~/"):
                command_topic = tilde + command_topic[1:]

            suggested_area = config.get("suggested_area", "Unassigned")
            device_info = config.get("device", {})
            dev_name = device_info.get("name", "Unknown Device")
            dev_model = device_info.get("model", "Unknown Model")

            is_dimmable = config.get("brightness", False)

            # Skip button sensors in the main dashboard view, map them only for resolution
            is_button = (domain == "sensor" and "btn" in unique_id) or (domain == "binary_sensor" and "bat" in unique_id)

            with lock:
                if unique_id not in devices:
                    devices[unique_id] = {
                        "unique_id": unique_id,
                        "name": name,
                        "domain": domain,
                        "state_topic": state_topic,
                        "command_topic": command_topic,
                        "area": suggested_area,
                        "device_name": dev_name,
                        "device_model": dev_model,
                        "is_dimmable": is_dimmable,
                        "is_button": is_button,
                        "state": "OFF",
                        "brightness": 0,
                        "position": 0
                    }
                    print(f"Discovered {domain} entity: {name} in {suggested_area}")
                    # Subscribe to its state topic to track state changes
                    if state_topic:
                        client.subscribe(state_topic)
            
            broadcast_sse("discover", device=devices[unique_id])
        except Exception as e:
            print(f"Error parsing discovery: {e}")
        return

    # Entity State update topic
    # Find matching device(s) subscribing to this state topic
    with lock:
        matched_devices = []
        for dev in devices.values():
            if dev["state_topic"] == topic:
                matched_devices.append(dev)
    
    if matched_devices:
        try:
            state_data = json.loads(payload)
            state_val = state_data.get("state", "OFF")
            brightness_val = state_data.get("brightness", 0)
            position_val = state_data.get("position", 0)

            for dev in matched_devices:
                with lock:
                    dev["state"] = state_val
                    dev["brightness"] = brightness_val
                    dev["position"] = position_val
                
                broadcast_sse("state_update", unique_id=dev["unique_id"], state=state_val, brightness=brightness_val, position=position_val)
        except Exception:
            # Fallback if payload is raw string state instead of JSON
            for dev in matched_devices:
                with lock:
                    dev["state"] = payload
                broadcast_sse("state_update", unique_id=dev["unique_id"], state=payload, brightness=0, position=0)


HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="da">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>JUNG eNet test dashboard</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&display=swap" rel="stylesheet">
    <style>
        body {
            font-family: 'Outfit', sans-serif;
            background-color: #0b0f19;
            scroll-behavior: smooth;
        }
        .glass {
            background: rgba(17, 25, 40, 0.75);
            backdrop-filter: blur(16px);
            -webkit-backdrop-filter: blur(16px);
            border: 1px solid rgba(255, 255, 255, 0.08);
        }
        .glass-hover:hover {
            border: 1px solid rgba(255, 255, 255, 0.15);
            box-shadow: 0 8px 30px rgba(0, 0, 0, 0.3);
        }
        /* Custom scrollbar for beautiful UI */
        ::-webkit-scrollbar {
            width: 6px;
            height: 6px;
        }
        ::-webkit-scrollbar-track {
            background: rgba(255, 255, 255, 0.02);
        }
        ::-webkit-scrollbar-thumb {
            background: rgba(255, 255, 255, 0.1);
            border-radius: 4px;
        }
        ::-webkit-scrollbar-thumb:hover {
            background: rgba(255, 255, 255, 0.2);
        }
    </style>
</head>
<body class="text-slate-100 min-h-screen pb-12">
    <!-- Header -->
    <header class="glass sticky top-0 z-50 border-b border-slate-800/50 px-6 py-4 mb-6">
        <div class="max-w-7xl mx-auto flex flex-col md:flex-row gap-4 justify-between items-center">
            <div class="flex items-center space-x-3">
                <div class="h-3 w-3 rounded-full bg-indigo-500 animate-pulse"></div>
                <div>
                    <h1 class="text-xl font-bold tracking-tight bg-gradient-to-r from-indigo-400 via-purple-400 to-pink-400 bg-clip-text text-transparent">
                        eNet Home-Assistant Mockup
                    </h1>
                    <p class="text-[10px] text-slate-400">Smart MQTT Auto-Discovery & Area Mapping Testbed</p>
                </div>
            </div>
            <div class="flex items-center space-x-4">
                <div id="stats-summary" class="flex gap-2 mr-2">
                    <!-- Dynamic counters here -->
                </div>
                <span class="text-xs text-slate-400">Gateway:</span>
                <span id="gateway-status" class="px-2.5 py-1 rounded-full text-xs font-semibold bg-rose-500/20 text-rose-300 border border-rose-500/30 flex items-center gap-1.5">
                    <span class="h-1.5 w-1.5 rounded-full bg-rose-400"></span> Offline
                </span>
            </div>
        </div>
    </header>

    <!-- Room quick navigation bar -->
    <div class="max-w-7xl mx-auto px-6 mb-8">
        <div class="glass p-3 rounded-2xl flex items-center gap-3 overflow-x-auto whitespace-nowrap">
            <span class="text-xs font-semibold uppercase tracking-wider text-slate-400 px-2 shrink-0">Hurtig navigation:</span>
            <div id="room-nav-links" class="flex gap-2 overflow-x-auto">
                <span id="room-nav-placeholder" class="text-xs text-slate-500">Ingen rum endnu...</span>
            </div>
        </div>
    </div>

    <main class="max-w-7xl mx-auto px-6 grid grid-cols-1 lg:grid-cols-4 gap-8">
        <!-- Main Area (Lights Dashboard) -->
        <div class="lg:col-span-3 space-y-8">
            <div class="flex justify-between items-center">
                <h2 class="text-2xl font-bold tracking-tight text-white">Lys og Kontakter</h2>
                <span class="text-xs text-slate-400 bg-slate-800/40 px-3 py-1.5 rounded-md border border-slate-700/50">
                    MQTT Broker: 127.0.0.1:11883
                </span>
            </div>
            
            <!-- Scenes Container -->
            <div id="scenes-section" class="glass p-6 rounded-2xl border border-slate-800/80 space-y-4 hidden animate-fade-in">
                <h3 class="text-lg font-bold tracking-tight text-white flex items-center gap-2">
                    <span>🎬</span> eNet Scener (USER)
                </h3>
                <div id="scenes-grid" class="flex flex-wrap gap-3"></div>
            </div>

            <!-- Areas/Rooms Container -->
            <div id="rooms-container" class="space-y-12">
                <!-- Dynamically populated room containers -->
                <div id="no-devices" class="glass p-12 rounded-2xl text-center space-y-4">
                    <div class="text-slate-400 text-5xl">📡</div>
                    <h3 class="text-lg font-medium">Venter på MQTT Discovery-data...</h3>
                    <p class="text-slate-500 text-sm max-w-md mx-auto">
                        Sørg for, at eNet MQTT-bundlen kører på boksen og har publiceret sine konfigurationer.
                    </p>
                </div>
            </div>
        </div>

        <!-- Sidebar (Physical Button Sniffer & Event log) -->
        <div class="space-y-6">
            <div class="glass p-6 rounded-2xl border border-slate-800/80 sticky top-28">
                <h3 class="text-lg font-bold tracking-tight text-white mb-4 flex items-center gap-2">
                    <span>🎯</span> Fysisk tangent-aflytter
                </h3>
                <p class="text-xs text-slate-400 mb-6 leading-relaxed">
                    Tryk på dine fysiske vægkontakter rundt omkring i huset for at se dem lyse op i real-time nedenfor.
                </p>

                <!-- Button Event Log -->
                <div id="button-log" class="space-y-3 max-h-[480px] overflow-y-auto pr-1">
                    <div class="text-xs text-slate-500 text-center py-8">Ingen registrerede knaptryk endnu</div>
                </div>
            </div>
        </div>
    </main>

    <script>
        const devices = {};
        const roomsContainer = document.getElementById("rooms-container");
        const buttonLog = document.getElementById("button-log");
        const gatewayBadge = document.getElementById("gateway-status");
        const activeRooms = new Set();

        // Open SSE connection for live updates
        const evtSource = new EventSource("/events");

        evtSource.onmessage = function(event) {
            const data = JSON.parse(event.data);
            
            if (data.type === "init") {
                updateGatewayStatus(data.gateway);
                data.devices.forEach(d => {
                    devices[d.unique_id] = d;
                    renderDevice(d);
                });
                updateStats();
            } else if (data.type === "gateway_status") {
                updateGatewayStatus(data.status);
            } else if (data.type === "discover") {
                devices[data.device.unique_id] = data.device;
                renderDevice(data.device);
                updateStats();
            } else if (data.type === "state_update") {
                const dev = devices[data.unique_id];
                if (dev) {
                    dev.state = data.state;
                    dev.brightness = data.brightness;
                    dev.position = data.position;
                    updateDeviceUI(dev);
                }
                updateStats();
            } else if (data.type === "button_press") {
                addButtonPressLog(data.event);
            }
        };

        function updateGatewayStatus(status) {
            if (status === "Online") {
                gatewayBadge.className = "px-2.5 py-1 rounded-full text-xs font-semibold bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 flex items-center gap-1.5";
                gatewayBadge.innerHTML = '<span class="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse"></span> Online';
            } else {
                gatewayBadge.className = "px-2.5 py-1 rounded-full text-xs font-semibold bg-rose-500/20 text-rose-300 border border-rose-500/30 flex items-center gap-1.5";
                gatewayBadge.innerHTML = '<span class="h-1.5 w-1.5 rounded-full bg-rose-400"></span> Offline';
            }
        }

        function getRoomId(roomName) {
            return "room-" + roomName.toLowerCase().replace(/[^a-z0-9]/g, "-");
        }

        function createRoomSection(roomName) {
            const roomId = getRoomId(roomName);
            let section = document.getElementById(roomId);
            if (!section) {
                // Remove the "no devices" placeholder if present
                const noDevices = document.getElementById("no-devices");
                if (noDevices) noDevices.remove();

                section = document.createElement("div");
                section.id = roomId;
                section.className = "space-y-4 pt-4 scroll-mt-24";
                section.innerHTML = `
                    <div class="flex justify-between items-center border-b border-slate-800/80 pb-2">
                        <div class="flex items-center space-x-2">
                            <span class="text-sm font-bold tracking-wider uppercase text-indigo-400">${roomName}</span>
                            <span class="text-[10px] text-slate-500 px-2 py-0.5 rounded bg-slate-800/60" id="${roomId}-count">0 enheder</span>
                        </div>
                        <button onclick="turnOffAllInRoom('${roomName.replace(/'/g, "\\'")}')" class="text-xs text-slate-400 hover:text-rose-400 transition-colors duration-200 flex items-center gap-1 bg-slate-800/30 hover:bg-rose-500/10 px-2.5 py-1 rounded border border-slate-700/50 hover:border-rose-500/30">
                            <span>🧹</span> Sluk alt
                        </button>
                    </div>
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4" id="${roomId}-grid"></div>
                `;
                roomsContainer.appendChild(section);
                updateRoomNavigation(roomName);
            }
            return document.getElementById(`${roomId}-grid`);
        }

        function updateRoomNavigation(roomName) {
            if (activeRooms.has(roomName)) return;
            activeRooms.add(roomName);
            
            const navContainer = document.getElementById("room-nav-links");
            if (navContainer) {
                const placeholder = document.getElementById("room-nav-placeholder");
                if (placeholder) placeholder.remove();
                
                const link = document.createElement("a");
                link.href = "#" + getRoomId(roomName);
                link.className = "px-3 py-1.5 rounded-full text-xs font-semibold bg-slate-800/60 hover:bg-indigo-600/30 border border-slate-700/50 hover:border-indigo-500/50 text-slate-300 hover:text-white transition-all duration-200 shrink-0";
                link.innerText = roomName;
                navContainer.appendChild(link);
            }
        }

        function updateRoomCounters() {
            const roomCounts = {};
            Object.values(devices).forEach(d => {
                if (d.is_button || d.domain === "sensor" || d.domain === "binary_sensor" || d.domain === "scene") return;
                roomCounts[d.area] = (roomCounts[d.area] || 0) + 1;
            });
            Object.keys(roomCounts).forEach(roomName => {
                const el = document.getElementById(`${getRoomId(roomName)}-count`);
                if (el) {
                    el.innerText = `${roomCounts[roomName]} ${roomCounts[roomName] === 1 ? 'enhed' : 'enheder'}`;
                }
            });
        }

        function renderScene(d) {
            let sceneBtn = document.getElementById(`scene-${d.unique_id}`);
            if (!sceneBtn) {
                const container = document.getElementById("scenes-section");
                if (container) container.classList.remove("hidden");
                
                const grid = document.getElementById("scenes-grid");
                sceneBtn = document.createElement("button");
                sceneBtn.id = `scene-${d.unique_id}`;
                sceneBtn.className = "px-4 py-2.5 rounded-xl text-sm font-semibold bg-indigo-600/20 hover:bg-indigo-600 border border-indigo-500/30 hover:border-indigo-500 text-indigo-200 hover:text-white transition-all duration-200 flex items-center gap-2 active:scale-95";
                sceneBtn.onclick = () => {
                    postControl(d.command_topic, "ON");
                    sceneBtn.classList.add("bg-indigo-600", "text-white");
                    setTimeout(() => {
                        sceneBtn.classList.remove("bg-indigo-600", "text-white");
                    }, 800);
                };
                sceneBtn.innerHTML = `🎬 ${d.name}`;
                grid.appendChild(sceneBtn);
            }
        }

        function renderDevice(d) {
            if (d.is_button) return; // Skip buttons in the lights/switches view
            if (d.domain === "sensor" || d.domain === "binary_sensor") return; // Skip sensors in controls view
            if (d.domain === "scene") {
                renderScene(d);
                return;
            }

            const grid = createRoomSection(d.area);
            let card = document.getElementById(d.unique_id);
            
            if (!card) {
                card = document.createElement("div");
                card.id = d.unique_id;
                card.className = "glass glass-hover p-5 rounded-2xl transition-all duration-300 flex flex-col justify-between";
                grid.appendChild(card);
            }

            const deviceIcon = d.domain === "switch" ? "🔌" : "💡";
            const isDimmableHtml = d.is_dimmable ? `
                <div class="mt-4 space-y-2">
                    <div class="flex justify-between text-xs text-slate-400">
                        <span>Lysstyrke</span>
                        <span id="${d.unique_id}-pct">${Math.round((d.brightness / 255) * 100)}%</span>
                    </div>
                    <input type="range" min="1" max="255" value="${d.brightness || 0}" 
                        class="w-full h-1.5 bg-slate-700 rounded-lg appearance-none cursor-pointer accent-indigo-500"
                        id="${d.unique_id}-slider"
                        oninput="onBrightnessSlide(this, '${d.unique_id}')"
                        onchange="onBrightnessChange(this, '${d.unique_id}')"
                    />
                </div>
            ` : "";

            card.innerHTML = `
                <div class="flex justify-between items-start">
                    <div class="flex items-center space-x-3">
                        <div class="p-3 rounded-xl transition-all duration-300" id="${d.unique_id}-icon-bg">
                            <span class="text-2xl transition-colors duration-300" id="${d.unique_id}-icon">${deviceIcon}</span>
                        </div>
                        <div>
                            <h4 class="font-medium text-white text-base">${d.name}</h4>
                            <p class="text-xs text-slate-500">${d.device_name} (${d.device_model})</p>
                        </div>
                    </div>
                    <button 
                        class="w-12 h-6 rounded-full p-0.5 transition-colors duration-300"
                        onclick="toggleDevice('${d.unique_id}')"
                        id="${d.unique_id}-toggle"
                    >
                        <div class="bg-white w-5 h-5 rounded-full shadow-md transform duration-300" id="${d.unique_id}-toggle-ball"></div>
                    </button>
                </div>
                ${isDimmableHtml}
            `;
            updateDeviceUI(d);
            updateRoomCounters();
        }

        function updateDeviceUI(d) {
            const card = document.getElementById(d.unique_id);
            if (!card) return;

            const icon = document.getElementById(`${d.unique_id}-icon`);
            const iconBg = document.getElementById(`${d.unique_id}-icon-bg`);
            const toggle = document.getElementById(`${d.unique_id}-toggle`);
            const toggleBall = document.getElementById(`${d.unique_id}-toggle-ball`);
            const pct = document.getElementById(`${d.unique_id}-pct`);
            const slider = document.getElementById(`${d.unique_id}-slider`);

            if (d.state === "ON") {
                if (d.domain === "switch") {
                    icon.className = "text-2xl text-teal-400";
                    iconBg.className = "p-3 rounded-xl bg-teal-500/15 border border-teal-500/30";
                } else {
                    icon.className = "text-2xl text-amber-400";
                    iconBg.className = "p-3 rounded-xl bg-amber-500/15 border border-amber-500/30";
                }
                toggle.className = "w-12 h-6 rounded-full p-0.5 transition-colors duration-300 bg-indigo-600";
                toggleBall.className = "bg-white w-5 h-5 rounded-full shadow-md transform translate-x-6 duration-300";
            } else {
                icon.className = "text-2xl text-slate-500";
                iconBg.className = "p-3 rounded-xl bg-slate-850/70 border border-transparent";
                toggle.className = "w-12 h-6 rounded-full p-0.5 transition-colors duration-300 bg-slate-700";
                toggleBall.className = "bg-white w-5 h-5 rounded-full shadow-md transform duration-300";
            }

            if (pct && slider) {
                pct.innerText = Math.round((d.brightness / 255) * 100) + "%";
                slider.value = d.brightness;
            }
        }

        function updateStats() {
            const allDevs = Object.values(devices).filter(d => !d.is_button);
            const total = allDevs.length;
            const activeLights = allDevs.filter(d => d.domain === "light" && d.state === "ON").length;
            const activeSwitches = allDevs.filter(d => d.domain === "switch" && d.state === "ON").length;
            
            const statsEl = document.getElementById("stats-summary");
            if (statsEl) {
                statsEl.innerHTML = `
                    <span class="bg-indigo-500/10 text-indigo-300 border border-indigo-500/20 px-2.5 py-1 rounded-md text-xs font-semibold">
                        Total: ${total}
                    </span>
                    <span class="bg-amber-500/10 text-amber-300 border border-amber-500/20 px-2.5 py-1 rounded-md text-xs font-semibold">
                        Lys tændt: ${activeLights}
                    </span>
                    <span class="bg-teal-500/10 text-teal-300 border border-teal-500/20 px-2.5 py-1 rounded-md text-xs font-semibold">
                        Stik tændt: ${activeSwitches}
                    </span>
                `;
            }
        }

        function turnOffAllInRoom(roomName) {
            Object.values(devices).forEach(d => {
                if (d.area === roomName && d.state === "ON" && !d.is_button) {
                    let payload = "";
                    if (d.is_dimmable) {
                        payload = JSON.stringify({ state: "OFF", brightness: 0 });
                    } else {
                        payload = JSON.stringify({ state: "OFF" });
                    }
                    postControl(d.command_topic, payload);
                }
            });
        }

        let isSliding = false;
        function onBrightnessSlide(slider, uniqueId) {
            isSliding = true;
            const pct = document.getElementById(`${uniqueId}-pct`);
            if (pct) {
                pct.innerText = Math.round((slider.value / 255) * 100) + "%";
            }
        }

        function onBrightnessChange(slider, uniqueId) {
            isSliding = false;
            const dev = devices[uniqueId];
            if (!dev) return;
            const val = parseInt(slider.value);
            
            const payload = JSON.stringify({
                state: val > 0 ? "ON" : "OFF",
                brightness: val
            });
            postControl(dev.command_topic, payload);
        }

        function toggleDevice(uniqueId) {
            const dev = devices[uniqueId];
            if (!dev) return;
            
            const nextState = dev.state === "ON" ? "OFF" : "ON";
            let payload = "";
            
            if (dev.is_dimmable) {
                payload = JSON.stringify({
                    state: nextState,
                    brightness: nextState === "ON" ? 179 : 0
                });
            } else {
                payload = JSON.stringify({
                    state: nextState
                });
            }
            postControl(dev.command_topic, payload);
        }

        function postControl(topic, payload) {
            fetch("/api/control", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ topic: topic, payload: payload })
            });
        }

        function addButtonPressLog(event) {
            if (buttonLog.innerText.includes("Ingen registrerede knaptryk")) {
                buttonLog.innerHTML = "";
            }

            const item = document.createElement("div");
            item.className = "flex justify-between items-center bg-indigo-500/10 border border-indigo-500/20 px-4 py-3 rounded-xl animate-bounce";
            item.innerHTML = `
                <div>
                    <h5 class="text-xs font-semibold text-indigo-300">KNAP DETEKTERET</h5>
                    <p class="text-sm font-medium text-white">${event.name}</p>
                </div>
                <span class="text-[10px] text-slate-400 font-mono">${event.time}</span>
            `;
            buttonLog.insertBefore(item, buttonLog.firstChild);
            
            setTimeout(() => {
                item.className = "flex justify-between items-center bg-slate-800/40 border border-slate-700/30 px-4 py-3 rounded-xl transition-all duration-500";
            }, 1000);

            while (buttonLog.children.length > 15) {
                buttonLog.removeChild(buttonLog.lastChild);
            }
        }
    </script>
</body>
</html>
"""

def main():
    global mqtt_client
    print("=" * 60)
    print(" JUNG ENET TEST DASHBOARD - LOCAL MOCKUP OF HA ")
    print("=" * 60)
    print(f"Connecting to MQTT Broker at {MQTT_HOST}:{MQTT_PORT}...")
    
    mqtt_client = mqtt.Client()
    mqtt_client.on_connect = on_connect
    mqtt_client.on_message = on_message
    
    try:
        mqtt_client.connect(MQTT_HOST, MQTT_PORT, 60)
    except Exception as e:
        print(f"FAILED to connect to MQTT Broker: {e}")
        print("Please check if the Mosquitto broker is running.")
        sys.exit(1)
        
    mqtt_client.loop_start()

    # Start HTTP server
    server_address = ('', HTTP_PORT)
    httpd = ThreadingHTTPServer(server_address, DashboardHTTPHandler)
    print(f"Dashboard running on local network. Access it here:")
    print(f"👉 http://10.0.0.2:{HTTP_PORT} (From NUC or other devices on local network)")
    print(f"👉 http://localhost:{HTTP_PORT} (Local loopback)")
    print("Press Ctrl+C to terminate.")

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping dashboard...")
    finally:
        mqtt_client.loop_stop()
        mqtt_client.disconnect()
        httpd.server_close()
        print("Stopped successfully.")

if __name__ == "__main__":
    main()
