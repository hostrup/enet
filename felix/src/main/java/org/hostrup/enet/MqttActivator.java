package org.hostrup.enet;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

import com.insta.instanet.instanetbox.simplecontrol.ChangeType;
import com.insta.instanet.instanetbox.simplecontrol.Endpoint;
import com.insta.instanet.instanetbox.simplecontrol.EndpointState;
import com.insta.instanet.instanetbox.simplecontrol.EndpointStateLevel;
import com.insta.instanet.instanetbox.simplecontrol.EndpointStatePower;
import com.insta.instanet.instanetbox.simplecontrol.ISimpleControl;
import com.insta.instanet.instanetbox.simplecontrol.ISimpleControlEventHandler;
import com.insta.instanet.instanetbox.simplecontrol.Location;
import com.insta.instanet.instanetbox.simplecontrol.ProjectChangeType;
import com.insta.instanet.instanetbox.simplecontrol.Scene;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MqttActivator implements BundleActivator, EventHandler, ISimpleControlEventHandler {
    
    public volatile boolean debugMode = true; 

    private ExecutorService executor;
    private BundleContext context;
    private ServiceRegistration eventRegistration;
    private HttpServer webServer;
    private ISimpleControl simpleControl = null;

    private final ConcurrentLinkedQueue<String> logBuffer = new ConcurrentLinkedQueue<>();
    private final java.util.Map<String, String> buttonRockerStates = new java.util.concurrent.ConcurrentHashMap<>();
    
    private ConfigManager configManager;
    private MqttManager mqttManager;
    private TopologyBuilder topologyBuilder;

    @Override
    public void start(BundleContext context) throws Exception {
        this.context = context;
        this.executor = new ThreadPoolExecutor(2, 4, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(200), new ThreadPoolExecutor.DiscardOldestPolicy());
        
        this.configManager = new ConfigManager(this);
        this.mqttManager = new MqttManager(this);
        this.topologyBuilder = new TopologyBuilder(this);

        setupWebDashboard();
        mqttManager.connectMqtt();

        Dictionary<String, String[]> props = new Hashtable<>();
        props.put(EventConstants.EVENT_TOPIC, new String[]{"*"});
        eventRegistration = context.registerService(EventHandler.class.getName(), this, props);

        executor.submit(this::waitForSimpleControlAndBuild);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (simpleControl != null) simpleControl.unregisterEventHandler(this);
        if (eventRegistration != null) eventRegistration.unregister();
        if (webServer != null) webServer.stop(0);
        if (executor != null) executor.shutdownNow();
        if (mqttManager != null) mqttManager.disconnect();
    }

    public void addLog(String message) {
        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
        logBuffer.offer(ts + " - " + message);
        while (logBuffer.size() > 100) logBuffer.poll();
        if (mqttManager != null && mqttManager.isConnected()) {
            mqttManager.publish("enet/gateway/log", message, 0, false);
        }
        System.out.println("HostrupEnet: " + message);
    }

    private void setupWebDashboard() {
        try {
            webServer = HttpServer.create(new InetSocketAddress(8090), 0);
            webServer.createContext("/mqtt", new WebDashboard(this));
            webServer.setExecutor(executor);
            webServer.start();
            addLog("Web Dashboard mounted natively on port 8090");
        } catch (Exception e) { 
            addLog("Error mounting Web Dashboard: " + e.getMessage()); 
        }
    }

    private void waitForSimpleControlAndBuild() {
        int attempts = 0;
        while (simpleControl == null && attempts < 60) {
            try {
                ServiceReference ref = context.getServiceReference(ISimpleControl.class.getName());
                if (ref != null) simpleControl = (ISimpleControl) context.getService(ref);
            } catch (Exception e) {}
            if (simpleControl == null) { try { Thread.sleep(3000); } catch (Exception ignored) {} attempts++; }
        }
        if (simpleControl != null) {
            simpleControl.registerEventHandler(this);
            try { Thread.sleep(5000); } catch (Exception ignored) {} 
            topologyBuilder.buildTopologyMap();
        } else {
            addLog("FATAL: ISimpleControl never loaded.");
        }
    }

    @Override
    public void handleEvent(final Event event) {
        executor.submit(() -> {
            try {
                if (event == null) return;
                String topic = event.getTopic();

                // 1. OMNI-SNIFFER (Kept for debugging and visibility)
                if (debugMode && topic != null) {
                    String tLower = topic.toLowerCase();
                    if (!tLower.contains("systemtime") && !tLower.contains("metric") && !tLower.contains("task") && !tLower.contains("heartbeat") && !tLower.contains("memory") && !tLower.contains("log")) {
                        if (topic.startsWith("MW/") || topic.startsWith("StackAdapter") || tLower.contains("button") || tLower.contains("function")) {
                            
                            String dUid = (String) event.getProperty("deviceUID");
                            Object chNumObj = event.getProperty("channelNumber");
                            String eName = "Unknown Device";
                            
                            if (dUid != null) {
                                Map<String, String> meta = topologyBuilder.deviceRegistryMeta.get(dUid);
                                if (meta != null) eName = meta.get("name");
                                if (chNumObj != null) eName += " (Button " + chNumObj + ")";
                            }

                            StringBuilder sb = new StringBuilder("OMNI-SNIFFER [" + eName + "] | " + topic + " | Props: { ");
                            for (String key : event.getPropertyNames()) {
                                if (!key.equals("event.topics")) {
                                    Object val = event.getProperty(key);
                                    sb.append(key).append("=").append(val != null ? val.toString() : "null").append(", ");
                                }
                            }
                            sb.append("}");
                            addLog(sb.toString());
                        }
                    }
                }

                if (mqttManager == null || !mqttManager.isConnected()) return;

                // 2. BATTERY INGRESS
                if ("DeviceBatteryStateChanged".equals(topic)) {
                    String uid = (String) event.getProperty("deviceUID");
                    if (uid == null) return;
                    Map<String, String> meta = topologyBuilder.deviceRegistryMeta.get(uid);
                    if (meta == null || meta.get("serial") == null) return;

                    String state = String.valueOf(event.getProperty("batteryState"));
                    String payload = "{\"state\":\"" + ((state.contains("LOW") || state.contains("EMPTY")) ? "ON" : "OFF") + "\"}";
                    mqttManager.publish("enet/sensor/battery/" + meta.get("serial") + "/state", payload, 0, true);
                    return;
                }

                // 3. VALUE CHANGE INGRESS (Actuator feedback and physical transmitter button rocker decoding)
                if ("MW/ValueChanged".equals(topic)) {
                    String valueUID = (String) event.getProperty("valueUID");
                    Object valObj = event.getProperty("value");
                    String valueTypeID = (String) event.getProperty("valueTypeID");
                    
                    if (valueUID != null && valObj != null) {
                        String endpointUID = topologyBuilder.valueUidToEndpointUid.get(valueUID);
                        if (endpointUID != null) {
                            String baseTopic = topologyBuilder.uidToTopic.get(endpointUID);
                            
                            if (baseTopic != null) {
                                // Standard actuator value updates (Lights/Switches)
                                String eName = topologyBuilder.uidToName.getOrDefault(endpointUID, "Unknown Device");
                                String payload = null;
                                if ("VT_SWITCH".equals(valueTypeID)) {
                                    boolean isOn = (Boolean) valObj;
                                    payload = "{\"state\":\"" + (isOn ? "ON" : "OFF") + "\"}";
                                } else if ("VT_ABSOLUTE_LEVEL".equals(valueTypeID)) {
                                    int level = (Integer) valObj;
                                    payload = "{\"state\":\"" + (level > 0 ? "ON" : "OFF") + "\",\"brightness\":" + Math.round((level / 100.0) * 255.0) + "}";
                                }
                                
                                if (payload != null) {
                                    if (debugMode) addLog("DEBUG INGRESS (EventAdmin): [" + eName + "] Updating status: " + payload);
                                    mqttManager.publish(baseTopic + "/state", payload, 0, true);
                                }
                            } else {
                                // Transmitter button event (endpointUID maps to no baseTopic, but has rocker state/time properties)
                                if (endpointUID.contains("_")) {
                                    String dUid = endpointUID.substring(0, endpointUID.indexOf("_"));
                                    String chStr = endpointUID.substring(endpointUID.indexOf("_") + 1);
                                    
                                    Map<String, String> meta = topologyBuilder.deviceRegistryMeta.get(dUid);
                                    if (meta != null && meta.get("serial") != null) {
                                        String hexSerial = meta.get("serial");
                                        int chNum = Integer.parseInt(chStr);
                                        
                                        if ("VT_ROCKER_STATE".equals(valueTypeID)) {
                                            String rState = valObj.toString(); // "UP_BUTTON" or "DOWN_BUTTON"
                                            buttonRockerStates.put(endpointUID, rState);
                                            if (debugMode) addLog("DEBUG BUTTON STATE: [" + hexSerial + " Ch " + chNum + "] State changed to: " + rState);
                                        } else if ("VT_ROCKER_SWITCH_TIME".equals(valueTypeID)) {
                                            int switchTime = ((Number) valObj).intValue();
                                            String rockerState = buttonRockerStates.getOrDefault(endpointUID, "UP_BUTTON");
                                            
                                            // Determine target channel (ch 1-4 for UP, ch 5-8 for DOWN)
                                            int targetCh = chNum;
                                            if ("DOWN_BUTTON".equals(rockerState)) {
                                                targetCh = chNum + 4;
                                            }
                                            
                                            String btnUid = hexSerial + "_ch" + targetCh;
                                            String btnTopic = topologyBuilder.uidToTopic.get(btnUid);
                                            
                                            if (btnTopic != null) {
                                                String btnName = topologyBuilder.uidToName.getOrDefault(btnUid, "Unknown Button");
                                                String btnState = "IDLE";
                                                if (switchTime == 0) {
                                                    btnState = "INITIAL_PRESS";
                                                } else if (switchTime > 60) {
                                                    btnState = "LONG_RELEASE";
                                                } else {
                                                    btnState = "SHORT_RELEASE";
                                                }
                                                
                                                if (debugMode) addLog("DEBUG INGRESS [BUTTON]: [" + btnName + "] State -> " + btnState + " (time=" + switchTime + ")");
                                                mqttManager.publish(btnTopic + "/state", btnState, 0, false);
                                                
                                                final String finalTopic = btnTopic;
                                                executor.submit(() -> {
                                                    try { Thread.sleep(350); } catch (Exception ignored) {}
                                                    mqttManager.publish(finalTopic + "/state", "IDLE", 0, false);
                                                });
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return;
                }

            } catch (Exception e) {
                if (debugMode) addLog("DEBUG ERROR in handleEvent: " + e.getMessage());
            }
        });
    }

    // 4. ACTUATOR INGRESS (Lights and Dimmers via ISimpleControl)
    @Override
    public void endpointStateChanged(String endpointId, EndpointState state) {
        executor.submit(() -> {
            try {
                if (endpointId == null || state == null) return;
                if (debugMode) addLog("DEBUG INGRESS (Event): endpointStateChanged called for " + endpointId + " with state class: " + state.getClass().getName() + " -> " + state.toString());
                
                if (mqttManager == null || !mqttManager.isConnected()) return;
                String baseTopic = topologyBuilder.uidToTopic.get(endpointId);
                if (baseTopic == null) return;
                
                String eName = topologyBuilder.uidToName.getOrDefault(endpointId, "Unknown Device");

                if (state instanceof EndpointStatePower) {
                    EndpointStatePower.Value val = ((EndpointStatePower) state).getValue();
                    if (val == null) {
                        if (debugMode) addLog("DEBUG INGRESS (Event): EndpointStatePower value is null for " + eName);
                        return;
                    }
                    String power = val.name(); 
                    String payload = "{\"state\":\"" + (baseTopic.contains("/cover/") ? ("ON".equals(power) ? "open" : "closed") : power) + "\"}";
                    if (debugMode) addLog("DEBUG INGRESS (Event): [" + eName + "] Updating power status to: " + payload);
                    mqttManager.publish(baseTopic + "/state", payload, 0, true);
                } else if (state instanceof EndpointStateLevel) {
                    int pct = ((EndpointStateLevel) state).getValue();
                    String payload = "{\"state\":\"" + (pct > 0 ? "ON" : "OFF") + "\",\"brightness\":" + Math.round((pct / 100.0) * 255.0) + "}";
                    if (debugMode) addLog("DEBUG INGRESS (Event): [" + eName + "] Updating dim level status to: " + payload);
                    mqttManager.publish(baseTopic + "/state", payload, 0, true);
                } else {
                    if (debugMode) addLog("DEBUG INGRESS (Event): State type not handled: " + state.getClass().getName());
                }
            } catch (Exception e) {
                addLog("ERROR in endpointStateChanged: " + e.toString());
            }
        });
    }

    public Object getServiceByFilter(String className) {
        try {
            org.osgi.framework.ServiceReference[] refs = context.getServiceReferences((String)null, "(objectClass=" + className + ")");
            if (refs != null && refs.length > 0) return context.getService(refs[0]);
        } catch (Exception e) {} return null;
    }

    public Object safeInvoke(Object obj, String method) { return safeInvokeWithArgs(obj, method, new Class<?>[0], new Object[0]); }

    public Object safeInvokeWithArgs(Object obj, String method, Class<?>[] types, Object[] args) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(method, types);
            m.setAccessible(true); return m.invoke(obj, args);
        } catch (Exception e) {
            try {
                java.lang.reflect.Method m = obj.getClass().getDeclaredMethod(method, types);
                m.setAccessible(true); return m.invoke(obj, args);
            } catch (Exception e2) {
                for (Class<?> iface : obj.getClass().getInterfaces()) {
                    try { return iface.getMethod(method, types).invoke(obj, args); } catch (Exception e3) {}
                }
            }
        } return null;
    }

    @Override public void handleControlResponse(String eId, int status, String msg, String reqId) {}
    @Override public void endpointChanged(ChangeType cType, String eId, Endpoint ep) {}
    @Override public void locationChanged(ChangeType cType, String locId, Location loc) {}
    @Override public void sceneChanged(ChangeType cType, String sceneId, Scene sc) {}
    @Override public void projectChanged(ProjectChangeType pType) {}

    public ExecutorService getExecutor() { return executor; }
    public BundleContext getContext() { return context; }
    public ISimpleControl getSimpleControl() { return simpleControl; }
    public ConfigManager getConfigManager() { return configManager; }
    public MqttManager getMqttManager() { return mqttManager; }
    public TopologyBuilder getTopologyBuilder() { return topologyBuilder; }
    public ConcurrentLinkedQueue<String> getLogBuffer() { return logBuffer; }
}