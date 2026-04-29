package dk.teamr3.enet;

import com.insta.instanet.instanetbox.simplecontrol.Endpoint;
import com.insta.instanet.instanetbox.simplecontrol.EndpointStateLevel;
import com.insta.instanet.instanetbox.simplecontrol.EndpointStatePower;
import com.insta.instanet.instanetbox.simplecontrol.ISimpleControl;
import com.insta.instanet.instanetbox.simplecontrol.Location;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class TopologyBuilder {
    private static final String HA_PREFIX = "homeassistant";
    private final MqttActivator core;
    
    public final Map<String, String> uidToTopic = new ConcurrentHashMap<>();
    public final Map<String, String> topicToUid = new ConcurrentHashMap<>();
    public final Map<String, String> locationMap = new ConcurrentHashMap<>();
    public final Map<String, Map<String, String>> deviceRegistryMeta = new ConcurrentHashMap<>();
    public final Map<String, String> uidToName = new ConcurrentHashMap<>(); 
    public final List<Map<String, String>> dashboardItems = new CopyOnWriteArrayList<>();

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

    public void buildTopologyMap() {
        try {
            core.addLog("Booting: Mapping Static Topography...");
            uidToTopic.clear(); topicToUid.clear(); locationMap.clear(); 
            deviceRegistryMeta.clear(); uidToName.clear(); dashboardItems.clear();

            ISimpleControl simpleControl = core.getSimpleControl();
            Object middleware = core.getServiceByFilter("com.insta.instanet.instanetbox.middleware.IMiddleware");
            Object deviceManager = core.getServiceByFilter("com.insta.instanet.instanetbox.systemfunctions.devicemanagement.IDeviceManager");

            for (Object o : toList(simpleControl.getLocations())) {
                Location l = (Location) o;
                if (l != null && l.getUID() != null) locationMap.put(l.getUID(), l.getName() != null ? l.getName() : "Unassigned");
            }

            publishMasterController();

            if (deviceManager != null) {
                for (Object dev : toList(core.safeInvoke(deviceManager, "getDevices"))) {
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
                    deviceRegistryMeta.put(dUid, metaMap);

                    boolean isTransmitter = modelId != null && (modelId.contains("WS") || modelId.contains("HS") || modelId.contains("Transmitter"));

                    for (Object ch : toList(core.safeInvoke(dev, "getDeviceChannels"))) {
                        Integer chNum = (Integer) core.safeInvoke(ch, "getNumber");
                        if (chNum == null) continue;
                        
                        String btnUid = hexSerial + "_ch" + chNum;

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
                                uidToTopic.put(btnUid, baseTopic);
                                topicToUid.put(baseTopic, btnUid);
                                uidToName.put(btnUid, btnName); 
                                
                                String btnConf = "{\"~\":\"" + baseTopic + "\",\"name\":\"" + escapeJson(btnName) + "\",\"unique_id\":\"enet_btn_" + btnUid + "\",\"state_topic\":\"~/state\",\"icon\":\"mdi:gesture-tap-button\",\"device\":{\"identifiers\":[\"enet_" + hexSerial + "\"],\"name\":\"" + escapeJson(devName) + "\",\"manufacturer\":\"JUNG\",\"model\":\"" + escapeJson(modelId) + "\",\"via_device\":\"enet_controller\"}}";
                                core.getMqttManager().publish(HA_PREFIX + "/sensor/enet_" + btnUid + "/config", btnConf, 0, true);
                                
                                core.getMqttManager().publish(baseTopic + "/state", "IDLE", 0, true);
                            }
                        }
                    }
                }
            }

            for (Object o : toList(simpleControl.getEndpoints())) {
                Endpoint e = (Endpoint) o;
                if (e == null || e.getUID() == null || e.getType() == null) continue;
                String uid = e.getUID();
                String type = e.getType().name();
                String domain = "unknown";
                if ("LIGHT".equals(type) || "DIMMER".equals(type)) domain = "light";
                else if ("SWITCH".equals(type)) domain = "switch";
                else if ("BLINDS".equals(type) || "MARQUEE".equals(type)) domain = "cover";
                if ("unknown".equals(domain)) continue;

                String room = e.getLocationUID() != null ? locationMap.get(e.getLocationUID()) : "Unassigned";
                String baseDUid = uid.contains("_") ? uid.substring(0, uid.indexOf("_")) : uid;
                String baseTopic = "enet/" + domain + "/" + uid;
                
                String eName = e.getName() != null ? e.getName() : "Unknown Endpoint";
                uidToTopic.put(uid, baseTopic);
                topicToUid.put(baseTopic, uid);
                uidToName.put(uid, eName); 
                
                Map<String, String> hwInfo = deviceRegistryMeta.get(baseDUid);
                if (hwInfo != null) hwInfo.put("identifiers", "enet_" + baseDUid);
                
                publishHADiscovery(domain, uid, baseTopic, eName, hwInfo, room);
                publishInitialState(e, baseTopic);
                
                Map<String, String> dash = new HashMap<>();
                dash.put("uid", uid); dash.put("name", eName); dash.put("room", room);
                dash.put("domain", domain); dash.put("topic", baseTopic);
                dashboardItems.add(dash);
            }

            core.addLog("Topology Mapping Complete! Systemet er optimeret og skudsikkert.");
        } catch (Exception e) { 
            StringWriter sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw));
            core.addLog("Topology Error: " + sw.toString()); 
        }
    }

    private void publishHADiscovery(String domain, String uid, String baseTopic, String entityName, Map<String, String> meta, String entityRoom) {
        String configTopic = HA_PREFIX + "/" + domain + "/enet_" + uid + "/config";
        StringBuilder json = new StringBuilder("{").append("\"~\":\"").append(baseTopic).append("\",")
            .append("\"name\":\"").append(escapeJson(entityName)).append("\",").append("\"unique_id\":\"enet_").append(uid).append("\",");
        
        if (!"Unassigned".equals(entityRoom)) json.append("\"suggested_area\":\"").append(escapeJson(entityRoom)).append("\",");

        if (meta != null) {
            String ids = meta.containsKey("identifiers") ? meta.get("identifiers") : "enet_" + meta.get("serial");
            json.append("\"device\":{\"identifiers\":[\"").append(ids).append("\"],\"manufacturer\":\"JUNG\",\"via_device\":\"enet_controller\",")
                .append("\"name\":\"").append(escapeJson(meta.get("name"))).append("\",\"model\":\"").append(escapeJson(meta.get("model"))).append("\"},");
        }

        json.append("\"state_topic\":\"~/state\",\"command_topic\":\"~/set\",\"schema\":\"json\"");
        if (baseTopic.contains("/light/")) json.append(",\"brightness\":true,\"brightness_scale\":255");
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

    public String escapeJson(String input) { return input == null ? "" : input.replace("\"", "\\\""); }
}