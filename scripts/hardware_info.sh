#!/bin/bash
# =====================================================
# eNet Server 2 — Complete Hardware & Driver Inspector
# =====================================================

ENET_HOST="${ENET_HOST:-"10.0.0.9"}"
ENET_PASS="${ENET_PASS:-"pvxtwl"}"

echo "========================================================"
echo "📡 eNet Server 2 Hardware & Bus Diagnostics ($ENET_HOST)"
echo "========================================================"
echo ""

sshpass -p "$ENET_PASS" ssh -o StrictHostKeyChecking=no "root@$ENET_HOST" '
echo "--- 1. SYSTEM ON MODULE & CPU ---"
cat /sys/firmware/devicetree/base/model 2>/dev/null; echo ""
grep -E "model name|Hardware|BogoMIPS|Features" /proc/cpuinfo 2>/dev/null | head -n 4

echo ""
echo "--- 2. MEMORY & STORAGE PARTITIONS ---"
free -m
echo ""
df -h | grep -E "Filesystem|root|mmc|home"

echo ""
echo "--- 3. SERIAL TTY INTERFACES ---"
for dev in /dev/ttymxc0 /dev/ttymxc1 /dev/ttymxc2; do
    if [ -e "$dev" ]; then
        SPEED=$(stty -F "$dev" speed 2>/dev/null || echo "N/A")
        FLAGS=$(stty -F "$dev" -a 2>/dev/null | grep -E "parenb|cs8|cstopb" | tr -s " " | head -n 1)
        echo "   $dev -> Speed: $SPEED baud | Flags: $FLAGS"
    fi
done

echo ""
echo "--- 4. SPI & GPIO STATUS ---"
ls -l /dev/spidev* 2>/dev/null
echo "   GPIO Chips available:"
ls -d /sys/class/gpio/gpiochip* 2>/dev/null | xargs -n 1 basename

echo ""
echo "--- 5. LOW-LEVEL DAEMONS & PORTS ---"
echo "   CTreiberCross (ATmega baseboard daemon):"
ps w | grep CTreiberCross | grep -v grep
echo "   Java JVM (Felix OSGi & kdrive KNX-RF stack):"
ps w | grep java | grep -v grep | cut -c 1-100
echo "   Internal Communication Sockets:"
netstat -tnpa 2>/dev/null | grep -E "5000|5001"

echo ""
echo "--- 6. RECENT KERNEL / HARDWARE MESSAGES ---"
dmesg | tail -n 10
'
