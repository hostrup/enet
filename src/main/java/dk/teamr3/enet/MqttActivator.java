package dk.teamr3.enet;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.http.HttpService;

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
    private HttpService httpService;
    private ISimpleControl simpleControl = null;

    private final ConcurrentLinkedQueue<String> logBuffer = new ConcurrentLinkedQueue<>();
    
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
        if (httpService != null) httpService.unregister("/mqtt");
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
        System.out.println("TeamR3: " + message);
    }

    private void setupWebDashboard() {
        try {
            ServiceReference ref = context.getServiceReference(HttpService.class.getName());
            if (ref != null) {
                httpService = (HttpService) context.getService(ref);
                httpService.registerServlet("/mqtt", new WebDashboard(this), null, null);
                addLog("Web Dashboard mounted on /mqtt");
            }
        } catch (Exception e) { addLog("Error mounting Web Dashboard: " + e.getMessage()); }
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

                // 1. OMNI-SNIFFER (Beholdes for fejlsøgning og synlighed)
                if (debugMode && topic != null) {
                    String tLower = topic.toLowerCase();
                    if (!tLower.contains("systemtime") && !tLower.contains("metric") && !tLower.contains("task") && !tLower.contains("heartbeat") && !tLower.contains("memory") && !tLower.contains("log")) {
                        if (topic.startsWith("MW/") || topic.startsWith("StackAdapter") || tLower.contains("button") || tLower.contains("function")) {
                            
                            String dUid = (String) event.getProperty("deviceUID");
                            Object chNumObj = event.getProperty("channelNumber");
                            String eName = "Ukendt Enhed";
                            
                            if (dUid != null) {
                                Map<String, String> meta = topologyBuilder.deviceRegistryMeta.get(dUid);
                                if (meta != null) eName = meta.get("name");
                                if (chNumObj != null) eName += " (Knap " + chNumObj + ")";
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

                // 2. BATTERI INGRESS
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

                // 3. KNAP INGRESS (Fysiske Vægkontakter) - Nu skudsikker via DeviceUID + Channel
                if ("MW/DeviceFunctionCalled".equals(topic)) {
                    String dUid = (String) event.getProperty("deviceUID");
                    Object chNumObj = event.getProperty("channelNumber");
                    Boolean isPressed = (Boolean) event.getProperty("valueChanged");
                    
                    if (dUid != null && chNumObj != null && Boolean.TRUE.equals(isPressed)) {
                        Map<String, String> meta = topologyBuilder.deviceRegistryMeta.get(dUid);
                        if (meta != null) {
                            String hexSerial = meta.get("serial");
                            String btnUid = hexSerial + "_ch" + chNumObj;
                            String mqttTopic = topologyBuilder.uidToTopic.get(btnUid);
                            
                            if (mqttTopic != null && mqttTopic.contains("/button/")) {
                                String eName = topologyBuilder.uidToName.getOrDefault(btnUid, "Ukendt Knap");
                                if (debugMode) addLog("DEBUG INGRESS [KNAP]: [" + eName + "] Trykket!");
                                
                                mqttManager.publish(mqttTopic + "/state", "PRESSED", 0, false);
                                
                                executor.submit(() -> {
                                    try { Thread.sleep(300); } catch (Exception ignored) {}
                                    mqttManager.publish(mqttTopic + "/state", "IDLE", 0, false);
                                });
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

    // 4. AKTUATOR INGRESS (Lys og Dæmpere via ISimpleControl)
    @Override
    public void endpointStateChanged(String endpointId, EndpointState state) {
        executor.submit(() -> {
            try {
                if (mqttManager == null || !mqttManager.isConnected() || endpointId == null) return;
                String baseTopic = topologyBuilder.uidToTopic.get(endpointId);
                if (baseTopic == null) return;
                
                String eName = topologyBuilder.uidToName.getOrDefault(endpointId, "Ukendt Enhed");

                if (state instanceof EndpointStatePower) {
                    String power = ((EndpointStatePower) state).getValue().name(); 
                    String payload = "{\"state\":\"" + (baseTopic.contains("/cover/") ? ("ON".equals(power) ? "open" : "closed") : power) + "\"}";
                    if (debugMode) addLog("DEBUG INGRESS (Event): [" + eName + "] Opdaterer status: " + payload);
                    mqttManager.publish(baseTopic + "/state", payload, 0, true);
                } else if (state instanceof EndpointStateLevel) {
                    int pct = ((EndpointStateLevel) state).getValue();
                    String payload = "{\"state\":\"" + (pct > 0 ? "ON" : "OFF") + "\",\"brightness\":" + Math.round((pct / 100.0) * 255.0) + "}";
                    if (debugMode) addLog("DEBUG INGRESS (Event): [" + eName + "] Opdaterer status: " + payload);
                    mqttManager.publish(baseTopic + "/state", payload, 0, true);
                }
            } catch (Exception ignored) {}
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