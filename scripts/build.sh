#!/bin/bash
# =====================================================
# eNet MQTT Gateway — Build Script
# Bygger .jar til Java 8 target (eNet-boks runtime)
# =====================================================
set -e

SCRIPT_DIR="$(builtin cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
FELIX_DIR="$(builtin cd "$SCRIPT_DIR/../felix" >/dev/null 2>&1 && pwd)"
cd "$FELIX_DIR"

echo "⏳ Bygger eNet MQTT Gateway (Java 8 target)..."
mvn clean package -q

JAR="$FELIX_DIR/target/enet-mqtt-2.0-PRODUCTION.jar"
if [ -f "$JAR" ]; then
    echo "✅ Build SUCCESS!"
    echo "   Størrelse: $(du -sh "$JAR" | cut -f1)  →  $JAR"
else
    echo "❌ Build fejlede — .jar ikke fundet"
    exit 1
fi
