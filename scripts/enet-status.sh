#!/bin/bash
# =====================================================
# eNet MQTT Gateway — eNet Server Status
# Viser tilstanden på eNet-boksen: processer, porte,
# bundles og MQTT config
# =====================================================

ENET_HOST="10.0.0.9"
ENET_PASS="pvxtwl"

echo "🔍 Henter status fra eNet-boksen ($ENET_HOST)..."
echo ""

sshpass -p "$ENET_PASS" ssh -o StrictHostKeyChecking=no root@"$ENET_HOST" '
echo "=== JAVA PROCESS ==="
ps w | grep java | grep -v grep

echo ""
echo "=== AKTIVE PORTE ==="
netstat -tlnp 2>/dev/null | grep -E "8090|1883|22|80"

echo ""
echo "=== MQTT CONFIG ==="
cat /home/insta/felix-framework/conf/mqtt-gateway.properties

echo ""
echo "=== FELIX STARTLEVEL4 STATUS ==="
grep -A5 "felix.auto.start.4" /home/insta/felix-framework/conf/config.properties | grep -v "^#" | head -10

echo ""
echo "=== SENESTE JAVA LOGS (20 linjer) ==="
tail -20 /home/insta/felix-framework/log/messages | grep -i "hostrup\|mqtt\|Error\|FATAL\|Bundle\|Starting" 2>/dev/null || tail -10 /home/insta/felix-framework/log/messages
'
