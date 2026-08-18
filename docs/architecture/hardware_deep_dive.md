# JUNG / Gira eNet Server 2 — Hardware & Low-Level Protocol Deep Dive

Denne dokumentation beskriver den præcise hardwarearkitektur, mikrokontrollere, bus-forbindelser, GPIO pinouts og serielle protokoller på JUNG/Gira eNet Smart Home Server 2 REG.

---

## 1. Hardware Oversigt

| Komponent | Specifikation / Detalje | Formål |
|---|---|---|
| **SoM (System on Module)** | Ka-Ro electronics `TXUL-0011` | Hovedcomputermodul |
| **Hovedprocessor (SoC)** | NXP / Freescale i.MX6 UltraLite (`imx6ul`, Cortex-A7 @ 528 MHz) | Kører Linux OS, Apache Felix OSGi & Java |
| **RAM & Flash** | 256 MB DDR3 RAM, 2.0 GiB eMMC Flash | Systemlager og eksekveringshukommelse |
| **Radio-mikrokontroller** | **Atmel ATxmega** | Håndterer 868.3 MHz KNX-RF radiostakken |
| **Baseboard-mikrokontroller** | **Atmel ATmega** (ATmega8 / ATmega128) | Styrer frontpanel-taster, status-LEDs og strømstyring |

---

## 2. Busser, Interfaces og Pinouts

```
 +-----------------------------------------------------------------------+
 |                     NXP i.MX6 UltraLite (Cortex-A7)                   |
 |                                                                       |
 |   [Java JVM / Felix]                 [CTreiberCross C-Daemon]         |
 |            |                                    |                     |
 |    libkdriveJniAdapter.so                       |                     |
 +------------+------------------------------------+---------------------+
              |                                    |
   UART2 (/dev/ttymxc1)             SPI (/dev/spidev3.0) & UART3 (/dev/ttymxc2)
   19200 baud, 8E1                  115200 baud, 8N1
   FT1.2 / cEMI Protocol            Proprietær Insta SPI Protocol
              |                                    |
              v                                    v
     +------------------+                 +------------------+
     |  Atmel ATxmega   |                 |   Atmel ATmega   |
     | (KNX-RF 868 MHz) |                 | (Baseboard/LEDs) |
     +------------------+                 +------------------+
              |                                    |
      868.3 MHz KNX-RF                    Frontpanel knapper & LEDs
   (Lys, Dæmpere, Vægtryk)
```

### Serielle Porte & Baudrates
- **`/dev/ttymxc1` (`/dev/ttyAPP1`):**
  - **Baudrate:** `19200 baud`, `8 data bits`, `Even parity (8E1)`, `1 stop bit`.
  - **Protokol:** FT1.2 (Variable Frame & Single Character) med KNX cEMI (Common External Message Interface).
  - **Forbindelse:** Direkte forbundet til **ATxmega** KNX-RF radiomodul.
  - **Hardware Reset Pin:** GPIO 28 (`gpio28` / `GPIO1[28]` på HW v2, eller `gpio88` på HW v1).
- **`/dev/ttymxc2` (`/dev/ttyAPP3`):**
  - **Baudrate:** `115200 baud`, `8 data bits`, `No parity (8N1)`, `1 stop bit`.
  - **Forbindelse:** Forbundet til **ATmega** baseboard controller.
- **`/dev/spidev3.0`:**
  - SPI bus interface benyttet af `CTreiberCross` til direkte ATmega IO og frontpanel.

---

## 3. Protokollen mellem i.MX6 og ATxmega (FT1.2 & cEMI)

Kommunikationen over `/dev/ttymxc1` følger standard KNX FT1.2 overførsel:

### 1. FT1.2 Variable Frame Format (Send/Receive Telegram)
```
+------+--------+--------+------+--------------+----------+------+
| 0x68 | Length | Length | 0x68 | Control Byte | Data...  | 0x16 |
+------+--------+--------+------+--------------+----------+------+
```
- `0x68`: Start-byte (gentages to gange med længden imellem).
- `Control Byte`: `0x53`, `0x73`, `0xD3` (styring af sekvensnumre og handshake).
- `Data`: KNX cEMI ramme (`L_Data.req`, `L_Data.con`, `L_Data.ind`).
- `0x16`: Slut-byte (End character).

### 2. FT1.2 Acknowledgment (ACK)
- `0xE5`: Single-character ACK.
- ATxmega returnerer `0xE5` inden for få millisekunder efter modtagelse af en gyldig FT1.2 ramme.

---

## 4. Lokale Sockets & Dæmoner

- **`CTreiberCross` (PID 718):**
  - Lytter på TCP port **`127.0.0.1:5000`** (`osgi2atmega`).
  - Sender events til Java på TCP port **`127.0.0.1:5001`** (`atmega2osgi`).
- **`libkdriveJniAdapter.so`:**
  - Indlæses i Java JVM og forbinder direkte til `/dev/ttymxc1` som en native KNX-RF stack.
