#!/bin/bash
# =====================================================
# eNet MQTT Gateway — MQTT Monitor
# Viser al MQTT-trafik fra brokeren
# =====================================================

BROKER_HOST="${MQTT_HOST:-"10.0.0.6"}"
BROKER_PORT="${MQTT_PORT:-"1883"}"
MQTT_USER="${MQTT_USER:-"homeassistant"}"
MQTT_PASS="${MQTT_PASS:-"eih3Soh8Ioceon5ughawief2Chahn3aeShieW3queisee9zohch0ephaew0shaid"}"

echo "🔍 Lytter på MQTT-trafik fra broker..."
echo "   Broker: $BROKER_HOST:$BROKER_PORT"
echo "   Topics: enet/# og homeassistant/#"
echo "   (Tryk Ctrl+C for at stoppe)"
echo ""

if command -v mosquitto_sub &> /dev/null; then
    mosquitto_sub -h "$BROKER_HOST" -p "$BROKER_PORT" \
        -u "$MQTT_USER" -P "$MQTT_PASS" \
        -t 'enet/#' \
        -t 'homeassistant/#' \
        -v 2>&1
else
    echo "⚠️  mosquitto_sub ikke fundet. Prøver Docker mosquitto container..."
    sudo docker exec -it enet-dev-mosquitto \
        mosquitto_sub -h localhost -p 1883 \
        -t 'enet/#' -t 'homeassistant/#' -v 2>/dev/null || echo "Ingen lokal mosquitto container fundet"
fi
