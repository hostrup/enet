#!/usr/bin/env python3
import urllib.request
import json
import time
import paho.mqtt.client as mqtt

# Configuration
DASHBOARD_URL = "http://127.0.0.1:8086"
MQTT_HOST = "127.0.0.1"
MQTT_PORT = 11883

received_messages = []

def on_connect(client, userdata, flags, rc, properties=None):
    print("Test client connected to MQTT broker")
    client.subscribe("enet/#")

def on_message(client, userdata, msg):
    topic = msg.topic
    payload = msg.payload.decode('utf-8', errors='ignore')
    print(f"MQTT msg received: {topic} -> {payload}")
    received_messages.append((topic, payload))

def main():
    # 1. Fetch devices from dashboard
    print("1. Fetching devices from dashboard...")
    try:
        with urllib.request.urlopen(f"{DASHBOARD_URL}/api/devices") as response:
            data = json.loads(response.read().decode())
    except Exception as e:
        print(f"Failed to reach dashboard API: {e}")
        return False

    devices = data.get("devices", [])
    print(f"Found {len(devices)} devices in dashboard state.")
    
    # Find a test light (not a switch, as per constraints "do not touch or toggle switch outlets")
    test_light = None
    for dev in devices:
        if dev["domain"] == "light" and dev["command_topic"]:
            test_light = dev
            break
            
    if not test_light:
        print("No light with command_topic found!")
        return False
        
    print(f"Selected test light: '{test_light['name']}' with topic '{test_light['command_topic']}'")

    # 2. Setup MQTT client to monitor sets and states
    client = mqtt.Client()
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(MQTT_HOST, MQTT_PORT, 60)
    client.loop_start()
    
    # Wait for subscription
    time.sleep(1)

    # 3. Simulate Toggle POST request to dashboard
    print("\n2. Sending POST request to dashboard to toggle the light...")
    control_payload = {
        "topic": test_light["command_topic"],
        "payload": json.dumps({"state": "ON", "brightness": 179})
    }
    
    req = urllib.request.Request(
        f"{DASHBOARD_URL}/api/control",
        data=json.dumps(control_payload).encode('utf-8'),
        headers={'Content-Type': 'application/json'}
    )
    
    try:
        with urllib.request.urlopen(req) as response:
            res_data = json.loads(response.read().decode())
            print(f"Dashboard control response: {res_data}")
    except Exception as e:
        print(f"POST request failed: {e}")
        client.loop_stop()
        return False

    # 4. Wait for MQTT message to propagate
    print("\n3. Waiting for MQTT message on command topic...")
    time.sleep(2)
    
    client.loop_stop()
    client.disconnect()

    # Check if the command was published to the broker
    success = False
    for topic, payload in received_messages:
        if topic == test_light["command_topic"]:
            print(f"SUCCESS: Found command message in MQTT broker: {topic} -> {payload}")
            success = True
            break
            
    if not success:
        print("FAILURE: Command message not found in MQTT broker.")
        return False
        
    print("\nTest completed successfully!")
    return True

if __name__ == "__main__":
    import sys
    sys.exit(0 if main() else 1)
