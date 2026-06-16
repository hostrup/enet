#!/bin/bash

OUTPUT_FILE="enet_mqtt_notebook.txt"

# Slet eksisterende fil for at undgå at appende til en gammel fil
if [ -f "$OUTPUT_FILE" ]; then
    rm "$OUTPUT_FILE"
fi

echo "Genererer samlet fil til NotebookLM: $OUTPUT_FILE"
echo "===================================================" > "$OUTPUT_FILE"
echo " JUNG ENET NATIVE MQTT GATEWAY (V11 MODULAR) " >> "$OUTPUT_FILE"
echo "===================================================" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

# Definer rækkefølgen af filer for bedste kontekstforståelse
FILES=(
    "pom.xml"
    "src/main/java/dk/teamr3/enet/MqttActivator.java"
    "src/main/java/dk/teamr3/enet/ConfigManager.java"
    "src/main/java/dk/teamr3/enet/MqttManager.java"
    "src/main/java/dk/teamr3/enet/TopologyBuilder.java"
    "src/main/java/dk/teamr3/enet/WebDashboard.java"
)

# Loop igennem filerne og tilføj dem
for FILE in "${FILES[@]}"; do
    if [ -f "$FILE" ]; then
        echo "Tilføjer $FILE..."
        echo "" >> "$OUTPUT_FILE"
        echo "--- BEGIN: $FILE ---" >> "$OUTPUT_FILE"
        cat "$FILE" >> "$OUTPUT_FILE"
        echo "" >> "$OUTPUT_FILE"
        echo "--- END: $FILE ---" >> "$OUTPUT_FILE"
        echo "" >> "$OUTPUT_FILE"
    else
        echo "ADVARSEL: Kunne ikke finde $FILE. Sørg for at du står i roden af dit Maven-projekt."
    fi
done

echo "Færdig! Filen er klar."