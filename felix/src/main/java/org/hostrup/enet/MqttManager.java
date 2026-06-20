package org.hostrup.enet;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import com.insta.instanet.instanetbox.simplecontrol.EndpointStateLevel;
import com.insta.instanet.instanetbox.simplecontrol.EndpointStatePower;
import com.insta.instanet.instanetbox.simplecontrol.ISimpleControl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Manages the connection, subscription, publication, and message dispatching
 * for the Eclipse Paho MQTT Client, bridging the local eNet system to the MQTT Broker.
 */
public class MqttManager implements MqttCallback {
    private static final String CLIENT_ID = "eNet_Native_Gateway";
    private volatile MqttClient mqttClient;
    private final MqttActivator core;
    private final Object connectLock = new Object();

    /**
     * Constructs the MqttManager instance.
     * 
     * @param core Reference to the main MqttActivator coordinator.
     */
    public MqttManager(MqttActivator core) {
        this.core = core;
    }

    /**
     * Asynchronously connects to the MQTT broker using configured credentials and properties.
     * If the client is already connected, it will disconnect first before reconnecting.
     */
    public void connectMqtt() {
        core.getExecutor().submit(() -> {
            synchronized (connectLock) {
                try {
                    if (mqttClient != null) {
                        try {
                            if (mqttClient.isConnected()) {
                                mqttClient.disconnect();
                            }
                        } catch (Exception ignored) {}
                        try {
                            mqttClient.close();
                        } catch (Exception ignored) {}
                    }
                    ConfigManager cfg = core.getConfigManager();
                    if (cfg.getMqttBroker().isEmpty()) return;

                    mqttClient = new MqttClient(cfg.getMqttBroker(), CLIENT_ID, new MemoryPersistence());
                    MqttConnectOptions o = new MqttConnectOptions();
                    o.setCleanSession(true); 
                    o.setAutomaticReconnect(true); 
                    o.setConnectionTimeout(10);
                    if (!cfg.getMqttUser().isEmpty()) o.setUserName(cfg.getMqttUser());
                    if (!cfg.getMqttPass().isEmpty()) o.setPassword(cfg.getMqttPass().toCharArray());
                    
                    mqttClient.setCallback(this);
                    mqttClient.connect(o);
                    core.addLog("MQTT Connected successfully!");
                    
                    // Subscribe to all eNet-related commands
                    mqttClient.subscribe("enet/#");
                } catch (Exception e) { 
                    core.addLog("MQTT Error: " + e.getMessage()); 
                }
            }
        });
    }

    /**
     * Gracefully disconnects the MQTT client if connected.
     */
    public void disconnect() {
        synchronized (connectLock) {
            try {
                if (mqttClient != null) {
                    if (mqttClient.isConnected()) {
                        mqttClient.disconnect();
                    }
                    mqttClient.close();
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Publishes a message payload to a target topic.
     * 
     * @param topic Target MQTT topic.
     * @param payload String payload value.
     * @param qos Quality of Service (0, 1, or 2).
     * @param retained Whether to persist the message on the broker.
     */
    public void publish(String topic, String payload, int qos, boolean retained) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try { mqttClient.publish(topic, payload.getBytes("UTF-8"), qos, retained); } catch (Exception ignored) {}
        }
    }

    /**
     * Checks if the MQTT client is currently connected.
     * 
     * @return True if connected, false otherwise.
     */
    public boolean isConnected() { return mqttClient != null && mqttClient.isConnected(); }

    /**
     * Triggered when a message arrives from the MQTT Broker.
     * Parses commands (e.g. state level, brightness, covers) and routes them to ISimpleControl.
     */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        core.getExecutor().submit(() -> {
            try {
                // We only handle setting commands ending with "/set"
                if (topic == null || !topic.endsWith("/set")) return;
                
                String baseTopic = topic.substring(0, topic.length() - 4);
                String rawPayload = new String(message.getPayload(), "UTF-8").trim();

                // Skip button topics in egress commands
                if (topic.contains("/button/")) {
                    return;
                }

                // Handle Scene Activation commands
                if (topic.contains("/scene/")) {
                    String uid = core.getTopologyBuilder().topicToUid.get(baseTopic);
                    if (uid != null) {
                        ISimpleControl sc = core.getSimpleControl();
                        if (sc != null) {
                            if (core.debugMode) core.addLog("DEBUG EGRESS: Activating Scene: " + uid);
                            sc.handleSceneRequest(uid);
                        }
                    }
                    return;
                }

                // Resolve target Device UID from topic
                String uid = core.getTopologyBuilder().topicToUid.get(baseTopic);
                if (uid == null) return;
                String eName = core.getTopologyBuilder().uidToName.getOrDefault(uid, "Unknown Device");

                if (core.debugMode) core.addLog("DEBUG EGRESS: [" + eName + "] (UID: " + uid + ") Received MQTT Command: " + rawPayload);

                ISimpleControl sc = core.getSimpleControl();
                if (sc == null) return;

                // Parse brightness, position and state with Gson
                boolean stateOn = false;
                boolean stateOff = false;
                boolean hasState = false;
                int positionVal = -1;
                int haBright = -1;
                boolean hasBrightness = false;

                try {
                    JsonElement jsonElement = new JsonParser().parse(rawPayload);
                    if (jsonElement.isJsonObject()) {
                        JsonObject jsonObj = jsonElement.getAsJsonObject();
                        if (jsonObj.has("state")) {
                            String st = jsonObj.get("state").getAsString();
                            hasState = true;
                            if ("ON".equalsIgnoreCase(st) || "open".equalsIgnoreCase(st)) {
                                stateOn = true;
                            } else if ("OFF".equalsIgnoreCase(st) || "closed".equalsIgnoreCase(st) || "close".equalsIgnoreCase(st)) {
                                stateOff = true;
                            }
                        }
                        if (jsonObj.has("brightness")) {
                            haBright = jsonObj.get("brightness").getAsInt();
                            hasBrightness = true;
                        }
                        if (jsonObj.has("position")) {
                            positionVal = jsonObj.get("position").getAsInt();
                        }
                    } else if (jsonElement.isJsonPrimitive()) {
                        String st = jsonElement.getAsString();
                        if ("ON".equalsIgnoreCase(st) || "open".equalsIgnoreCase(st)) {
                            stateOn = true;
                            hasState = true;
                        } else if ("OFF".equalsIgnoreCase(st) || "closed".equalsIgnoreCase(st) || "close".equalsIgnoreCase(st)) {
                            stateOff = true;
                            hasState = true;
                        } else {
                            try {
                                positionVal = Integer.parseInt(st);
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    // Fallback to basic string parsing
                    String clean = rawPayload.replaceAll("[\"\\s{}]", "");
                    if (clean.equals("ON") || clean.equalsIgnoreCase("open")) {
                        stateOn = true;
                        hasState = true;
                    } else if (clean.equals("OFF") || clean.equalsIgnoreCase("close") || clean.equalsIgnoreCase("closed")) {
                        stateOff = true;
                        hasState = true;
                    } else {
                        try {
                            positionVal = Integer.parseInt(clean);
                        } catch (Exception ignored) {}
                    }
                }

                boolean isDimmable = core.getTopologyBuilder().dimmableUids.contains(uid);
                boolean isCover = baseTopic.contains("/cover/");

                if (isCover) {
                    if (positionVal >= 0 && positionVal <= 100) {
                        EndpointStateLevel level = new EndpointStateLevel(); 
                        level.setValue(positionVal);
                        sc.handleControlRequest(uid, level, "hostrup_mqtt");
                        
                        String coverState = (positionVal > 0) ? "open" : "closed";
                        String stateJson = "{\"state\":\"" + coverState + "\",\"position\":" + positionVal + "}";
                        if (core.debugMode) core.addLog("DEBUG EGRESS: [" + eName + "] Publishing immediate cover position: " + stateJson);
                        publish(baseTopic + "/state", stateJson, 0, true);
                    } else if (hasState && (stateOn || stateOff)) {
                        EndpointStatePower p = new EndpointStatePower();
                        p.setValue(stateOn ? EndpointStatePower.Value.ON : EndpointStatePower.Value.OFF);
                        sc.handleControlRequest(uid, p, "hostrup_mqtt");
                        
                        String stateVal = stateOn ? "open" : "closed";
                        String stateJson = "{\"state\":\"" + stateVal + "\"}";
                        if (core.debugMode) core.addLog("DEBUG EGRESS: [" + eName + "] Publishing immediate cover state: " + stateJson);
                        publish(baseTopic + "/state", stateJson, 0, true);
                    }
                } else if (hasBrightness && isDimmable && haBright >= 0) {
                    // Dimmable device: Setting level automatically turns it ON at that level.
                    // We only send the EndpointStateLevel command to minimize KNX-RF bus traffic.
                    int enetPct = (int) Math.round((haBright / 255.0) * 100.0);
                    EndpointStateLevel level = new EndpointStateLevel(); 
                    level.setValue(enetPct);
                    sc.handleControlRequest(uid, level, "hostrup_mqtt");
                    
                    String stateJson = "{\"state\":\"ON\",\"brightness\":" + haBright + ",\"position\":" + enetPct + "}";
                    if (core.debugMode) core.addLog("DEBUG EGRESS: [" + eName + "] Publishing immediate dim-state: " + stateJson);
                    publish(baseTopic + "/state", stateJson, 0, true);
                } else if (hasState && (stateOn || stateOff)) {
                    // Standard ON/OFF or non-dimmable light fallback.
                    EndpointStatePower p = new EndpointStatePower();
                    p.setValue(stateOn ? EndpointStatePower.Value.ON : EndpointStatePower.Value.OFF);
                    sc.handleControlRequest(uid, p, "hostrup_mqtt");
                    
                    String stateJson = "{\"state\":\"" + (stateOn ? "ON" : "OFF") + "\"}";
                    if (core.debugMode) core.addLog("DEBUG EGRESS: [" + eName + "] Publishing immediate state: " + stateJson);
                    publish(baseTopic + "/state", stateJson, 0, true);
                }
            } catch (Exception e) { 
                if (core.debugMode) core.addLog("DEBUG ERROR in Egress: " + e.toString()); 
            }
        });
    }

    @Override public void connectionLost(Throwable cause) { core.addLog("MQTT Connection Lost. Auto-reconnecting..."); }
    @Override public void deliveryComplete(IMqttDeliveryToken token) {}
}