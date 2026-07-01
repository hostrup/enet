package org.hostrup.enet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Scanner;
import com.insta.instanet.instanetbox.simplecontrol.EndpointStatePower;

/**
 * Handles inbound HTTP requests to the native configuration dashboard of the MQTT gateway.
 * Serves static HTML pages, processes configuration saves, handles test actions (toggling endpoints),
 * and exposes JSON statuses (broker details, mapped devices, live log buffer).
 */
public class WebDashboard implements HttpHandler {
    /**
     * Absolute path where the deployed OSGi bundle is hot-reloaded from.
     */
    private static final String BUNDLE_JAR_PATH = "/home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar";
    private final MqttActivator core;

    /**
     * Constructs a WebDashboard instance.
     * 
     * @param core Reference to the main MqttActivator coordinator.
     */
    public WebDashboard(MqttActivator core) { 
        this.core = core; 
    }

    /**
     * Entrypoint for the HttpServer context routing. Dispatches requests based on POST/GET actions.
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String query = exchange.getRequestURI().getQuery();

            if ("POST".equalsIgnoreCase(method)) {
                String body;
                try (InputStream is = exchange.getRequestBody();
                     Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                    body = s.hasNext() ? s.next() : "";
                } catch (Exception e) {
                    body = "";
                }
                
                String action = extractParam(body, "action");
                if (action == null || action.isEmpty()) {
                    action = extractParam(query != null ? query : "", "action");
                }

                if ("save".equals(action)) {
                    // Save updated MQTT broker parameters
                    core.getConfigManager().saveConfig(extractParam(body, "b"), extractParam(body, "u"), extractParam(body, "p"));
                    sendResponse(exchange, 200, "OK", "text/plain");
                } else if ("debug".equals(action)) {
                    // Toggle debugging mode inside the gateway
                    core.debugMode = !core.debugMode;
                    core.addLog("Debug Mode is now: " + (core.debugMode ? "ON" : "OFF"));
                    sendResponse(exchange, 200, "OK", "text/plain");
                } else if ("toggle".equals(action)) {
                    // Manually trigger a test command to the eNet simple control facade
                    String uid = extractParam(body, "uid"); 
                    String st = extractParam(body, "st");
                    if (core.getSimpleControl() != null && uid != null && st != null) {
                        try {
                            EndpointStatePower pwr = new EndpointStatePower();
                            pwr.setValue("ON".equals(st) ? EndpointStatePower.Value.ON : EndpointStatePower.Value.OFF);
                            core.getSimpleControl().handleControlRequest(uid, pwr, "dashboard_test");
                            core.addLog("Dashboard Test: Sent " + st + " to " + uid);
                        } catch (Exception ex) {
                            core.addLog("Dashboard Test Error: " + ex.getMessage());
                        }
                    }
                    sendResponse(exchange, 200, "OK", "text/plain");
                } else if ("reload".equals(action)) {
                    // Hot-reload the OSGi bundle directly from the jar location
                    core.addLog("Hot-Reload signal received. Reloading OSGi Bundle...");
                    sendResponse(exchange, 200, "OK", "text/plain");
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                            java.io.File jarFile = new java.io.File(BUNDLE_JAR_PATH);
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(jarFile)) {
                                core.getContext().getBundle().update(fis);
                            }
                        } catch (Exception e) { System.out.println("Reload failed: " + e.getMessage()); }
                    }).start();
                } else {
                    sendResponse(exchange, 400, "Unknown Action", "text/plain");
                }
            } else { // GET
                String action = extractParam(query != null ? query : "", "action");
                
                if ("status".equals(action)) {
                    // Returns JSON status showing broker credentials and active log buffer lines
                    StringBuilder json = new StringBuilder();
                    json.append("{").append("\"broker\":\"").append(core.getTopologyBuilder().escapeJson(core.getConfigManager().getMqttBroker())).append("\",")
                        .append("\"user\":\"").append(core.getTopologyBuilder().escapeJson(core.getConfigManager().getMqttUser())).append("\",")
                        .append("\"connected\":").append(core.getMqttManager().isConnected()).append(",")
                        .append("\"debug\":").append(core.debugMode).append(",")
                        .append("\"logs\":[");
                    Object[] logs = core.getLogBuffer().toArray();
                    for (int i = 0; i < logs.length; i++) {
                        json.append("\"").append(core.getTopologyBuilder().escapeJson((String) logs[i])).append("\"");
                        if (i < logs.length - 1) json.append(",");
                    }
                    json.append("]}"); 
                    sendResponse(exchange, 200, json.toString(), "application/json; charset=UTF-8");
                } else if ("devices".equals(action)) {
                    // Exposes the list of mapped eNet topology devices to the dashboard table
                    StringBuilder json = new StringBuilder("[");
                    boolean first = true;
                    for (Map<String, String> map : core.getTopologyBuilder().dashboardItems) {
                        if (!first) json.append(",");
                        first = false;
                        json.append("{").append("\"uid\":\"").append(core.getTopologyBuilder().escapeJson(map.get("uid"))).append("\",")
                            .append("\"name\":\"").append(core.getTopologyBuilder().escapeJson(map.get("name"))).append("\",")
                            .append("\"room\":\"").append(core.getTopologyBuilder().escapeJson(map.get("room"))).append("\",")
                            .append("\"domain\":\"").append(core.getTopologyBuilder().escapeJson(map.get("domain"))).append("\",")
                            .append("\"topic\":\"").append(core.getTopologyBuilder().escapeJson(map.get("topic"))).append("\"").append("}");
                    }
                    json.append("]"); 
                    sendResponse(exchange, 200, json.toString(), "application/json; charset=UTF-8");
                } else if ("value_map".equals(action)) {
                    // Exposes internal mappings of function values to end-user endpoints
                    StringBuilder json = new StringBuilder("{");
                    Object[] keys = core.getTopologyBuilder().valueUidToEndpointUid.keySet().toArray();
                    for (int i = 0; i < keys.length; i++) {
                        String k = (String) keys[i];
                        String v = core.getTopologyBuilder().valueUidToEndpointUid.get(k);
                        json.append("\"").append(core.getTopologyBuilder().escapeJson(k)).append("\":\"").append(core.getTopologyBuilder().escapeJson(v)).append("\"");
                        if (i < keys.length - 1) json.append(",");
                    }
                    json.append("}");
                    sendResponse(exchange, 200, json.toString(), "application/json; charset=UTF-8");
                } else {
                    // Serves the JUNG-themed HTML dashboard interface page layout
                    String html = getDashboardHtml();
                    sendResponse(exchange, 200, html, "text/html; charset=UTF-8");
                }
            }
        } catch (Exception e) {
            core.addLog("Error handling HTTP request: " + e.getMessage());
            try {
                sendResponse(exchange, 500, "Internal Server Error", "text/plain");
            } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    /**
     * Helper parameter extractor for standard query and urlencoded request bodies.
     */
    private String extractParam(String source, String paramName) {
        if (source == null || source.isEmpty()) return null;
        try {
            String[] pairs = source.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx != -1) {
                    String key = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    if (key.equals(paramName)) {
                        return java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    }
                }
            }
        } catch (Exception e) { }
        return null;
    }

    /**
     * Utility method to send an HTTP response back to the client.
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String response, String contentType) throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Generates the HTML string for the JUNG-themed configuration dashboard.
     */
    private String getDashboardHtml() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>eNet SMART HOME - MQTT Gateway</title>" +
            "<style>" +
            "body{background:#1c1f1f;color:#eaeaea;font-family:'Arial', 'Helvetica Neue', Helvetica, sans-serif;margin:0;padding:20px;} " +
            "h1,h2{color:#f56e00;} " +
            ".panel{background:#262626;border:1px solid #3a3a3a;border-radius:4px;padding:20px;margin-bottom:20px;box-shadow:0 2px 5px rgba(0,0,0,0.2);} " +
            "input{background:#121213;color:#eaeaea;border:1px solid #3a3a3a;padding:8px;margin-right:10px;border-radius:4px;width:200px;} " +
            "button{background:#f56e00;color:#fff;border:none;padding:8px 16px;border-radius:4px;cursor:pointer;margin-right:5px;font-weight:bold;transition:background 0.2s;} " +
            "button:hover{background:#ff8700;} " +
            "button.off{background:#444;} " +
            "button.off:hover{background:#555;} " +
            "button.warn{background:#d29922;color:#000;} " +
            "button.info{background:#1f6feb;color:#fff;} " +
            "#logs{height:200px;overflow-y:auto;background:#121213;color:#a7a7a7;padding:10px;font-size:12px;border:1px solid #3a3a3a;font-family:monospace;} " +
            "table{width:100%;border-collapse:collapse;} " +
            "th{background:#313131;color:#f56e00;border:1px solid #3a3a3a;padding:10px;text-align:left;} " +
            "td{border:1px solid #3a3a3a;padding:8px;text-align:left;} " +
            ".indicator{display:inline-block;width:12px;height:12px;border-radius:50%;margin-right:8px;} " +
            ".red{background:#da3633;} " +
            ".green{background:#7ec4af;} " +
            "</style></head><body>" +
            "<h1>eNet SMART HOME - Native MQTT Gateway</h1>" +
            "<div class='panel'><h2>Gateway Configuration</h2>" +
            "<div><span id='status-dot' class='indicator red'></span> <span id='status-text'>Offline</span></div><br>" +
            "Broker IP: <input id='b' type='text' placeholder='tcp://10.0.0.2:1883' style='width:250px;'> " +
            "User: <input id='u' type='text'> " +
            "Pass: <input id='p' type='password'> " +
            "<button onclick='save()'>Save & Connect</button>" +
            "<button id='debugBtn' class='info' onclick='toggleDebug()' style='float:right; margin-left: 10px;'>Debug: OFF</button>" +
            "<button class='warn' onclick='reloadGateway()' style='float:right;'>Hot-Reload Gateway</button>" +
            "</div>" +
            "<div class='panel'><h2>Live Logs (RAM Buffer)</h2><div id='logs'></div></div>" +
            "<div class='panel'><h2>Topology & Testing</h2>" +
            "<table><thead><tr><th>Room</th><th>Name</th><th>Domain</th><th>Actions</th></tr></thead><tbody id='devs'></tbody></table>" +
            "</div>" +
            "<script>" +
            "let init=0; " +
            "function uLog(){ " +
            "  fetch('/mqtt?action=status').then(r=>r.json()).then(d=>{ " +
            "    if(!init){ document.getElementById('b').value=d.broker; document.getElementById('u').value=d.user; init=1; } " +
            "    let dt=document.getElementById('status-dot'); let st=document.getElementById('status-text'); " +
            "    if(d.connected){dt.className='indicator green'; st.innerText='Connected';}else{dt.className='indicator red'; st.innerText='Offline';} " +
            "    let dbg = document.getElementById('debugBtn'); " +
            "    if(d.debug) { dbg.innerText = 'Debug: ON'; dbg.style.background = '#da3633'; } else { dbg.innerText = 'Debug: OFF'; dbg.style.background = '#f56e00'; } " +
            "    let lb=document.getElementById('logs'); lb.innerHTML=d.logs.join('<br>'); lb.scrollTop=lb.scrollHeight; " +
            "  });" +
            "} " +
            "function uDev(){ " +
            "  fetch('/mqtt?action=devices').then(r=>r.json()).then(d=>{ " +
            "    let h=''; " +
            "    d.forEach(x=>{ " +
            "      h+='<tr><td>'+x.room+'</td><td>'+x.name+'</td><td>'+x.domain+'</td><td>'; " +
            "      if(x.domain==='light' || x.domain==='switch') { " +
            "        h+='<button onclick=\"tg(\\''+x.uid+'\\',\\'ON\\')\">ON</button> <button class=\"off\" onclick=\"tg(\\''+x.uid+'\\',\\'OFF\\')\">OFF</button>'; " +
            "      } " +
            "      h+='</td></tr>'; " +
            "    }); " +
            "    document.getElementById('devs').innerHTML=h; " +
            "  });" +
            "} " +
            "function save(){ " +
            "  let f=new URLSearchParams(); f.append('action', 'save'); f.append('b',document.getElementById('b').value); " +
            "  f.append('u',document.getElementById('u').value); f.append('p',document.getElementById('p').value); " +
            "  fetch('/mqtt',{method:'POST',body:f}).then(()=>uLog()); " +
            "} " +
            "function tg(id, st){ " +
            "  let f=new URLSearchParams(); f.append('action', 'toggle'); f.append('uid',id); f.append('st',st); " +
            "  fetch('/mqtt',{method:'POST',body:f}); " +
            "} " +
            "function reloadGateway(){ " +
            "  fetch('/mqtt?action=reload',{method:'POST'}); " +
            "  alert('Reload signal sent. Check logs.'); " +
            "} " +
            "function toggleDebug(){ " +
            "  fetch('/mqtt?action=debug',{method:'POST'}).then(()=>uLog()); " +
            "} " +
            "setInterval(uLog, 2000); uLog(); setInterval(uDev, 5000); uDev();" +
            "</script></body></html>";
    }
}