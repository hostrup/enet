import sys
import time
import subprocess
import threading
import paho.mqtt.client as mqtt

# Output log file path
SNIFFER_LOG = "/home/hostrup/.gemini/antigravity-cli/brain/76d749b5-44b9-4831-b507-2e15ada1bb1b/button_sniffer.log"

# Clear/init the sniffer log
with open(SNIFFER_LOG, "w", encoding="utf-8") as f:
    f.write(f"=== eNet Button & Event Sniffer Session Started: {time.strftime('%Y-%m-%d %H:%M:%S')} ===\n\n")

def log_event(message):
    timestamp = time.strftime('%H:%M:%S')
    formatted = f"[{timestamp}] {message}"
    print(formatted)
    sys.stdout.flush()
    with open(SNIFFER_LOG, "a", encoding="utf-8") as f:
        f.write(formatted + "\n")

# --- MQTT Listener ---
def on_connect(client, userdata, flags, reason_code, properties=None):
    log_event(f"MQTT: Connected to local broker. Subscribing to enet/#...")
    client.subscribe("enet/#")

def on_message(client, userdata, msg):
    payload = msg.payload.decode('utf-8')
    # Suppress gateway log output to avoid recursive prints
    if "/gateway/log" in msg.topic:
        return
    log_event(f"MQTT EVENT: {msg.topic} -> {payload}")

def start_mqtt():
    client = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION2)
    client.on_connect = on_connect
    client.on_message = on_message
    try:
        client.connect("localhost", 11883, 60)
        client.loop_forever()
    except Exception as e:
        log_event(f"MQTT Error: {e}")

# --- SSH Log Tailing ---
def start_ssh_tail():
    log_event("SSH: Connecting to eNet box (10.0.0.9) to tail Felix logs...")
    cmd = [
        "sshpass", "-p", "pvxtwl",
        "ssh", "-o", "StrictHostKeyChecking=no", "root@10.0.0.9",
        "tail -f -n 0 /home/insta/felix-framework/log/messages"
    ]
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in iter(proc.stdout.readline, ''):
            line = line.strip()
            # Filter lines containing gateway activity
            if "HostrupEnet:" in line or "OMNI-SNIFFER" in line or "teamr3" in line.lower():
                # Clean up the syslog header for readability
                if "eNetServer:" in line:
                    line = line.split("eNetServer:", 1)[1].strip()
                log_event(f"OSGi EVENT: {line}")
    except Exception as e:
        log_event(f"SSH Tail Error: {e}")

def main():
    # Start MQTT listener in a background thread
    mqtt_thread = threading.Thread(target=start_mqtt, daemon=True)
    mqtt_thread.start()

    # Start SSH tailing in the main thread (blocking)
    start_ssh_tail()

if __name__ == "__main__":
    main()
