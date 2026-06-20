# eNet Architectural Overview & Security Audit Report

This report provides a comprehensive architectural mapping and security audit of the eNet project located at `/home/hostrup/enet_project/`. It details the OSGi bundle packaging, system boundaries, and native driver communications, followed by a rigorous defensive coding and vulnerability analysis.

---

## 1. Architectural Overview & Component Mapping

The eNet system is structured as an OSGi-based Java application running on the **Apache Felix** framework. It interfaces with specialized transceiver hardware (using ATmega8 and Xmega128 microcontrollers) via a native C driver daemon. 

### 1.1 OSGi Bundle Stack & Packaging

The runtime environment organizes bundles into four distinct start levels to ensure correct initialization dependency ordering:

```
+-----------------------------------------------------------------------+
|                    Start Level 4: Network Advertising                 |
| - com.insta.instanet.instanetbox.mdns-2.31-SNAPSHOT.jar               |
+-----------------------------------------------------------------------+
                                   ▲
+-----------------------------------------------------------------------+
|              Start Level 3: Middleware & Servlet Services             |
| - com.insta.instanet.instanetbox.server.servlet-6.14.0-SNAPSHOT.jar   |
| - com.insta.instanet.instanetbox.server.api-6.14.0-SNAPSHOT.jar       |
| - com.insta.instanet.instanetbox.middleware.system-handler-2.31.jar   |
| - com.insta.instanet.instanetbox.middleware.instanet-boxdevice.jar   |
| - com.insta.instanet.instanetbox.applications-2.31-SNAPSHOT.jar       |
| - com.insta.instanet.instanetbox.facade.application-2.31.jar          |
+-----------------------------------------------------------------------+
                                   ▲
+-----------------------------------------------------------------------+
|              Start Level 2: Shared Libraries & Web Container          |
| - pax-web-jetty-insta-bundle-4.2.4.jar (Jetty Web Server)             |
| - com.sonove.persistence-2.31.0-SNAPSHOT.jar (Data Persistence)       |
| - jackson-databind-2.8.9.jar / joda-time-2.8.2.jar                    |
+-----------------------------------------------------------------------+
                                   ▲
+-----------------------------------------------------------------------+
|               Start Level 1: OSGi Framework Core Services             |
| - org.apache.felix.eventadmin-1.4.6.jar (Event Broker)                |
| - org.apache.felix.configadmin-1.8.8.jar (Configuration Admin)        |
| - com.insta.instanet.instanetbox.common.environment.jar               |
+-----------------------------------------------------------------------+
```

### 1.2 System Boundaries & Communications

The boundary between the Java OSGi container and the physical transceiver is managed by the `com.insta.instanet.instanetbox.systemfunctions.instanetboxdevice` package and the native driver `CTreiberCross` (compiled for Cortex-A7 ARM Linux):

1. **Subprocess Management**: The Java class `DriverThread` spawns the native daemon `/home/insta/felix-framework/driver/CTreiberCross` with the `-nojavacheck` flag using Java's `ProcessBuilder`.
2. **IPC Channel (Local TCP Sockets)**: Communication occurs over a local TCP socket.
   * `CTreiberCross` acts as a TCP socket server, binding to `127.0.0.1` and listening on port `5000` (port `5000` is defined as `0x1388`, represented in big-endian network byte order as `0x8813` or `-0x77ed` signed 16-bit).
   * Java's `NetBoxDeviceClient` establishes a TCP client connection to `127.0.0.1:5000` to send command packets (binary commands prefixed with ASCII `'A'` (65)) and read responses.
3. **Firmware & Code Updates**: `DriverManager` contains methods to write raw updates to the native driver executable file directly on the file system and subsequently flags the file as executable.
4. **Native JNI Drivers**: In addition to the subprocess, the package `com.insta.instanet.instanetbox.transformation.knxrf` loads native JNI library proxies (`SerialPortProxy.java`, `BroadcastServicesProxy.java`, `ObjectServerProxy.java`) to make JNI calls directly into shared object libraries.

---

## 2. Core Class Relationships (Mermaid Class Diagram)

The class diagram below documents the key components, boundaries, and interactions of the servlet interface, authentication managers, process management, and native boundaries:

```mermaid
classDiagram
    class InstaboxServlet {
        +doPost(request, response) void
        -handleRPCRequest(request, response, reqStr, isBatch) RPCRequestResult
    }
    class UserManager {
        -registeredUserDeletedEventHandler : List
        +getInstance() UserManager
        +handleSecurity(request, response, forceSSL, roles, groups) boolean
        +loginBasic(user, pass) ClientCredentials
        +loginDigest(params) ClientCredentials
        +createDriverFromStream(stream) File
    }
    class SessionManager {
        -SESSIONS : Set~Session~
        +get() SessionManager
        +getCurrentSession() Session
        +getOrCreateSession(sessionId) Session
        +destroySession(session) void
    }
    class Session {
        -identifier : String
        -user : User
        -currentRole : String
        -transactions : List~String~
        +getSessionEventData() SessionEventsData
    }
    class UserManagerData {
        -USERMAP : Map~String, User~
        +get() UserManagerData
        +getUserPropertiesFilePath() String
    }
    class User {
        -username : String
        -password : String
        -group : String
        +serialize() String
        +authenticate(hash) boolean
    }
    class DriverManager {
        -driver : DriverThread
        +getDriverManager() DriverManager
        +startDriver() boolean
        +stopDriver() boolean
        +createDriverFromStream(stream) File
    }
    class DriverThread {
        -driver : Process
        +run() void
        -startDriver(log) void
    }
    class NetBoxDeviceClient {
        -serverAddress : SocketAddress
        -connection : Socket
        +process(msg, ignoreLock) Message
    }
    class CTreiberCross {
        <<native daemon>>
        +main(argc, argv) int
        +atmega2osgi(sock_fd) int
        +osgi2atmega(sock_fd) int
        +function_13458(sock, buf) int
    }

    InstaboxServlet --> SessionManager : resolves current session
    InstaboxServlet --> UserManager : checks security / login
    UserManager --> SessionManager : logs sessions out
    UserManager --> UserManagerData : accesses user records
    UserManagerData --> User : maps users
    SessionManager --> Session : manages
    Session --> User : holds
    DriverManager --> DriverThread : controls
    DriverThread --> "CTreiberCross" : spawns process
    NetBoxDeviceClient ..> "CTreiberCross" : communicates over TCP port 5000
    DriverManager ..> NetBoxDeviceClient : uses to check version/stop
```

---

## 3. Security Audit & Defensive Coding Analysis

The following section conducts a detailed security analysis focusing on input validation, serialization, memory safety, and native boundary vulnerabilities.

### 3.1 Severity Categorization

Findings are classified using standard severity guidelines:
*   **Blocker:** Critical flaws that allow immediate Remote Code Execution (RCE), complete system compromise, or hardware damage.
*   **High:** Major vulnerabilities allowing unauthorized network access, session hijacking, or hardware control from unauthenticated local interfaces.
*   **Medium:** Algorithmic weaknesses, info disclosures, or localized buffer issues requiring specialized environment setups to exploit.
*   **Low:** Hardening recommendations, Denial of Service (DoS) stability vectors, or logging flaws.

---

### 3.2 Security Audit Findings

### Finding 1: Unsigned Native Driver Binary Update (RCE)
*   **Severity:** Blocker
*   **Component:** `DriverManager.java` (Java OSGi Bundle)
*   **Vulnerability Type:** Remote Code Execution / Arbitrary File Write
*   **Description:** The method `createDriverFromStream(byte[] stream)` writes raw bytes directly to `/home/insta/felix-framework/driver/CTreiberCross` (resolving the whitespace split of the configuration string) and subsequently marks the file as executable (`file.setExecutable(true, false)`). There is no verification of cryptographic signatures, hash sums (e.g., SHA-256), or origin of the byte stream.
*   **Impact:** Any internal class, exposed API, or malicious JSON-RPC payload invoking this update routine can overwrite the native binary with a rogue executable, resulting in arbitrary execution under the privileges of the JVM process.

### Finding 2: Unbounded Socket Buffer Copy (Heap Overflow)
*   **Severity:** Blocker
*   **Component:** `CTreiberCross.c` (Native Driver Daemon)
*   **Vulnerability Type:** Heap-based Buffer Overflow
*   **Description:** Inside `function_13458` of the native C driver, packets are received from the TCP socket:
    ```c
    int32_t result = recv(sock, mem, 0x10000, 0);
    ...
    int32_t v6 = result - 7;
    *a3 = v6;
    if (v6 > 0) {
        int32_t v7 = a2 - 1;
        ...
        v7++;
        *(char *)v7 = *(char *)v8;
        while (v9 != ...) {
            ...
            v7++;
            *(char *)v7 = *(char *)v8;
        }
    }
    ```
    The variable `v6` (payload bytes received) controls the loop copying data to the buffer `a2`. There is no check to ensure `v6` is less than or equal to the size of the destination buffer `a2`. 
*   **Impact:** While the only current caller in the binary allocates a `0x10000` (64KB) heap buffer matching the recv size, any changes to callers or direct execution using smaller target buffers will result in heap destruction, memory corruption, and potentially native code execution.

### Finding 3: Unauthenticated Local TCP Socket Interface
*   **Severity:** High
*   **Component:** `CTreiberCross.c` / `NetBoxDeviceClient.java`
*   **Vulnerability Type:** Authentication Bypass / Access Control Failure
*   **Description:** The native daemon binds to `127.0.0.1:5000` to process incoming packets. The connection protocol does not perform any handshake, cryptographic validation, or password check. It merely inspects the first two bytes to verify they match `'A'` and `'B'` or `'C'` before decoding and executing instructions.
*   **Impact:** Any local user, low-privileged script, or parallel web application running on the host system can connect directly to port 5000 and spoof control packets to reset the transceiver, upload arbitrary firmware, or issue wireless commands to physical building automation units.

### Finding 4: Insecure Session Cookie Flags
*   **Severity:** High
*   **Component:** `ClassicHandleCookiesTask.java`
*   **Vulnerability Type:** Insufficient Cookie Security Attributes
*   **Description:** The session cookie `INSTASESSIONID` and the `rememberMe` cookie are added to the HttpServletResponse with `setMaxAge(Integer.MAX_VALUE)` (indefinite persistence in browser storage), but they are *not* flagged with `HttpOnly` or `Secure`.
*   **Impact:** 
    *   Missing `HttpOnly` allows JavaScript access, meaning a Cross-Site Scripting (XSS) exploit on the visualization client can immediately steal active user sessions.
    *   Missing `Secure` ensures the cookies are transmitted in cleartext over unencrypted HTTP connections, exposing them to local sniffing.

### Finding 5: Broken Password Hashing (MD5 HA1)
*   **Severity:** Medium
*   **Component:** `UserManager.java` / `User.java`
*   **Vulnerability Type:** Cryptographically Broken Authentication Hashing
*   **Description:** User passwords are encrypted using MD5 HA1 digests (`MD5(username:realm:password)`), which are subsequently serialized and stored as semicolon-separated strings inside `/settings/users.properties`.
*   **Impact:** MD5 is completely broken against collisions and rapid brute-forcing. If the database file `users.properties` is read via directory traversal or local file read issues, the administrative passwords can be cracked almost instantly.

### Finding 6: Unbounded Stack Formatting in C Driver
*   **Severity:** Medium
*   **Component:** `CTreiberCross.c`
*   **Vulnerability Type:** Stack-based Buffer Overflow
*   **Description:** In `function_11000`, the serial port initialization executes:
    ```c
    sprintf((char *)&str, "can't open device: %s\n", path);
    ```
    The variable `str` is stored on the stack (`bp-528` allocating 528 bytes). If the variable `path` exceeds approximately 500 bytes, `sprintf` will overflow the stack frame, overwriting the return address.
*   **Impact:** Currently, `path` is a constant string pointing to `/dev/ttyAPP3`. However, if the serial device path is modified in the future to be read from a configuration file or a user-supplied parameter, this introduces a direct stack overflow exploit vector.

### Finding 7: JNI Core Dump Risk (Denial of Service)
*   **Severity:** Low
*   **Component:** `com.insta.instanet.instanetbox.transformation.knxrf` (JNI Proxies)
*   **Vulnerability Type:** Memory Safety / Denial of Service
*   **Description:** The OSGi stack calls directly into native JNI code via `SerialPortProxy.java` and others. If the JNI library encounters a null-pointer dereference, invalid array boundary accesses, or uncaught native segment signals, it does not throw Java exceptions. Instead, it crashes the JVM process.
*   **Impact:** A crash in the JNI boundary brings down the entire Apache Felix container, disrupting all hosted HTTP servlets, visualization services, and system integrations.

---

## 4. Remediation & Hardening Roadmap

To mitigate the identified architectural and implementation risks, the following remediations must be applied:

| # | Finding / Vulnerability | Remediation Action |
| :- | :--- | :--- |
| 1 | **Unsigned Native Update** | Implement cryptographic signature verification (e.g., using RSA or ECDSA with public keys embedded in the Java bundle) on the update stream before writing to the filesystem. |
| 2 | **C Heap Buffer Overflow** | Refactor `function_13458` to pass the maximum size of the destination buffer `a2`. Replace the unsafe copy loop with a bounds-checked copy (e.g., `memcpy` with a checked length limit). |
| 3 | **Unauthenticated Local Socket** | Implement a local challenge-response or token-based authentication mechanism. Generate a secure random token in Java upon daemon startup, pass it to `CTreiberCross` via argv/env, and require it in the TCP socket header. |
| 4 | **Insecure Session Cookies** | Modify `ClassicHandleCookiesTask.java` to set `cookie.setHttpOnly(true)` and `cookie.setSecure(true)`. Limit the expiration age (`setMaxAge`) to a reasonable duration (e.g., 24 hours). |
| 5 | **Broken Password Hashing** | Replace MD5 digest generation with modern password hashing algorithms such as Argon2id or bcrypt. |
| 6 | **Unbounded Stack Formatting** | Replace the unsafe `sprintf` call in `CTreiberCross.c` with `snprintf(str, sizeof(str), "can't open device: %s\n", path)` to prevent stack corruption. |
| 7 | **JNI Crash Risk** | Add structured error-checking wrappers inside the native JNI libraries, and ensure all JNI calls are run in isolated, monitorable threads. |
