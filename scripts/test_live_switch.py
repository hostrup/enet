#!/usr/bin/env python3
import paho.mqtt.client as mqtt
import json
import time

broker = "127.0.0.1"
port = 11883
topic_set = "enet/switch/8291dd17-ee34-48e7-9102-dc2709003239_3/set"

def on_connect(client, userdata, flags, rc, properties=None):
    client.subscribe("enet/gateway/log")
    client.subscribe("enet/switch/+/state")

def on_message(client, userdata, msg):
    payload = msg.payload.decode('utf-8', errors='ignore')
    print(f"[{msg.topic}] {payload}")

def main():
    client = mqtt.Client()
    client.on_connect = on_connect
    client.on_message = on_message
    
    client.connect(broker, port, 60)
    client.loop_start()
    
    time.sleep(1) # Let subscription establish
    
    print("\n--- Sending ON command to switch ---")
    client.publish(topic_set, json.dumps({"state": "ON"}), qos=0)
    
    time.sleep(2)
    
    print("\n--- Sending OFF command to switch ---")
    client.publish(topic_set, json.dumps({"state": "OFF"}), qos=0)
    
    time.sleep(2)
    client.loop_stop()
    client.disconnect()

if __name__ == "__main__":
    main()
