package dk.teamr3.enet;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import com.insta.instanet.instanetbox.simplecontrol.EndpointStateLevel;
import com.insta.instanet.instanetbox.simplecontrol.EndpointStatePower;
import com.insta.instanet.instanetbox.simplecontrol.ISimpleControl;

public class MqttManager implements MqttCallback {
    private static final String CLIENT_ID = "eNet_Native_Gateway";
    private MqttClient mqttClient;
    private final MqttActivator core;

    public MqttManager(MqttActivator core) {
        this.core = core;
    }

    public void connectMqtt() {
        core.getExecutor().submit(() -> {
            try {
                if (mqttClient != null && mqttClient.isConnected()) mqttClient.disconnect();
                ConfigManager cfg = core.getConfigManager();
                if (cfg.getMqttBroker().isEmpty()) return;

                mqttClient = new MqttClient(cfg.getMqttBroker(), CLIENT_ID, new MemoryPersistence());
                MqttConnectOptions o = new MqttConnectOptions();
                o.setCleanSession(true); o.setAutomaticReconnect(true); o.setConnectionTimeout(10);
                if (!cfg.getMqttUser().isEmpty()) o.setUserName(cfg.getMqttUser());
                if (!cfg.getMqttPass().isEmpty()) o.setPassword(cfg.getMqttPass().toCharArray());
                
                mqttClient.setCallback(this);
                mqttClient.connect(o);
                core.addLog("MQTT Connected successfully!");
                
                mqttClient.subscribe("enet/#");
            } catch (Exception e) { 
                core.addLog("MQTT Error: " + e.getMessage()); 
            }
        });
    }

    public void disconnect() {
        try { if (mqttClient != null && mqttClient.isConnected()) mqttClient.disconnect(); } catch (Exception ignored) {}
    }

    public void publish(String topic, String payload, int qos, boolean retained) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try { mqttClient.publish(topic, payload.getBytes("UTF-8"), qos, retained); } catch (Exception ignored) {}
        }
    }

    public boolean isConnected() { return mqttClient != null && mqttClient.isConnected(); }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        core.getExecutor().submit(() -> {
            try {
                if (topic == null || !topic.endsWith("/set")) return;
                
                String baseTopic = topic.substring(0, topic.length() - 4);
                String rawPayload = new String(message.getPayload(), "UTF-8");
                String clean = rawPayload.replaceAll("[\"\\s{}]", "");

                if (topic.contains("/button/")) {
                    return;
                }

                String uid = core.getTopologyBuilder().topicToUid.get(baseTopic);
                if (uid == null) return;
                String eName = core.getTopologyBuilder().uidToName.getOrDefault(uid, "Ukendt Enhed");

                if (core.debugMode) core.addLog("DEBUG EGRESS: [" + eName + "] (UID: " + uid + ") Modtog MQTT Kommando: " + clean);

                ISimpleControl sc = core.getSimpleControl();
                if (sc == null) return;

                boolean stateOn = clean.contains("state:ON") || clean.equals("ON") || clean.contains("state:open");
                boolean stateOff = clean.contains("state:OFF") || clean.equals("OFF") || clean.contains("state:close");
                
                if (stateOn || stateOff) {
                    EndpointStatePower p = new EndpointStatePower();
                    p.setValue(stateOn ? EndpointStatePower.Value.ON : EndpointStatePower.Value.OFF);
                    sc.handleControlRequest(uid, p, "teamr3_mqtt");
                    
                    String stateJson = "{\"state\":\"" + (stateOn ? "ON".equals(p.getValue().name()) ? "open" : "closed" : p.getValue().name()) + "\"}";
                    if (core.debugMode) core.addLog("DEBUG EGRESS: [" + eName + "] Publicerer straks status: " + stateJson);
                    publish(baseTopic + "/state", stateJson, 0, true);
                }

                if (clean.contains("brightness:")) {
                    int bIdx = clean.indexOf("brightness:") + 11;
                    int bEndIdx = clean.indexOf(",", bIdx); 
                    if (bEndIdx == -1) bEndIdx = clean.length();
                    if (bEndIdx > bIdx) {
                        int haBright = Integer.parseInt(clean.substring(bIdx, bEndIdx));
                        int enetPct = (int) Math.round((haBright / 255.0) * 100.0);
                        EndpointStateLevel level = new EndpointStateLevel(); 
                        level.setValue(enetPct);
                        sc.handleControlRequest(uid, level, "teamr3_mqtt");
                        
                        String stateJson = "{\"state\":\"ON\",\"brightness\":" + haBright + ",\"position\":" + enetPct + "}";
                        if (core.debugMode) core.addLog("DEBUG EGRESS: [" + eName + "] Publicerer straks dæmp-status: " + stateJson);
                        publish(baseTopic + "/state", stateJson, 0, true);
                    }
                }
            } catch (Exception e) { 
                if (core.debugMode) core.addLog("DEBUG ERROR in Egress: " + e.toString()); 
            }
        });
    }

    @Override public void connectionLost(Throwable cause) { core.addLog("MQTT Connection Lost. Auto-reconnecting..."); }
    @Override public void deliveryComplete(IMqttDeliveryToken token) {}
}