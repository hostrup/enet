package dk.teamr3.enet;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

public class ConfigManager {
    private static final String CONFIG_FILE = "/home/insta/felix-framework/conf/mqtt-gateway.properties";
    private String mqttBroker = "";
    private String mqttUser = "";
    private String mqttPass = "";
    private final MqttActivator core;

    public ConfigManager(MqttActivator core) {
        this.core = core;
        loadConfig();
    }

    private void loadConfig() {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_FILE)) {
            p.load(in);
            mqttBroker = p.getProperty("MQTT_BROKER", "");
            mqttUser = p.getProperty("MQTT_USER", "");
            mqttPass = p.getProperty("MQTT_PASS", "");
        } catch (Exception ignored) {}
    }

    public synchronized void saveConfig(String broker, String user, String pass) {
        this.mqttBroker = broker != null ? broker : "";
        this.mqttUser = user != null ? user : "";
        this.mqttPass = pass != null ? pass : "";
        Properties props = new Properties();
        props.setProperty("MQTT_BROKER", this.mqttBroker);
        props.setProperty("MQTT_USER", this.mqttUser);
        props.setProperty("MQTT_PASS", this.mqttPass);
        try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
            props.store(out, "eNet Configuration");
            core.addLog("Config saved. Reconnecting MQTT...");
            core.getMqttManager().connectMqtt();
        } catch (Exception e) {
            core.addLog("Error saving config: " + e.getMessage());
        }
    }

    public String getMqttBroker() { return mqttBroker; }
    public String getMqttUser() { return mqttUser; }
    public String getMqttPass() { return mqttPass; }
}