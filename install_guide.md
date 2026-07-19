# eNet Smart Home Server — Komplet Installationsguide (Wizard Edition)

> **Fra sort metal-boks til fuldt integreret MQTT Smart Home Gateway på 5 minutter**
>
> Denne guide beskriver den interaktive installations-wizard, som automatisk logger på din Gira/Jung eNet-server, udtrækker sessionscookies via JSON-RPC, bygger en Zip Slip RCE-payload med dine personlige indstillinger (inkl. dit eget SSH root-password) og udruller gatewayen uden behov for manuelle web- eller curl-operationer.

---

## Indholdsfortegnelse

1. [Målsystemets Arkitektur](#1-målsystemets-arkitektur)
2. [Sårbarhedsanalyse & Automatiseret Udnyttelse](#2-sårbarhedsanalyse--automatiseret-udnyttelse)
3. [Forberedelse](#3-forberedelse)
4. [Den Interaktive Wizard: build_unified_payload.py](#4-den-interaktive-wizard-build_unified_payloadpy)
5. [Trin-for-trin installationsflow](#5-trin-for-trin-installationsflow)
6. [Post-installation: Verifikation](#6-post-installation-verifikation)
7. [Deploy af efterfølgende MQTT-opdateringer](#7-deploy-af-efterfølgende-mqtt-opdateringer)
8. [Sikkerhed & Hærdning](#8-sikkerhed--hærdning)

---

## 1. Målsystemets Arkitektur

### Hardware & OS
* **Enhed:** JUNG/Gira eNet Smart Home Server v2.3.2
* **Processor (SoM):** Ka-Ro electronics TXUL-0011 med NXP i.MX6 UltraLite (Single-Core, 256 MB RAM, 2 GB eMMC).
* **OS:** Poky Yocto Linux v2.1.3 (Linux Kernel 4.4.15-insta).
* **Radiomodul:** 868 MHz KNX-RF via TI CC1101, forbundet via UART på `/dev/ttyAPP1`.

### Startsekvens & Felix OSGi
Systemet booter og starter et Apache Felix OSGi-framework via `/etc/init.d/felix.sh`. Felix indlæser systemets bundles i fire niveauer (Start Levels). Vores MQTT-gateway indlæses på **Start Level 4** for at sikre, at alle eNet middleware-tjenester (`ISimpleControl`, `IMiddleware`) er fuldt initialiserede forinden.

---

## 2. Sårbarhedsanalyse & Automatiseret Udnyttelse

Installationsprocessen kæder tre sårbarheder sammen for at opnå permanent, priviligeret SSH-adgang:

1. **JSON-RPC Session Retrieval (CWE-287):** 
   Ved at indsende en `userLogin`-forespørgsel til `/jsonrpc/management` med gyldige admin-legitimationsoplysninger, returnerer eNet-serveren en `INSTASESSIONID` cookie. Vores script automatiserer dette og gemmer cookien i en virtuel `CookieJar`.
2. **Zip Slip / Arbitrary File Write (CWE-22):** 
   Projektupload-tjenesten (`doPostProjectFile`) i eNet udpakker ZIP-arkiver uden at validere destinationsstierne. Ved at inkludere path-traversal stier (f.eks. `../../../../../../home/insta/felix-framework/script/restartFelix`), kan vi skrive vilkårlige filer overalt på boksens filsystem med root-rettigheder.
3. **Privilege Execution (RCE):** 
   Når Felix modtager et signal om at genstarte (`restartFelix`), kører systemet `/home/insta/felix-framework/script/restartFelix` som **root**. Vores script overskriver denne fil med et installationsscript, som derefter eksekveres automatisk.

---

## 3. Forberedelse

Før du kører guiden, skal du sikre dig følgende lokalt:

1. **Byg Java MQTT Gateway-JAR:**
   Gateway-JAR-filen skal bygges til Java 8 target. Kør følgende kommando:
   ```bash
   cd /hostrup/data/dev/enet
   bash scripts/build.sh
   ```
   Dette danner filen `felix/target/enet-mqtt-2.0-PRODUCTION.jar`.

2. **Dropbear SSH Binary (Valgfrit, men anbefalet):**
   For at installere SSH skal du lægge en statisk kompileret `armv7l` binary af Dropbear kaldet `dropbearmulti` i projektets rod-mappe. Hvis den ikke findes, vil installationsscriptet forsøge at anvende eNet-serverens eksisterende Dropbear.

---

## 4. Den Interaktive Wizard: build_unified_payload.py

Dette Python 3 script guider dig igennem hele processen. Det er 100% uafhængigt af eksterne biblioteker (kræver ingen `requests` eller `pip install`).

Gem koden i `/hostrup/data/dev/enet/build_unified_payload.py`:

```python
# Koden findes i repoet under build_unified_payload.py
```

*Se den fulde kildekode direkte i filen [build_unified_payload.py](file:///hostrup/data/dev/enet/build_unified_payload.py).*

---

## 5. Trin-for-trin installationsflow

Kør installationsguiden fra din terminal:

```bash
cd /hostrup/data/dev/enet
python3 build_unified_payload.py
```

### De 7 trin i guiden:

1. **Trin 1: Indtastning af konfiguration**
   Guiden spørger efter eNet-IP, admin-login, MQTT broker-oplysninger og det ønskede SSH root-password.
2. **Trin 2: Login og Cookie-hentning**
   Forbinder til `/jsonrpc/management` og henter `INSTASESSIONID`.
3. **Trin 3: LFI Verifikation**
   Udfører et hurtigt path-traversal kald mod `/etc/init.d/felix.sh` for at sikre, at sårbarheden kan udnyttes, og at cookien er aktiv.
4. **Trin 4: Payload Bygning**
   Skaber `payload_unified.zip` i RAM/disk med det genererede installationsscript, Java JAR'en og Dropbear SSH-filerne.
5. **Trin 5: Upload via Zip Slip**
   Sender ZIP-filen direkte til `/storage/upload` med den gyldige sessionscookie.
6. **Trin 6: Trigger genstart**
   Sender et JSON-RPC signal (`restartFelix`), som tvinger Felix til at genstarte og afvikle vores script som root.
7. **Trin 7: Verifikation**
   Venter 25 sekunder på, at systemet starter op igen, og tester derefter TCP-forbindelsen til port 22 (SSH) for at bekræfte succesen.

---

## 6. Post-installation: Verifikation

### SSH Forbindelse
Når guiden melder succes, kan du SSH ind på boksen med det valgte password:
```bash
ssh root@10.0.0.9
```

### Tjek installationsloggen på boksen
Alle operationer logges til `/tmp/enet_install.log`. Læs loggen for at bekræfte, at alt gik godt:
```bash
cat /tmp/enet_install.log
```

### Tjek MQTT Gateway status via HTTP
Gatewayen kører sit eget web-dashboard på port 8090. Hent status:
```bash
curl -s http://10.0.0.9:8090/mqtt?action=status
```

---

## 7. Deploy af efterfølgende MQTT-opdateringer

Efter den indledende installation behøver du ikke køre guiden igen for at ændre Java-koden. Du kan foretage "hot-reloads" via SSH og HTTP-reload API'et:

```bash
# Byg lokalt
bash scripts/build.sh

# Upload direkte til boksens autostart-mappe via SSH pipe
cat felix/target/enet-mqtt-2.0-PRODUCTION.jar | \
  ssh root@10.0.0.9 "cat > /home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar"

# Trigger genindlæsning af modulet (Hot-reload)
curl -X POST "http://10.0.0.9:8090/mqtt/api?action=reload"
```

---

## 8. Sikkerhed & Hærdning

Efter succesfuld udrulning anbefales det at hærde eNet-serveren yderligere:

1. **SSH Key-Auth:**
   Kopiér din SSH public key til boksen:
   ```bash
   ssh-copy-id root@10.0.0.9
   ```
2. **Deaktiver password-login i Dropbear:**
   Rediger `/etc/init.d/felix.sh` på boksen og tilføj `-s` flaget til Dropbear-opstartskommandoen for at deaktivere password-logins helt:
   ```bash
   # I /etc/init.d/felix.sh:
   /usr/sbin/dropbearmulti dropbear -s -E -R -p 22 &
   ```
   Dette sikrer, at kun enheder med godkendte SSH-nøgler kan få SSH-adgang til boksen.