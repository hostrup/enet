#!/bin/bash
# =====================================================
# eNet MQTT Gateway — Live Log Watcher
# Følger eNet-boksens Java/Felix logfiler live via SSH
# =====================================================

ENET_HOST="10.0.0.9"
ENET_PASS="pvxtwl"
LOG_FILE="/home/insta/felix-framework/log/messages"

echo "📋 Følger eNet-boks log ($ENET_HOST)..."
echo "   Logfil: $LOG_FILE"
echo "   (Tryk Ctrl+C for at stoppe)"
echo ""

sshpass -p "$ENET_PASS" ssh -o StrictHostKeyChecking=no root@"$ENET_HOST" \
    "tail -f $LOG_FILE"
