#!/bin/bash
# =====================================================
# eNet MQTT Gateway — Enable Bundle Script
# Aktiverer MQTT-bundlen på eNet-boksen ved at
# opdatere config.properties og mqtt-gateway.properties
# OG genstarter Felix.
#
# BRUG: ./enable-bundle.sh [local|production]
#   local      → peger på lokal dev-broker (10.0.0.2:11883)  [DEFAULT]
#   production → peger på HA-broker (10.0.0.6:1883)
# =====================================================

ENET_HOST="10.0.0.9"
ENET_PASS="pvxtwl"
MODE="${1:-local}"

if [ "$MODE" = "production" ]; then
    MQTT_BROKER="tcp\\:\\/\\/10.0.0.6\\:1883"
    MQTT_BROKER_DISPLAY="tcp://10.0.0.6:1883 (PRODUKTION - Rigtige HA!)"
else
    MQTT_BROKER="tcp\\:\\/\\/10.0.0.2\\:11883"
    MQTT_BROKER_DISPLAY="tcp://10.0.0.2:11883 (Lokal dev-broker)"
fi

echo "⚙️  Aktiverer eNet MQTT Gateway..."
echo "   Mode: $MODE"
echo "   MQTT Broker: $MQTT_BROKER_DISPLAY"
echo ""

if [ "$MODE" = "production" ]; then
    echo "⚠️  ADVARSEL: Du er ved at pege på PRODUKTIONS-HA!"
    echo "   Tryk Ctrl+C inden for 5 sekunder for at annullere..."
    sleep 5
fi

sshpass -p "$ENET_PASS" ssh -o StrictHostKeyChecking=no root@"$ENET_HOST" "

# 1. Opdater config.properties — aktivér MQTT bundle i startlevel4
CONFIG=/home/insta/felix-framework/conf/config.properties

# Lav backup
cp \$CONFIG \${CONFIG}.bak_\$(date +%Y%m%d_%H%M%S)
echo '   Backup af config.properties lavet'

# Erstat de aktive startlevel4 linjer med versionen inkl. MQTT-bundle
# Fjerner de eksisterende aktive linjer og tilføjer nye
python3 -c \"
import re, sys

with open('\$CONFIG', 'r') as f:
    content = f.read()

# Erstat den aktive (ikke-kommenterede) felix.auto.install.4 og felix.auto.start.4
# med versioner der inkluderer enet-mqtt bundlen
new_install = '''felix.auto.install.4=file:///home/insta/felix-framework/bundle/startlevel4/com.insta.instanet.instanetbox.mdns-2.31-SNAPSHOT.jar \\\\\\nfile:///home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar'''

new_start = '''felix.auto.start.4=file:///home/insta/felix-framework/bundle/startlevel4/com.insta.instanet.instanetbox.mdns-2.31-SNAPSHOT.jar \\\\\\nfile:///home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar'''

# Erstat eksisterende aktive linjer (ikke kommenterede)
content = re.sub(
    r'^felix\.auto\.install\.4=file:///home/insta/felix-framework/bundle/startlevel4/com\.insta.*?(?=^\s*\$|\Z|^[^\\\\])',
    new_install + '\n\n',
    content, flags=re.MULTILINE | re.DOTALL
)
content = re.sub(
    r'^felix\.auto\.start\.4=file:///home/insta/felix-framework/bundle/startlevel4/com\.insta.*?(?=^\s*\$|\Z|^[^\\\\])',
    new_start + '\n\n',
    content, flags=re.MULTILINE | re.DOTALL
)

with open('\$CONFIG', 'w') as f:
    f.write(content)

print('   config.properties opdateret')
\" 2>&1

# 2. Opdater mqtt-gateway.properties med korrekt broker
MQTT_CONFIG=/home/insta/felix-framework/conf/mqtt-gateway.properties
cat > \$MQTT_CONFIG << 'MQTTEOF'
#eNet Native MQTT Gateway Configuration
MQTT_BROKER=$MQTT_BROKER
MQTT_USER=hostrup
MQTT_PASS=DellD820
MQTTEOF

echo '   mqtt-gateway.properties opdateret'

# 3. Genstart Felix
echo '   Genstarter Felix...'
/etc/init.d/felix.sh restart

echo '   Felix genstartet!'
"

echo ""
echo "⏳ Venter 15 sekunder på Felix og MQTT bundle opstart..."
sleep 15

echo ""
echo "🔍 Verificerer..."
sshpass -p "$ENET_PASS" ssh -o StrictHostKeyChecking=no root@"$ENET_HOST" '
echo "Aktive porte:"
netstat -tlnp 2>/dev/null | grep -E "8090|22"
echo ""
echo "Seneste log (MQTT/HostrupEnet):"
grep -i "hostrup\|mqtt\|8090" /home/insta/felix-framework/log/messages | tail -10
'

# Check port 8090
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 "http://${ENET_HOST}:8090/mqtt?action=status" 2>/dev/null || echo "000")
if [ "$HTTP_STATUS" = "200" ]; then
    echo ""
    echo "✅ SUCCESS! Web Dashboard svarer på http://${ENET_HOST}:8090/mqtt"
    echo "   MQTT Gateway er AKTIV!"
else
    echo ""
    echo "⚠️  Web Dashboard (port 8090) svarer ikke endnu (HTTP $HTTP_STATUS)"
    echo "   Bundle kan stadig starte op — tjek logs med: bash /hostrup/data/dev/enet/scripts/watch-logs.sh"
fi
