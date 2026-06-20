package org.hostrup.enet;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Handles loading and saving the gateway configuration from/to properties file.
 * This class is responsible for reading the MQTT broker credentials and endpoint settings
 * stored on the embedded eNet server's filesystem, and writing user updates back to it.
 */
public class ConfigManager {
    /**
     * Absolute path on the eNet Server filesystem where the MQTT gateway properties are persisted.
     */
    private static final String CONFIG_FILE = "/home/insta/felix-framework/conf/mqtt-gateway.properties";
    
    private String mqttBroker = "";
    private String mqttUser = "";
    private String mqttPass = "";
    private final MqttActivator core;

    /**
     * Constructs a ConfigManager instance, loading persisted settings from disk immediately.
     * 
     * @param core Reference to the main MqttActivator coordinator for logging and event dispatch.
     */
    public ConfigManager(MqttActivator core) {
        this.core = core;
        loadConfig();
    }

    /**
     * Loads the MQTT configuration properties from the designated properties file.
     * If the properties file does not exist (e.g. during first-time startup), default
     * empty values are maintained.
     */
    private void loadConfig() {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_FILE)) {
            p.load(in);
            mqttBroker = p.getProperty("MQTT_BROKER", "");
            mqttUser = p.getProperty("MQTT_USER", "");
            mqttPass = p.getProperty("MQTT_PASS", "");
        } catch (Exception ignored) {
            // Properties file might not exist yet, which is fine and expected on initial run
        }
    }

    /**
     * Saves the MQTT configuration to disk and triggers an asynchronous MQTT reconnection.
     * 
     * @param broker The target MQTT Broker URL (e.g., tcp://10.0.0.2:1883).
     * @param user The username for MQTT authentication.
     * @param pass The password for MQTT authentication.
     */
    public synchronized void saveConfig(String broker, String user, String pass) {
        String testBroker = broker != null ? broker : "";
        String testUser = user != null ? user : "";
        String testPass = pass != null ? pass : "";

        // Validate broker URI format
        if (!testBroker.isEmpty()) {
            if (!testBroker.startsWith("tcp://") && !testBroker.startsWith("ssl://")) {
                core.addLog("Error: Broker URI must start with tcp:// or ssl://");
                return;
            }
            try {
                java.net.URI uri = new java.net.URI(testBroker);
                if (uri.getHost() == null) {
                    core.addLog("Error: Invalid Broker hostname/IP.");
                    return;
                }
            } catch (Exception e) {
                core.addLog("Error: Invalid Broker URI format: " + e.getMessage());
                return;
            }
        }

        Properties props = new Properties();
        props.setProperty("MQTT_BROKER", testBroker);
        props.setProperty("MQTT_USER", testUser);
        props.setProperty("MQTT_PASS", testPass);
        
        try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
            props.store(out, "eNet Configuration");
            
            // Only update fields on successful save
            this.mqttBroker = testBroker;
            this.mqttUser = testUser;
            this.mqttPass = testPass;
            
            core.addLog("Config saved. Reconnecting MQTT...");
            // Force the MQTT manager to re-establish connection using new configuration details
            core.getMqttManager().connectMqtt();
        } catch (Exception e) {
            core.addLog("Error saving config: " + e.getMessage());
        }
    }

    // Getters for the configured properties
    public String getMqttBroker() { return mqttBroker; }
    public String getMqttUser() { return mqttUser; }
    public String getMqttPass() { return mqttPass; }
}