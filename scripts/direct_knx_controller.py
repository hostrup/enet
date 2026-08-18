#!/usr/bin/env python3
"""
===================================================================
eNet Direct KNX-RF Hardware Controller
Constructs raw FT1.2 / cEMI frames, calculates checksums, and transmits
directly to the Atmel ATxmega radio coprocessor via /dev/ttymxc1.
===================================================================
"""

import sys
import time
import subprocess

ENET_HOST = "10.0.0.9"
ENET_PASS = "pvxtwl"

def build_ft12_cemi_frame(target_channel, opcode, value, ctrl_byte=0x53):
    """
    Builds a complete FT1.2 frame carrying a KNX-RF cEMI L_Data.req payload.
    
    Structure:
    0x68 [len] [len] 0x68 [ctrl] [cEMI L_Data.req header...] [target_ch] [opcode] [value] [checksum] 0x16
    """
    # Base cEMI L_Data.req template for eNet Multirum / Actuator control
    # Source: eNet Server Controller (0x1C60), Action parameters
    cemi_header = [
        0x11,             # cEMI Message Code: L_Data.req
        0x00,             # Additional info length
        0x1C, 0x60,       # Source Address: Controller (1.12.96 / 0x1C60)
        0x00, 0x00, 0x00, # Subnet / Domain
        target_channel,   # Target Channel Index (e.g. 0x01 for Ch 1, 0x1D for Ch 4)
        0x06, 0x02,       # Routing / Hop count / Length
        0xC7, 0x00, 0x3E, # APCI / Command flags
        0x00,             # Parameter offset
        opcode,           # Opcode: 0x50 (Switch), 0x52 (Dimming/Brightness)
        value             # Value: 0x01 (ON), 0x00 (OFF), or 0x00-0xFF (0-255 brightness)
    ]
    
    payload = [ctrl_byte] + cemi_header
    payload_len = len(payload)
    
    # Calculate FT1.2 Checksum: 8-bit arithmetic sum of ctrl + cemi
    checksum = sum(payload) % 256
    
    # Assemble full FT1.2 Variable Frame
    frame = [0x68, payload_len, payload_len, 0x68] + payload + [checksum, 0x16]
    return bytes(frame)

def send_raw_frame_to_hardware(frame_bytes):
    """
    Transmits the raw frame bytes directly to /dev/ttymxc1 via SSH.
    """
    hex_str = " ".join(f"\\x{b:02x}" for b in frame_bytes)
    remote_cmd = f"printf '{hex_str}' > /dev/ttymxc1"
    
    ssh_cmd = [
        "sshpass", "-p", ENET_PASS,
        "ssh", "-o", "StrictHostKeyChecking=no", f"root@{ENET_HOST}",
        remote_cmd
    ]
    
    res = subprocess.run(ssh_cmd, capture_output=True, text=True)
    return res.returncode == 0

def main():
    if len(sys.argv) < 2:
        print("Usage:")
        print("  python3 scripts/direct_knx_controller.py on [brightness 0-255]")
        print("  python3 scripts/direct_knx_controller.py off")
        print("  python3 scripts/direct_knx_controller.py dim <0-255>")
        print("  python3 scripts/direct_knx_controller.py raw <hex_bytes>")
        sys.exit(1)

    cmd = sys.argv[1].lower()
    
    # Target Channel for Multirum Spots: 0x01
    target_channel = 0x01
    
    if cmd == "on":
        if len(sys.argv) > 2:
            bright = int(sys.argv[2])
            print(f"📡 Sending Direct KNX-RF Command: ON with brightness {bright} (0x{bright:02X})...")
            frame = build_ft12_cemi_frame(target_channel, 0x52, bright)
        else:
            print("📡 Sending Direct KNX-RF Command: ON (Switch Opcode 0x50, Value 0x01)...")
            frame = build_ft12_cemi_frame(target_channel, 0x50, 0x01)
    elif cmd == "off":
        print("📡 Sending Direct KNX-RF Command: OFF (Switch Opcode 0x50, Value 0x00)...")
        frame = build_ft12_cemi_frame(target_channel, 0x50, 0x00)
    elif cmd == "dim":
        bright = int(sys.argv[2]) if len(sys.argv) > 2 else 128
        print(f"📡 Sending Direct KNX-RF Command: DIM to {bright}/255 (Dim Opcode 0x52, Value 0x{bright:02X})...")
        frame = build_ft12_cemi_frame(target_channel, 0x52, bright)
    elif cmd == "raw":
        raw_hex = sys.argv[2].replace(" ", "")
        frame = bytes.fromhex(raw_hex)
        print(f"📡 Sending Raw Hex Bytes: {frame.hex()}...")
    else:
        print(f"Unknown command: {cmd}")
        sys.exit(1)

    hex_display = " ".join(f"{b:02X}" for b in frame)
    print(f"   FT1.2 Frame [{len(frame)} bytes]: {hex_display}")
    
    success = send_raw_frame_to_hardware(frame)
    if success:
        print("✅ Frame successfully injected directly into /dev/ttymxc1 (ATxmega Radio)!")
    else:
        print("❌ Failed to inject frame to hardware.")

if __name__ == "__main__":
    main()
