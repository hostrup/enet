#!/bin/bash
# =====================================================
# eNet MQTT Gateway — MQTT Monitor
# Viser al MQTT-trafik fra den lokale dev-broker
# på port 11883
# =====================================================

BROKER_HOST="localhost"
BROKER_PORT="11883"

echo "🔍 Lytter på MQTT-trafik fra lokal dev-broker..."
echo "   Broker: $BROKER_HOST:$BROKER_PORT"
echo "   Topics: enet/# og homeassistant/#"
echo "   (Tryk Ctrl+C for at stoppe)"
echo ""

# Kræver mosquitto-clients. Installer med: sudo dnf install mosquitto
if command -v mosquitto_sub &> /dev/null; then
    mosquitto_sub -h "$BROKER_HOST" -p "$BROKER_PORT" \
        -t 'enet/#' \
        -t 'homeassistant/#' \
        -v 2>&1
else
    echo "⚠️  mosquitto_sub ikke fundet. Prøver Docker exec i stedet..."
    sudo docker exec -it enet-dev-mosquitto \
        mosquitto_sub -h localhost -p 1883 \
        -t 'enet/#' -t 'homeassistant/#' -v
fi
