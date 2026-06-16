#!/bin/bash
# =====================================================
# eNet MQTT Gateway — Build Script
# Bygger .jar til Java 8 target (eNet-boks runtime)
# =====================================================
set -e

FELIX_DIR="/hostrup/data/dev/enet/felix"
cd "$FELIX_DIR"

echo "⏳ Bygger eNet MQTT Gateway (Java 8 target)..."
mvn clean package -q

JAR="$FELIX_DIR/target/enet-mqtt-2.0-PRODUCTION.jar"
if [ -f "$JAR" ]; then
    echo "✅ Build SUCCESS!"
    echo "   Størrelse: $(du -sh $JAR | cut -f1)  →  $JAR"
else
    echo "❌ Build fejlede — .jar ikke fundet"
    exit 1
fi
