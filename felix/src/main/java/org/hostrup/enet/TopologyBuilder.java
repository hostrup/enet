package org.hostrup.enet;

import com.insta.instanet.instanetbox.simplecontrol.Endpoint;
import com.insta.instanet.instanetbox.simplecontrol.EndpointStateLevel;
import com.insta.instanet.instanetbox.simplecontrol.EndpointStatePower;
import com.insta.instanet.instanetbox.simplecontrol.ISimpleControl;
import com.insta.instanet.instanetbox.simplecontrol.Location;
import com.insta.instanet.instanetbox.simplecontrol.Scene;
import com.insta.instanet.instanetbox.simplecontrol.ScSceneType;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Maps the physical and logical structure (locations, devices, endpoints, scenes)
 * of the JUNG eNet system and generates corresponding Home Assistant MQTT Discovery topics
 * and config payloads. It maintains lookup indexes to route incoming and outgoing MQTT messages.
 */
public class TopologyBuilder {
    private static final String HA_PREFIX = "homeassistant";
    private final MqttActivator core;
    
    /**
     * Map translating Endpoint UIDs to MQTT base state/set topics.
     */
    public volatile Map<String, String> uidToTopic = new ConcurrentHashMap<>();
    
    /**
     * Reverse map translating MQTT base topics to Endpoint UIDs.
     */
    public volatile Map<String, String> topicToUid = new ConcurrentHashMap<>();
    
    /**
     * Map translating Location UIDs to human-readable names.
     */
    public volatile Map<String, String> locationMap = new ConcurrentHashMap<>();
    
    /**
     * Registry mapping device UIDs to maps of metadata parameters (e.g. name, model, serial).
     */
    public volatile Map<String, Map<String, String>> deviceRegistryMeta = new ConcurrentHashMap<>();
    
    /**
     * Quick lookups from endpoint UID to human-readable name.
     */
    public volatile Map<String, String> uidToName = new ConcurrentHashMap<>(); 
    
    /**
     * Thread-safe list of dashboard data maps presented in the local Web Dashboard table.
     */
    public volatile List<Map<String, String>> dashboardItems = new CopyOnWriteArrayList<>();
    
    /**
     * Set containing UIDs of dimmable endpoints, used to decide whether to publish brightness configs.
     */
    public volatile java.util.Set<String> dimmableUids = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    
    /**
     * Maps low-level event Value UIDs to their parent user-facing Endpoint UIDs.
     */
    public volatile Map<String, String> valueUidToEndpointUid = new ConcurrentHashMap<>();

    /**
     * Constructs a TopologyBuilder instance.
     * 
     * @param core Reference to the main MqttActivator coordinator.
     */
    public TopologyBuilder(MqttActivator core) {
        this.core = core;
    }

    private List<Object> toList(Object obj) {
        List<Object> list = new ArrayList<>();
        if (obj == null) return list;
        if (obj instanceof Iterable) {
            for (Object o : (Iterable<?>) obj) list.add(o);
        } else if (obj instanceof Object[]) {
            for (Object o : (Object[]) obj) list.add(o);
        }
        return list;
    }

    /**
     * Rebuilds the internal topology tables and publishes discovery parameters for Home Assistant.
     * Reflectively pulls locations, devices, values, and endpoints from the eNet core OSGi service registry.
     */
    public void buildTopologyMap() {
        try {
            core.addLog("Booting: Mapping Static Topography...");

            Map<String, String> localUidToTopic = new ConcurrentHashMap<>();
            Map<String, String> localTopicToUid = new ConcurrentHashMap<>();
            Map<String, String> localLocationMap = new ConcurrentHashMap<>();
            Map<String, Map<String, String>> localDeviceRegistryMeta = new ConcurrentHashMap<>();
            Map<String, String> localUidToName = new ConcurrentHashMap<>();
            List<Map<String, String>> localDashboardItems = new CopyOnWriteArrayList<>();
            java.util.Set<String> localDimmableUids = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
            Map<String, String> localValueUidToEndpointUid = new ConcurrentHashMap<>();

            ISimpleControl simpleControl = core.getSimpleControl();
            // Retrieve low-level eNet OSGi domain managers dynamically using ServiceReferences
            Object middleware = core.getServiceByFilter("com.insta.instanet.instanetbox.middleware.IMiddleware");
            Object deviceManager = core.getServiceByFilter("com.insta.instanet.instanetbox.systemfunctions.devicemanagement.IDeviceManager");

            // Map Locations (Rooms)
            for (Object o : toList(simpleControl.getLocations())) {
                Location l = (Location) o;
                if (l != null && l.getUID() != null) localLocationMap.put(l.getUID(), l.getName() != null ? l.getName() : "Unassigned");
            }

            publishMasterController();

            if (deviceManager != null) {
                for (Object dev : toList(core.safeInvoke(deviceManager, "getDevices"))) {
                    try {
                        String dUid = (String) core.safeInvoke(dev, "getUID");
                        if (dUid == null) continue;

                        Object devTypeObj = core.safeInvoke(dev, "getDeviceType");
                        String modelId = devTypeObj != null ? (String) core.safeInvoke(devTypeObj, "getDeviceTypeID") : "Unknown Model";
                        
                        Object metaObj = core.safeInvoke(dev, "getMetaData");
                        if (metaObj == null) metaObj = core.safeInvoke(dev, "getDeviceMetaData");

                        String hexSerial = ""; 
                        if (metaObj != null) {
                            hexSerial = (String) core.safeInvoke(metaObj, "getSerialNumberHexFormat");
                            if (hexSerial == null || hexSerial.isEmpty()) {
                                Object rawSerial = core.safeInvoke(metaObj, "getSerialNumber");
                                hexSerial = rawSerial != null ? String.valueOf(rawSerial) : dUid;
                            }
                        } else {
                            hexSerial = dUid;
                        }

                        String devName = "Unknown Device";
                        if (middleware != null) {
                            String areaName = (String) core.safeInvokeWithArgs(middleware, "getInstallationAreaFromDevice", new Class<?>[]{String.class}, new Object[]{dUid});
                            if (areaName != null && !areaName.trim().isEmpty()) devName = areaName;
                        }

                        if ("Unknown Device".equals(devName)) {
                            try {
                                Object metaData = dev.getClass().getMethod("getDeviceMetaData").invoke(dev);
                                if (metaData != null) {
                                    try {
                                        String desig = (String) metaData.getClass().getMethod("getDesignation").invoke(metaData);
                                        if (desig != null && !desig.trim().isEmpty()) devName = desig;
                                    } catch (Exception ignore1) { }
                                    
                                    if ("Unknown Device".equals(devName)) {
                                        try {
                                            String name = (String) metaData.getClass().getMethod("getName").invoke(metaData);
                                            if (name != null && !name.trim().isEmpty()) devName = name;
                                        } catch (Exception ignore2) { }
                                    }
                                }
                            } catch (Exception e) {}
                        }
                        if ("Unknown Device".equals(devName) && modelId != null) devName = "JUNG " + modelId;

                        Map<String, String> metaMap = new HashMap<>();
                        metaMap.put("name", devName);
                        metaMap.put("model", modelId);
                        metaMap.put("serial", hexSerial);
                        localDeviceRegistryMeta.put(dUid, metaMap);

                        boolean isTransmitter = modelId != null && (modelId.contains("WS") || modelId.contains("HS") || modelId.contains("Transmitter"));

                        for (Object ch : toList(core.safeInvoke(dev, "getDeviceChannels"))) {
                            Integer chNum = (Integer) core.safeInvoke(ch, "getNumber");
                            if (chNum == null) continue;
                            
                            String btnUid = hexSerial + "_ch" + chNum;
                            String endpointUID = dUid + "_" + chNum;
                            String chUid = (String) core.safeInvoke(ch, "getUID");
                            core.addLog("Mapping Dev: " + dUid + " Ch: " + chNum + " (UID: " + chUid + ")");
                            
                            try {
                                List<Object> funcs = toList(core.safeInvoke(ch, "getFunctions"));
                                core.addLog("  Found " + funcs.size() + " functions for Ch: " + chNum);
                                for (Object func : funcs) {
                                    String funcUid = (String) core.safeInvoke(func, "getUID");
                                    List<Object> vals = toList(core.safeInvoke(func, "getCurrentValues"));
                                    core.addLog("    Func: " + funcUid + " has " + vals.size() + " values");
                                    for (Object val : vals) {
                                        String valUid = (String) core.safeInvoke(val, "getUID");
                                        core.addLog("      Value UID: " + valUid + " -> Endpoint: " + endpointUID);
                                        if (valUid != null) {
                                            localValueUidToEndpointUid.put(valUid, endpointUID);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                core.addLog("Error mapping value UIDs: " + e.getMessage());
                            }

                            if (isTransmitter) {
                                if (chNum == 1) { 
                                    String batTopic = "enet/sensor/battery/" + hexSerial;
                                    String batConf = "{\"~\":\"" + batTopic + "\",\"name\":\"Battery Low\",\"unique_id\":\"enet_bat_" + hexSerial + "\",\"state_topic\":\"~/state\",\"value_template\":\"{{ value_json.state }}\",\"device_class\":\"battery\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"device\":{\"identifiers\":[\"enet_" + hexSerial + "\"],\"name\":\"" + escapeJson(devName) + "\",\"manufacturer\":\"JUNG\",\"model\":\"" + escapeJson(modelId) + "\",\"via_device\":\"enet_controller\"}}";
                                    core.getMqttManager().publish(HA_PREFIX + "/binary_sensor/enet_" + hexSerial + "_battery/config", batConf, 0, true);
                                    
                                    try {
                                        Object batEnum = core.safeInvoke(dev, "getBatteryState");
                                        String batStr = batEnum != null ? batEnum.toString() : "NORMAL";
                                        String batPayload = "{\"state\":\"" + ((batStr.contains("LOW") || batStr.contains("EMPTY")) ? "ON" : "OFF") + "\"}";
                                        core.getMqttManager().publish(batTopic + "/state", batPayload, 0, true);
                                    } catch (Exception ignore) {}
                                }

                                if (chNum >= 1 && chNum <= 8) {
                                    String btnName = devName + " Knap " + chNum;
                                    try {
                                        Object chMeta = core.safeInvoke(ch, "getMetaData");
                                        if (chMeta != null) {
                                            String cName = (String) core.safeInvoke(chMeta, "getName");
                                            if (cName != null && !cName.trim().isEmpty()) btnName = cName;
                                        }
                                    } catch (Exception ignore) {}
                                    
                                    String baseTopic = "enet/button/" + btnUid;
                                    localUidToTopic.put(btnUid, baseTopic);
                                    localTopicToUid.put(baseTopic, btnUid);
                                    localUidToName.put(btnUid, btnName); 
                                    
                                    // 1. CLEAN UP: Unpublish the old sensor config
                                     String oldSensorConfigTopic = HA_PREFIX + "/sensor/enet_" + btnUid + "/config";
                                     core.getMqttManager().publish(oldSensorConfigTopic, "", 0, true);
                                     
                                     // 2. PUBLISH DEVICE TRIGGERS:
                                     String devId = "enet_" + hexSerial;
                                     String devNameJson = escapeJson(devName);
                                     String modelIdJson = escapeJson(modelId);
                                     
                                     String deviceBlock = "{\"identifiers\":[\"" + devId + "\"],\"name\":\"" + devNameJson + "\",\"manufacturer\":\"JUNG\",\"model\":\"" + modelIdJson + "\",\"via_device\":\"enet_controller\"}";
                                     
                                     // A. Initial Press Trigger
                                     String triggerPressTopic = HA_PREFIX + "/device_trigger/" + devId + "/btn_" + chNum + "_press/config";
                                     String triggerPressConf = "{\"automation_type\":\"trigger\",\"topic\":\"" + baseTopic + "/state\",\"payload\":\"INITIAL_PRESS\",\"type\":\"button_press\",\"subtype\":\"button_" + chNum + "\",\"device\":" + deviceBlock + "}";
                                     core.getMqttManager().publish(triggerPressTopic, triggerPressConf, 0, true);
                                     
                                     // B. Short Release Trigger
                                     String triggerShortTopic = HA_PREFIX + "/device_trigger/" + devId + "/btn_" + chNum + "_short/config";
                                     String triggerShortConf = "{\"automation_type\":\"trigger\",\"topic\":\"" + baseTopic + "/state\",\"payload\":\"SHORT_RELEASE\",\"type\":\"button_short_press\",\"subtype\":\"button_" + chNum + "\",\"device\":" + deviceBlock + "}";
                                     core.getMqttManager().publish(triggerShortTopic, triggerShortConf, 0, true);
                                     
                                     // C. Long Release Trigger
                                     String triggerLongTopic = HA_PREFIX + "/device_trigger/" + devId + "/btn_" + chNum + "_long/config";
                                     String triggerLongConf = "{\"automation_type\":\"trigger\",\"topic\":\"" + baseTopic + "/state\",\"payload\":\"LONG_RELEASE\",\"type\":\"button_long_press\",\"subtype\":\"button_" + chNum + "\",\"device\":" + deviceBlock + "}";
                                     core.getMqttManager().publish(triggerLongTopic, triggerLongConf, 0, true);
                                     
                                     // Keep publishing the initial state to the raw button topic so our local dashboard can still monitor it.
                                     core.getMqttManager().publish(baseTopic + "/state", "IDLE", 0, true);
                                }
                            }
                        }
                    } catch (Exception e) {
                        core.addLog("Error mapping device " + dev + ": " + e.getMessage());
                    }
                }
            }

            for (Object o : toList(simpleControl.getEndpoints())) {
                Endpoint e = (Endpoint) o;
                if (e == null || e.getUID() == null || e.getType() == null) continue;
                String uid = e.getUID();
                String type = e.getType().name();
                String domain = "unknown";
                boolean isDimmable = "DIMMER".equals(type);
                if ("LIGHT".equals(type) || isDimmable) {
                    domain = "light";
                    if (isDimmable) {
                        localDimmableUids.add(uid);
                    }
                }
                else if ("SWITCH".equals(type)) domain = "switch";
                else if ("BLINDS".equals(type) || "MARQUEE".equals(type)) domain = "cover";
                if ("unknown".equals(domain)) continue;

                String room = e.getLocationUID() != null ? localLocationMap.get(e.getLocationUID()) : "Unassigned";
                if (room == null) room = "Unassigned";
                String baseDUid = uid.contains("_") ? uid.substring(0, uid.indexOf("_")) : uid;
                String baseTopic = "enet/" + domain + "/" + uid;
                
                String eName = e.getName() != null ? e.getName() : "Unknown Endpoint";
                localUidToTopic.put(uid, baseTopic);
                localTopicToUid.put(baseTopic, uid);
                localUidToName.put(uid, eName); 
                
                Map<String, String> hwInfo = localDeviceRegistryMeta.get(baseDUid);
                if (hwInfo != null) hwInfo.put("identifiers", "enet_" + baseDUid);
                
                publishHADiscovery(domain, uid, baseTopic, eName, hwInfo, room, isDimmable);
                publishInitialState(e, baseTopic);
                
                Map<String, String> dash = new HashMap<>();
                dash.put("uid", uid); dash.put("name", eName); dash.put("room", room);
                dash.put("domain", domain); dash.put("topic", baseTopic);
                localDashboardItems.add(dash);
            }

            // Publish USER scenes
            for (Object o : toList(simpleControl.getScenes())) {
                Scene sc = (Scene) o;
                if (sc == null || sc.getUID() == null || sc.getType() == null) continue;
                
                if (sc.getType() == ScSceneType.USER) {
                    String sUid = sc.getUID();
                    String sName = sc.getName() != null ? sc.getName() : "Scene " + sUid;
                    String room = sc.getLocationUID() != null ? localLocationMap.get(sc.getLocationUID()) : "Unassigned";
                    if (room == null) room = "Unassigned";
                    
                    String baseTopic = "enet/scene/" + sUid;
                    localUidToTopic.put(sUid, baseTopic);
                    localTopicToUid.put(baseTopic, sUid);
                    localUidToName.put(sUid, sName);

                    String configTopic = HA_PREFIX + "/scene/enet_scene_" + sUid + "/config";
                    StringBuilder json = new StringBuilder("{")
                        .append("\"~\":\"").append(baseTopic).append("\",")
                        .append("\"name\":\"").append(escapeJson(sName)).append("\",")
                        .append("\"unique_id\":\"enet_scene_").append(sUid).append("\",");
                    
                    if (!"Unassigned".equals(room)) {
                        json.append("\"suggested_area\":\"").append(escapeJson(room)).append("\",");
                    }
                    
                    json.append("\"command_topic\":\"~/set\",")
                        .append("\"payload_on\":\"ON\"")
                        .append("}");
                    
                    core.getMqttManager().publish(configTopic, json.toString(), 0, true);
                    core.addLog("Discovered USER scene: " + sName + " (UID: " + sUid + ") in " + room);
                }
            }

            // Atomically swap references
            this.uidToTopic = localUidToTopic;
            this.topicToUid = localTopicToUid;
            this.locationMap = localLocationMap;
            this.deviceRegistryMeta = localDeviceRegistryMeta;
            this.uidToName = localUidToName;
            this.dashboardItems = localDashboardItems;
            this.dimmableUids = localDimmableUids;
            this.valueUidToEndpointUid = localValueUidToEndpointUid;

            core.addLog("Topology Mapping Complete! System is optimized and fully mapped.");
        } catch (Exception e) { 
            StringWriter sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw));
            core.addLog("Topology Error: " + sw.toString()); 
        }
    }

    private void publishHADiscovery(String domain, String uid, String baseTopic, String entityName, Map<String, String> meta, String entityRoom, boolean isDimmable) {
        String configTopic = HA_PREFIX + "/" + domain + "/enet_" + uid + "/config";
        StringBuilder json = new StringBuilder("{").append("\"~\":\"").append(baseTopic).append("\",")
            .append("\"name\":\"").append(escapeJson(entityName)).append("\",").append("\"unique_id\":\"enet_").append(uid).append("\",");
        
        if (!"Unassigned".equals(entityRoom)) json.append("\"suggested_area\":\"").append(escapeJson(entityRoom)).append("\",");
        
        if (meta != null) {
            String ids = "enet_dev_" + uid;
            json.append("\"device\":{\"identifiers\":[\"").append(ids).append("\"],\"manufacturer\":\"JUNG\",\"via_device\":\"enet_controller\",")
                .append("\"name\":\"").append(escapeJson(entityName)).append("\",")
                .append("\"model\":\"").append(escapeJson(meta.get("model"))).append(" Channel\"");
            if (!"Unassigned".equals(entityRoom)) {
                json.append(",\"suggested_area\":\"").append(escapeJson(entityRoom)).append("\"");
            }
            json.append("},");
        }

        json.append("\"state_topic\":\"~/state\",\"command_topic\":\"~/set\",\"schema\":\"json\"");
        if (baseTopic.contains("/light/") && isDimmable) {
            json.append(",\"brightness\":true,\"brightness_scale\":255");
        }
        if ("cover".equals(domain)) json.append(",\"position_topic\":\"~/state\",\"set_position_topic\":\"~/set\",\"position_template\":\"{{ value_json.position }}\",\"value_template\":\"{{ value_json.state }}\"");
        json.append("}");
        core.getMqttManager().publish(configTopic, json.toString(), 0, true);
    }

    private void publishInitialState(Endpoint ep, String baseTopic) {
        StringBuilder st = new StringBuilder("{\"state\":\"OFF\"}");
        if (ep.getState() != null) {
            for (Object sObj : ep.getState()) {
                if (sObj instanceof EndpointStatePower) {
                    st = new StringBuilder("{\"state\":\"").append(((EndpointStatePower) sObj).getValue().name()).append("\"}");
                } else if (sObj instanceof EndpointStateLevel) {
                    int pct = ((EndpointStateLevel) sObj).getValue();
                    st = new StringBuilder("{\"state\":\"").append(pct > 0 ? "ON" : "OFF").append("\",\"brightness\":").append(Math.round((pct / 100.0) * 255.0)).append("}");
                }
            }
        }
        core.getMqttManager().publish(baseTopic + "/state", st.toString(), 0, true);
    }

    private void publishMasterController() {
        String json = "{\"name\":\"eNet Server Status\",\"unique_id\":\"enet_controller_status\",\"state_topic\":\"enet/gateway/status\",\"device\":{\"identifiers\":[\"enet_controller\"],\"name\":\"JUNG eNet Server\",\"manufacturer\":\"JUNG\",\"model\":\"eNet Smart Home Box\"}}";
        core.getMqttManager().publish(HA_PREFIX + "/sensor/enet_controller_status/config", json, 0, true);
        core.getMqttManager().publish("enet/gateway/status", "Online", 0, true);
    }

    public String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}