#!/usr/bin/env python3
"""
===================================================================
eNet KNX-RF UART Live Sniffer & Protocol Decoder
Captures and reassembles FT1.2 / KNX cEMI frames on /dev/ttymxc1 (UART2).
===================================================================
"""

import sys
import subprocess
import re
import time

ENET_HOST = "10.0.0.9"
ENET_PASS = "pvxtwl"

def parse_cemi_type(payload_bytes):
    """Identify KNX cEMI message code if present."""
    if len(payload_bytes) == 1 and payload_bytes[0] == 0xE5:
        return "FT1.2 ACK (0xE5)"
    
    if len(payload_bytes) >= 6 and payload_bytes[0] == 0x68 and payload_bytes[3] == 0x68:
        ctrl = payload_bytes[4]
        cemi_msg_code = payload_bytes[5]
        
        cemi_types = {
            0x11: "L_Data.req (Transmit Request)",
            0x2E: "L_Data.con (Transmit Confirm)",
            0x29: "L_Data.ind (Incoming RF Telegram / State)",
            0x10: "L_Raw.req",
            0x2D: "L_Raw.con",
            0x2B: "L_Raw.ind",
            0xF0: "M_PropRead.req",
            0xF1: "M_PropRead.con",
            0xF6: "M_PropWrite.req",
            0xF7: "M_PropWrite.con"
        }
        
        cemi_name = cemi_types.get(cemi_msg_code, f"cEMI Code 0x{cemi_msg_code:02X}")
        return f"FT1.2 Frame [Ctrl: 0x{ctrl:02X}] -> {cemi_name}"
        
    return "Serial Packet"

def octal_to_bytes(octal_str):
    """Convert C-style octal escape string (from strace) into raw bytes."""
    result = bytearray()
    i = 0
    while i < len(octal_str):
        if octal_str[i] == '\\':
            if i + 1 < len(octal_str):
                c = octal_str[i+1]
                if c == 'n': result.append(10); i += 2; continue
                elif c == 'r': result.append(13); i += 2; continue
                elif c == 't': result.append(9); i += 2; continue
                elif c == '\\': result.append(ord('\\')); i += 2; continue
                elif c == '"': result.append(ord('"')); i += 2; continue
                elif c in '01234567':
                    oct_digits = ""
                    j = i + 1
                    while j < len(octal_str) and len(oct_digits) < 3 and octal_str[j] in '01234567':
                        oct_digits += octal_str[j]
                        j += 1
                    result.append(int(oct_digits, 8))
                    i = j
                    continue
        result.append(ord(octal_str[i]))
        i += 1
    return bytes(result)

def main():
    duration = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    print("===================================================================")
    print(f"📡 Capturing eNet Server ({ENET_HOST}) KNX-RF UART (/dev/ttymxc1) for {duration}s...")
    print("   Protocol: FT1.2 (19200 baud, 8E1) <-> Atmel ATxmega KNX-RF Transceiver")
    print("===================================================================\n")

    remote_cmd = f'JAVA_PID=$(pidof java | cut -d" " -f1); timeout -t {duration} /usr/bin/strace -f -s 512 -p $JAVA_PID -e trace=read,write 2>&1 | grep "(118,"'
    ssh_cmd = [
        "sshpass", "-p", ENET_PASS,
        "ssh", "-o", "StrictHostKeyChecking=no", f"root@{ENET_HOST}",
        remote_cmd
    ]

    regex = re.compile(r'\[pid\s+(\d+)\]\s+(read|write)\(118,\s+"(.*)",\s+\d+\)\s+=\s+(\d+)')
    
    rx_buffer = bytearray()
    
    try:
        proc = subprocess.Popen(ssh_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        for line in proc.stdout:
            match = regex.search(line)
            if match:
                pid, op, raw_escaped, count = match.groups()
                chunk = octal_to_bytes(raw_escaped)
                
                if op == "write":
                    # TX is sent in one chunk
                    info = parse_cemi_type(chunk)
                    hex_dump = " ".join(f"{b:02X}" for b in chunk)
                    ts = time.strftime("%H:%M:%S")
                    print(f"[{ts}] ⬆️ TX (Host -> Radio) [{len(chunk):2d} bytes] {info}")
                    print(f"         HEX: {hex_dump}")
                else: # read
                    if chunk == b'\xe5':
                        ts = time.strftime("%H:%M:%S")
                        print(f"[{ts}] ⬇️ RX (Radio -> Host) [ 1 bytes] FT1.2 ACK (0xE5)")
                    else:
                        rx_buffer.extend(chunk)
                        # Check if FT1.2 frame complete (ends with 0x16 and length matches)
                        if len(rx_buffer) >= 6 and rx_buffer[0] == 0x68 and rx_buffer[3] == 0x68:
                            expected_len = rx_buffer[1] + 6 # payload + 2x0x68 + 2xlen + ctrl + 0x16
                            if len(rx_buffer) >= expected_len:
                                full_frame = bytes(rx_buffer[:expected_len])
                                rx_buffer = rx_buffer[expected_len:]
                                info = parse_cemi_type(full_frame)
                                hex_dump = " ".join(f"{b:02X}" for b in full_frame)
                                ts = time.strftime("%H:%M:%S")
                                print(f"[{ts}] ⬇️ RX (Radio -> Host) [{len(full_frame):2d} bytes] {info}")
                                print(f"         HEX: {hex_dump}")
    except KeyboardInterrupt:
        pass
    finally:
        print("\nCapture finished.")

if __name__ == "__main__":
    main()
