#!/bin/bash
# =====================================================
# eNet MQTT Gateway — Deploy Script
# Bygger og uploader .jar til eNet-boksen (10.0.0.9)
# og trigger OSGi Hot-Reload via HTTP API
# =====================================================
set -e

SCRIPT_DIR="$(builtin cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
FELIX_DIR="$(builtin cd "$SCRIPT_DIR/../felix" >/dev/null 2>&1 && pwd)"
ENET_HOST="${ENET_HOST:-"10.0.0.9"}"
ENET_PASS="${ENET_PASS:-"pvxtwl"}"
JAR="$FELIX_DIR/target/enet-mqtt-2.0-PRODUCTION.jar"
REMOTE_PATH="/home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar"

echo "⏳ 1. Bygger..."
bash "$SCRIPT_DIR/build.sh"

echo ""
echo "📡 2. Uploader .jar til eNet-boksen ($ENET_HOST) via SSH pipe..."
# Bemærk: Dropbear på eNet-boksen understøtter IKKE sftp - bruger cat pipe i stedet
cat "$JAR" | sshpass -p "$ENET_PASS" ssh -o StrictHostKeyChecking=no "root@${ENET_HOST}" \
    "cat > $REMOTE_PATH"
echo "   Upload OK! ($(du -sh "$JAR" | cut -f1))"

echo ""
echo "🔄 3. Trigger OSGi Hot-Reload..."
RELOAD_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://${ENET_HOST}:8090/mqtt?action=reload")
if [ "$RELOAD_RESPONSE" = "200" ]; then
    echo "   Hot-Reload signal sendt (HTTP 200)"
else
    echo "   ⚠️  HTTP $RELOAD_RESPONSE — Reload API ikke tilgængelig. Prøv manuel genstart:"
    echo "   sshpass -p '$ENET_PASS' ssh root@$ENET_HOST '/etc/init.d/felix.sh restart'"
fi

echo ""
echo "🎉 Deploy komplet!"
echo "   Tjek logs: bash $SCRIPT_DIR/watch-logs.sh"
echo "   Tjek MQTT: bash $SCRIPT_DIR/test-mqtt.sh"
