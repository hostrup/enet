Here is the complete, comprehensive `README.md` file written in English, documenting the entire reverse-engineering journey, the architecture of the solution, and the crucial do's and don'ts.

***

# JUNG/Gira eNet Smart Home - Native MQTT Gateway

This repository contains the complete documentation and source code for achieving full root access and injecting a native, zero-latency OSGi MQTT Gateway directly into a JUNG/Gira eNet Smart Home Server (v2.3.2). This integration completely bypasses the proprietary cloud, enabling local, real-time, two-way communication with Home Assistant.

## 📊 Hardware & System Specifications
Through deep kernel (`dmesg`) and system analysis, the eNet server's architecture was mapped:
* **System on Module (SoM):** Ka-Ro electronics TXUL-0011.
* **Processor (SoC):** NXP/Freescale i.MX6 UltraLite (`imx6ul`), ARMv7l 32-bit (Single-Core).
* **Memory & Storage:** 256 MB RAM and a 2.00 GiB eMMC Flash Drive. 
* **OS:** Poky Insta 2.1.3 (Yocto Project, Linux Kernel 4.4.15-insta).
* **Application Layer:** Apache Felix OSGi (Java 8 Embedded).
* **Hardware Interfacing:** The Java layer communicates via a JNI adapter (`libkdriveJniAdapter.so`) to a C-daemon (`CTreiberCross`), which talks directly to the 868 MHz KNX-RF UART radio on `/dev/ttyAPP1`.

---

## 🔓 Phase 1: From Zero to Root (Exploitation)
To deploy native Java code, root access to the highly restricted Linux environment was required. This was achieved by chaining multiple vulnerabilities.

1. **Session Hijacking:** The `INSTASESSIONID` cookie was extracted via the browser's developer tools upon logging into the web interface.
2. **Local File Inclusion (LFI):** The built-in `ResourceServlet` failed to sanitize path-traversal characters. This allowed read-only access to the root filesystem (e.g., `http://10.0.0.9////////../../../../../../etc/shadow`).
3. **Zip Slip (Remote Code Execution):** The `doPostProjectFile` method extracted uploaded `.zip` files without validating destination paths. A malicious Python-generated payload was uploaded to overwrite system scripts (`restartFelix` and `resetSystem`). Triggering a JSON-RPC restart executed a reverse shell.
4. **Permanent SSH Persistence:** To escape the reverse shell, the filesystem was remounted as Read/Write, and a statically compiled `armv7l` Dropbear SSH binary was deployed. Persistence was ensured by injecting the Dropbear startup command into `/etc/init.d/felix.sh`.

---

## 🧠 Phase 2: Reverse Engineering the Architecture
Before arriving at the final OSGi injection, two dead ends provided critical knowledge:
* **WSS Cloud Spoofing:** We initially hijacked the TLS session using a custom Python script, but discovered that the web API only registers "App Mode" events, rendering physical hardware buttons completely invisible.
* **Bare Metal KNX Interception:** We stopped the Java daemon and piped the raw `/dev/ttyAPP1` serial port via `socat` and `netcat`. We discovered the radio uses FT1.2 wrapped KNX cEMI formatting, but requires instantaneous `0xE5` heartbeat acknowledgments. This proved too unstable and resource-intensive for the tiny i.MX6 CPU.

**The Conclusion:** The only viable, latency-free solution was "Living off the Land". We built a custom OSGi bundle (`MqttActivator.java`) injected directly into Apache Felix (`startlevel4`) to hook natively into the system's memory.

---

## 🏗️ Phase 3: The Native OSGi Gateway
The Java Gateway uses OSGi Reflection to bypass ClassLoader isolation, gaining direct access to JUNG's core components like `ISimpleControl` and `IMiddleware`.

### Topography & Naming (The Hybrid Approach)
To present clean, human-readable names to Home Assistant, a hybrid discovery approach is used.
* **Primary:** We query the Middleware for the user-defined `InstallationArea` name.
* **Fallback:** If that fails, we use reflection on the `DeviceMetaData` to extract the factory `Designation` or `Name`.

### Actuators (Lights & Blinds)
* **Ingress (Reading):** We listen directly to JUNG's native `ISimpleControl` event handler (`endpointStateChanged`). This asynchronously catches state and brightness changes with zero latency.
* **Egress (Writing):** Incoming MQTT payloads (e.g., `{"state":"ON"}`) are parsed and injected back into `ISimpleControl.handleControlRequest()`. 

### Sensors (Physical Wall Buttons)
Handling buttons required building an OSGi "Omni-Sniffer" to understand JUNG's undocumented bus.
* **Ingress (Reading):** Physical buttons are stateless triggers. They do not broadcast state changes; instead, they fire the `MW/DeviceFunctionCalled` event on the OSGi bus. The payload provides the `deviceUID`, `functionUID`, and a boolean for `PRESS` (true) or `RELEASE` (false).
* **Egress (Virtual Presses):** To allow Home Assistant to "push" a physical button and trigger internal eNet scenes, the Gateway maps each button's `functionUID` during boot. An MQTT `PRESS` command executes `IMiddleware.callInputDeviceFunction()` via Java Reflection, simulating a physical interaction perfectly.

---

## 🚫 Do's and Don'ts (Crucial Findings)

### DO:
* **DO use `*` for the OSGi EventAdmin wildcard.** To build a global listener (the Omni-Sniffer), you must register the `EventHandler` with `EventConstants.EVENT_TOPIC` set to `*`.
* **DO map the `functionUID`.** Physical buttons can only be triggered programmatically if you extract and map their input function UIDs during the initial topology build.
* **DO log to RAM.** The system runs on a 2GB eMMC flash drive. To prevent SSD wear and tear, all live debug logs in the Gateway are written to a `ConcurrentLinkedQueue` in RAM and served via a lightweight Web UI (`http://[IP]/mqtt`).

### DON'T:
* **DON'T use `null` as an OSGi Event Filter.** Passing `null` to the `EventAdmin` properties does not create a global listener; it acts as a black hole and drops all events entirely.
* **DON'T sniff `MW/ValueChanged` for actuators.** During runtime, the eNet server creates dynamic, mirrored UIDs (e.g., containing `09a0`) for continuous value changes. Attempting to map these causes `ClassCastExceptions` and severe CPU/RAM spikes. Rely purely on `ISimpleControl` for actuators.
* **DON'T attempt "Continuous Dimming" over MQTT.** When a physical button is held down, the dimming occurs via direct hardware-to-hardware KNX-RF communication. The OSGi bus "holds its breath" until the button is released, at which point it broadcasts the total duration (`VT_ROCKER_SWITCH_TIME`) and the final brightness percentage. Forcing continuous software dimming would violate the strict 868 MHz Duty Cycle limits and crash the radio network.
* **DON'T install Python, Node.js, or external heavy libraries.** The i.MX6 processor only has 256MB of RAM. Keep the Gateway as a lightweight, native Java 8 OSGi bundle utilizing the existing Eclipse Paho MQTT client.