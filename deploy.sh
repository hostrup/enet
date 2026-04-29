#!/bin/bash
# ==========================================
# eNet OSGi Hot-Deploy Script (Optimeret)
# ==========================================

# Stop scriptet omgående, hvis en kommando fejler
set -e

echo "⏳ 1. Kompilerer Java-koden..."

# Kør Maven. Hvis det fejler, fanges det af IF-sætningen, og scriptet dræbes.
if ! mvn clean package; then
    echo ""
    echo "❌ KATASTROFE: Kompileringen fejlede!"
    echo "❌ Deployment afbrudt. Ret fejlen i Java-koden og prøv igen."
    exit 1
fi

echo ""
echo "✅ Kompilering lykkedes!"
echo "📡 2. Uploader ny .jar fil til eNet-boksen..."

# Vi beder eNet-boksen om at hente den friske fil fra din NUC (10.0.0.2)
# HUSK: Du skal have `python3 -m http.server 8080` kørende i din 'target' mappe på NUC'en i et andet terminalvindue.
ssh root@10.0.0.9 "wget -q -O /home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar http://10.0.0.2:8080/enet-mqtt-2.0-PRODUCTION.jar"

echo "🔄 3. Trigger OSGi Hot-Reload via vores eget API..."
curl -s -X POST http://10.0.0.9:8090/mqtt/api?action=reload

echo ""
echo "🎉 SUCCESS! MQTT Gateway er opdateret og genstartet i hukommelsen."