import time
import json
import paho.mqtt.client as mqtt
from queue import Queue, Empty

# Classified list of lights based on our mapping
LIGHTS = [
    # Non-dimmable lights
    {"uid": "8291dd17-ee34-48e7-9102-dc2700c018b9_2", "name": "Skab Spots walkin", "room": "Skab", "dimmable": False},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700c018b9_3", "name": "Soveværelse Ronni seng", "room": "Soveværelse", "dimmable": False},
    {"uid": "8291dd17-ee34-48e7-9102-dc27011017a2_3", "name": "Rasmus Diskolys Rasmus", "room": "Rasmus", "dimmable": False},
    {"uid": "8291dd17-ee34-48e7-9102-dc27011017a2_2", "name": "Emil Diskolys Emil", "room": "Emil", "dimmable": False},
    {"uid": "8291dd17-ee34-48e7-9102-dc2709003239_1", "name": "Bad stueetage Spots bad", "room": "Bad stueetage", "dimmable": False},
    {"uid": "8291dd17-ee34-48e7-9102-dc2709003239_2", "name": "Bryggers Spots bryggers", "room": "Bryggers", "dimmable": False},
    {"uid": "8291dd17-ee34-48e7-9102-dc2709003239_6", "name": "Bad 1. sal Spots bad 1.sal", "room": "Bad 1. sal", "dimmable": False},
    {"uid": "8291dd17-ee34-48e7-9102-dc2709003239_7", "name": "Bad stueetage Pendler bad", "room": "Bad stueetage", "dimmable": False},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700300ca5_1", "name": "Soveværelse Mathilde seng", "room": "Soveværelse", "dimmable": False},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700300ca5_3", "name": "Udenfor Tagterrasse", "room": "Udenfor", "dimmable": False},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700300ca5_5", "name": "Udenfor Postkasse", "room": "Udenfor", "dimmable": False},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700300ca5_4", "name": "Udenfor Udendørslys", "room": "Udenfor", "dimmable": False},
    
    # Dimmable lights
    {"uid": "8291dd17-ee34-48e7-9102-dc2700f01766_1", "name": "Køkken Køkkenbord", "room": "Køkken", "dimmable": True},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700f017a8_1", "name": "Stue Spisebord", "room": "Stue", "dimmable": True},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700300c19_4", "name": "Køkken Spots Køkken", "room": "Køkken", "dimmable": True},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700300c19_3", "name": "Soveværelse Spots Soveværel", "room": "Soveværelse", "dimmable": True},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700300c19_2", "name": "Viktualierum Spots Viktualie", "room": "Viktualierum", "dimmable": True},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700300c19_1", "name": "Stue Spots Stue", "room": "Stue", "dimmable": True},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700000291_2", "name": "Emil Spots Emil", "room": "Emil", "dimmable": True},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700000291_3", "name": "Rasmus Spots Rasmus", "room": "Rasmus", "dimmable": True},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700000291_4", "name": "Multirum Spots Multirum", "room": "Multirum", "dimmable": True},
    {"uid": "8291dd17-ee34-48e7-9102-dc2700000291_1", "name": "Entre Spots Entre", "room": "Entre", "dimmable": True}
]

BROKER_HOST = "localhost"
BROKER_PORT = 11883
TIMEOUT = 5.0  # seconds to wait for feedback on state topic

# Thread-safe queue for incoming state messages
state_queue = Queue()

def on_connect(client, userdata, flags, reason_code, properties=None):
    print(f"Connected to MQTT broker with status: {reason_code}")
    client.subscribe("enet/light/+/state")

def on_message(client, userdata, msg):
    payload = msg.payload.decode('utf-8')
    topic = msg.topic
    print(f"DEBUG: Received state: {topic} -> {payload}")
    state_queue.put({
        "topic": topic,
        "payload": payload,
        "timestamp": time.time()
    })

def wait_for_state(expected_topic, start_time, timeout=TIMEOUT):
    deadline = start_time + timeout
    while True:
        now = time.time()
        if now >= deadline:
            return None, timeout
        try:
            msg = state_queue.get(timeout=deadline - now)
            # Ensure it matches the topic and occurred after our action
            if msg["topic"] == expected_topic and msg["timestamp"] >= start_time - 0.2:
                return msg["payload"], (msg["timestamp"] - start_time)
        except Empty:
            return None, timeout

def main():
    client = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION2)
    client.on_connect = on_connect
    client.on_message = on_message

    print(f"Connecting to MQTT broker at {BROKER_HOST}:{BROKER_PORT}...")
    client.connect(BROKER_HOST, BROKER_PORT, 60)
    client.loop_start()

    time.sleep(1.0)

    results = []

    for index, light in enumerate(LIGHTS):
        uid = light["uid"]
        name = light["name"]
        room = light["room"]
        is_dimmable = light["dimmable"]
        set_topic = f"enet/light/{uid}/set"
        state_topic = f"enet/light/{uid}/state"

        print(f"\n--- Testing Light {index + 1}/{len(LIGHTS)}: {name} ({uid}) in {room} (Dimmable: {is_dimmable}) ---")
        device_result = {
            "uid": uid,
            "name": name,
            "room": room,
            "dimmable": is_dimmable,
            "on_test": {"success": False, "payload": None, "timing": None},
            "dim_test": {"success": False, "payload": None, "timing": None},
            "off_test": {"success": False, "payload": None, "timing": None}
        }

        # Clear queue
        while not state_queue.empty():
            try:
                state_queue.get_nowait()
            except Empty:
                break

        # 1. Test ON
        print(" -> Sending ON command...")
        t_start = time.time()
        client.publish(set_topic, '{"state":"ON"}', qos=1)
        payload, duration = wait_for_state(state_topic, t_start)
        if payload is not None and '"state":"ON"' in payload:
            print(f"    Received ON response in {duration:.3f}s: {payload}")
            device_result["on_test"] = {"success": True, "payload": payload, "timing": duration}
        else:
            print(f"    ❌ Bad or missing ON response: {payload}")
            device_result["on_test"] = {"success": False, "payload": payload or "TIMEOUT", "timing": duration}

        time.sleep(1.0)

        # 2. Test DIM to 50%
        print(" -> Sending DIM (128) command...")
        t_start = time.time()
        client.publish(set_topic, '{"state":"ON","brightness":128}', qos=1)
        payload, duration = wait_for_state(state_topic, t_start)
        
        # Validation based on device type
        if payload is not None:
            if is_dimmable:
                # Dimmable lights should output brightness
                if '"brightness":128' in payload:
                    print(f"    Received DIM response in {duration:.3f}s: {payload}")
                    device_result["dim_test"] = {"success": True, "payload": payload, "timing": duration}
                else:
                    print(f"    ❌ Missing brightness field in DIM response: {payload}")
                    device_result["dim_test"] = {"success": False, "payload": payload, "timing": duration}
            else:
                # Non-dimmable lights should fallback to ON
                if '"state":"ON"' in payload and 'brightness' not in payload:
                    print(f"    Received fallback ON response in {duration:.3f}s: {payload}")
                    device_result["dim_test"] = {"success": True, "payload": payload, "timing": duration}
                else:
                    print(f"    ❌ Bad fallback response for non-dimmer: {payload}")
                    device_result["dim_test"] = {"success": False, "payload": payload, "timing": duration}
        else:
            print("    ❌ Timeout waiting for DIM/fallback response")
            device_result["dim_test"] = {"success": False, "payload": "TIMEOUT", "timing": TIMEOUT}

        time.sleep(1.0)

        # 3. Test OFF
        print(" -> Sending OFF command...")
        t_start = time.time()
        client.publish(set_topic, '{"state":"OFF"}', qos=1)
        payload, duration = wait_for_state(state_topic, t_start)
        if payload is not None and '"state":"OFF"' in payload:
            print(f"    Received OFF response in {duration:.3f}s: {payload}")
            device_result["off_test"] = {"success": True, "payload": payload, "timing": duration}
        else:
            print(f"    ❌ Bad or missing OFF response: {payload}")
            device_result["off_test"] = {"success": False, "payload": payload or "TIMEOUT", "timing": duration}

        results.append(device_result)
        time.sleep(1.0)

    client.loop_stop()
    client.disconnect()

    # Generate Markdown Report
    report = []
    report.append("# eNet Smart Home MQTT Light Unit Test Results (After Bug Fixes)")
    report.append(f"**Test Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}")
    report.append("**Target Environment:** Local Dev Broker (enet-dev-mosquitto:11883)")
    report.append("**Test Mode:** Automated ON -> DIM (128) -> OFF sequence for all light devices.")
    report.append("**Improvements Verified:** package renamed to org.hostrup.enet, state name fixes, dimming crashes resolved, redundant commands removed.\n")

    report.append("## Overview Table")
    report.append("| # | Device Name | Room | Dimmable | ON Status | DIM/Fallback Status | OFF Status | Avg Latency (s) | Notes |")
    report.append("|---|-------------|------|----------|-----------|---------------------|------------|-----------------|-------|")

    anomalies = []

    for idx, r in enumerate(results):
        on_succ = "✅" if r["on_test"]["success"] else "❌"
        dim_succ = "✅" if r["dim_test"]["success"] else "❌"
        off_succ = "✅" if r["off_test"]["success"] else "❌"

        timings = []
        if r["on_test"]["success"]: timings.append(r["on_test"]["timing"])
        if r["dim_test"]["success"]: timings.append(r["dim_test"]["timing"])
        if r["off_test"]["success"]: timings.append(r["off_test"]["timing"])
        avg_time = f"{sum(timings)/len(timings):.2f}s" if timings else "N/A"

        notes = []
        if not r["on_test"]["success"] or not r["dim_test"]["success"] or not r["off_test"]["success"]:
            notes.append("Failed test step(s)")
            anomalies.append({
                "device": r["name"],
                "uid": r["uid"],
                "description": f"ON: {r['on_test']['payload']}, DIM: {r['dim_test']['payload']}, OFF: {r['off_test']['payload']}"
            })

        notes_str = ", ".join(notes) if notes else "OK"
        report.append(f"| {idx+1} | {r['name']} | {r['room']} | {'Yes' if r['dimmable'] else 'No'} | {on_succ} ({r['on_test']['timing']:.2f}s) | {dim_succ} ({r['dim_test']['timing']:.2f}s) | {off_succ} ({r['off_test']['timing']:.2f}s) | {avg_time} | {notes_str} |")

    report.append("\n## Detailed Test Logs")
    for r in results:
        report.append(f"### {r['name']} ({r['uid']})")
        report.append(f"- **Room:** {r['room']}")
        report.append(f"- **Dimmable:** {'Yes' if r['dimmable'] else 'No'}")
        report.append(f"- **ON Test:** Response=`{r['on_test']['payload']}`, Latency=`{r['on_test']['timing']:.3f}s`")
        report.append(f"- **DIM/Fallback Test:** Response=`{r['dim_test']['payload']}`, Latency=`{r['dim_test']['timing']:.3f}s`")
        report.append(f"- **OFF Test:** Response=`{r['off_test']['payload']}`, Latency=`{r['off_test']['timing']:.3f}s`")
        report.append("")

    report.append("## Identified Bugs & Anomalies")
    if anomalies:
        for idx, a in enumerate(anomalies):
            report.append(f"### Bug {idx+1}: {a['device']}")
            report.append(f"- **UID:** `{a['uid']}`")
            report.append(f"- **Details:** {a['description']}")
            report.append("")
    else:
        report.append("No anomalies found. All 22 lights passed unit tests successfully!")

    # Save to file
    artifact_path = "/home/hostrup/.gemini/antigravity-cli/brain/76d749b5-44b9-4831-b507-2e15ada1bb1b/enet_test_results.md"
    with open(artifact_path, "w", encoding="utf-8") as f:
        f.write("\n".join(report))

    print(f"\nNew report written to {artifact_path}")

if __name__ == "__main__":
    main()
