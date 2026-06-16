#!/usr/bin/env python3
import paho.mqtt.client as mqtt
import time
import sys

broker = "127.0.0.1"
port = 11883

received = []

def on_connect(client, userdata, flags, rc, properties=None):
    client.subscribe("enet/#")

def on_message(client, userdata, msg):
    payload = msg.payload.decode('utf-8', errors='ignore')
    received.append((msg.topic, payload))

def main():
    client = mqtt.Client()
    client.on_connect = on_connect
    client.on_message = on_message
    
    try:
        client.connect(broker, port, 60)
    except Exception as e:
        print(f"Error connecting: {e}")
        return
        
    client.loop_start()
    time.sleep(1.5)  # Wait to gather retained messages
    client.loop_stop()
    client.disconnect()
    
    print(f"Total topics gathered: {len(received)}")
    # Print light states
    print("\n--- LIGHTS ---")
    for topic, payload in sorted(received):
        if "/light/" in topic and topic.endswith("/state"):
            print(f"{topic}: {payload}")
            
    # Print switch states
    print("\n--- SWITCHES ---")
    for topic, payload in sorted(received):
        if "/switch/" in topic and topic.endswith("/state"):
            print(f"{topic}: {payload}")

if __name__ == "__main__":
    main()
