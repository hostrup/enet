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
import java.util.concurrent.ScheduledExecutorService;
import java.lang.reflect.Method;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.HttpContext;
import javax.servlet.Servlet;

/**
 * The main OSGi Bundle Activator and EventHandler for the eNet MQTT Gateway.
 * It manages the lifecycle of the bundle, sets up an internal web dashboard server,
 * listens to eNet event notifications via EventAdmin and SimpleControl frameworks,
 * and coordinates data dispatching through the Config, Mqtt, and Topology sub-managers.
 */
public class MqttActivator implements BundleActivator, EventHandler, ISimpleControlEventHandler {
    
    /**
     * Toggles verbose logging of internal/external events to the logs dashboard.
     */
    public volatile boolean debugMode = true; 

    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;
    private BundleContext context;
    private ServiceRegistration eventRegistration;
    private HttpServer webServer;
    private ISimpleControl simpleControl = null;

    /**
     * Internal ring buffer storing the last 100 log lines to display in the Web Dashboard.
     */
    private final ConcurrentLinkedQueue<String> logBuffer = new ConcurrentLinkedQueue<>();
    
    /**
     * Cache tracking button press states (UP/DOWN toggles) to differentiate switch timings.
     */
    private final java.util.Map<String, String> buttonRockerStates = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Cache for reflection methods.
     */
    private final java.util.Map<String, Method> reflectionCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final SimpleDateFormat LOG_TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

    private ConfigManager configManager;
    private MqttManager mqttManager;
    private TopologyBuilder topologyBuilder;

    /**
     * OSGi Activator hook. Invoked when the bundle is loaded and started by the Felix container.
     * Initializes configuration properties, MQTT connectivity, the topology builder, and spins
     * up the local HTTP web console, registering as an EventHandler in the container.
     */
    @Override
    public void start(BundleContext context) throws Exception {
        this.context = context;
        this.executor = new ThreadPoolExecutor(2, 4, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(200), new ThreadPoolExecutor.DiscardOldestPolicy());
        this.scheduledExecutor = java.util.concurrent.Executors.newScheduledThreadPool(1);
        
        this.configManager = new ConfigManager(this);
        this.mqttManager = new MqttManager(this);
        this.topologyBuilder = new TopologyBuilder(this);

        setupWebDashboard();
        registerGdsServlet();
        mqttManager.connectMqtt();

        Dictionary<String, String[]> props = new Hashtable<>();
        props.put(EventConstants.EVENT_TOPIC, new String[]{"MW/ValueChanged", "DeviceBatteryStateChanged"});
        eventRegistration = context.registerService(EventHandler.class.getName(), this, props);

        executor.submit(this::waitForSimpleControlAndBuild);
    }

    /**
     * OSGi Activator hook. Invoked when the bundle is stopped or uninstalled.
     * Performs graceful cleanup by unregistering listeners, shutting down the HTTP dashboard,
     * shutting down the local worker thread pool, and disconnecting the MQTT broker.
     */
    @Override
    public void stop(BundleContext context) throws Exception {
        try {
            if (simpleControl != null) simpleControl.unregisterEventHandler(this);
        } catch (Exception e) {
            System.err.println("Error unregistering simpleControl: " + e.getMessage());
        }

        try {
            if (eventRegistration != null) eventRegistration.unregister();
        } catch (Exception e) {
            System.err.println("Error unregistering eventRegistration: " + e.getMessage());
        }

        try {
            if (webServer != null) webServer.stop(0);
        } catch (Exception e) {
            System.err.println("Error stopping webServer: " + e.getMessage());
        }

        try {
            org.osgi.framework.ServiceReference httpRef = context.getServiceReference(HttpService.class.getName());
            if (httpRef != null) {
                HttpService httpService = (HttpService) context.getService(httpRef);
                httpService.unregister("/gds");
                addLog("GDS: Unregistered GdsServlet.");
            }
        } catch (Exception ignored) {}

        try {
            if (executor != null) executor.shutdownNow();
        } catch (Exception e) {
            System.err.println("Error shutting down executor: " + e.getMessage());
        }

        try {
            if (scheduledExecutor != null) scheduledExecutor.shutdownNow();
        } catch (Exception e) {
            System.err.println("Error shutting down scheduledExecutor: " + e.getMessage());
        }

        try {
            if (mqttManager != null) mqttManager.disconnect();
        } catch (Exception e) {
            System.err.println("Error disconnecting mqttManager: " + e.getMessage());
        }
    }

    /**
     * Appends a log message prefixed with a time stamp to the ring buffer.
     * Also publishes it to the MQTT topic "enet/gateway/log" if connected.
     * 
     * @param message The raw log message string.
     */
    public void addLog(String message) {
        String ts;
        synchronized (LOG_TIME_FORMAT) {
            ts = LOG_TIME_FORMAT.format(new Date());
        }
        logBuffer.offer(ts + " - " + message);
        while (logBuffer.size() > 100) logBuffer.poll();
        if (mqttManager != null && mqttManager.isConnected()) {
            mqttManager.publish("enet/gateway/log", message, 0, false);
        }
        System.out.println("HostrupEnet: " + message);
    }

    /**
     * Initializes the built-in HTTP server on port 8090 to host the Web UI configuration panel.
     */
    private void setupWebDashboard() {
        try {
            webServer = HttpServer.create(new InetSocketAddress(8090), 0);
            webServer.createContext("/mqtt", new WebDashboard(this));
            webServer.setExecutor(null);
            webServer.start();
            addLog("Web Dashboard mounted natively on port 8090");
        } catch (Exception e) { 
            addLog("Error mounting Web Dashboard: " + e.getMessage()); 
        }
    }

    /**
     * Dynamically loads and mounts JUNG's internal Gira Device Service (GDS) REST API under /gds.
     */
    private void registerGdsServlet() {
        try {
            org.osgi.framework.Bundle gdsBundle = null;
            for (org.osgi.framework.Bundle b : context.getBundles()) {
                if ("com.insta.instanet.instanetbox.servlet".equals(b.getSymbolicName())) {
                    gdsBundle = b;
                    break;
                }
            }
            if (gdsBundle == null) {
                addLog("GDS: Bundle com.insta.instanet.instanetbox.servlet not found.");
                return;
            }
            Class<?> gdsServletClass = gdsBundle.loadClass("de.infoteam.insta.instaboxservlet.servlets.GdsServlet");
            if (gdsServletClass == null) {
                addLog("GDS: GdsServlet class not found in bundle.");
                return;
            }
            Servlet gdsServlet = (Servlet) gdsServletClass.newInstance();
            
            org.osgi.framework.ServiceReference httpRef = context.getServiceReference(HttpService.class.getName());
            if (httpRef != null) {
                HttpService httpService = (HttpService) context.getService(httpRef);
                HttpContext httpContext = httpService.createDefaultHttpContext();
                httpService.registerServlet("/gds", gdsServlet, null, httpContext);
                addLog("GDS: Successfully registered GdsServlet under /gds");
            } else {
                addLog("GDS: HttpService reference not found.");
            }
        } catch (Exception e) {
            addLog("GDS: Error registering GdsServlet dynamically: " + e.toString());
        }
    }

    /**
     * Periodically queries the OSGi BundleContext for the ISimpleControl service.
     * Once resolved, registers the activator as an event handler and triggers topology building.
     */
    private void waitForSimpleControlAndBuild() {
        int attempts = 0;
        while (simpleControl == null && attempts < 60) {
            try {
                ServiceReference ref = context.getServiceReference(ISimpleControl.class.getName());
                if (ref != null) simpleControl = (ISimpleControl) context.getService(ref);
            } catch (Exception e) {}
            if (simpleControl == null) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    addLog("Startup loop interrupted while waiting for ISimpleControl.");
                    return;
                } catch (Exception ignored) {}
                attempts++;
            }
        }
        if (simpleControl != null) {
            simpleControl.registerEventHandler(this);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                addLog("Startup loop interrupted after registering event handler.");
                return;
            } catch (Exception ignored) {} 
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

                // 3. VALUE CHANGE INGRESS (Transmitter button rocker decoding only; actuator status is in endpointStateChanged)
                if ("MW/ValueChanged".equals(topic)) {
                    String valueUID = (String) event.getProperty("valueUID");
                    Object valObj = event.getProperty("value");
                    String valueTypeID = (String) event.getProperty("valueTypeID");
                    
                    if (valueUID != null && valObj != null) {
                        String endpointUID = topologyBuilder.valueUidToEndpointUid.get(valueUID);
                        if (endpointUID != null) {
                            String baseTopic = topologyBuilder.uidToTopic.get(endpointUID);
                            
                            if (baseTopic == null) {
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
                                                scheduledExecutor.schedule(() -> {
                                                    mqttManager.publish(finalTopic + "/state", "IDLE", 0, false);
                                                }, 350, TimeUnit.MILLISECONDS);
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

    // 4. ACTUATOR INGRESS (Lights, Dimmers and Covers via ISimpleControl)
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
                    String payload;
                    if (baseTopic.contains("/cover/")) {
                        payload = "{\"state\":\"" + (pct > 0 ? "open" : "closed") + "\",\"position\":" + pct + "}";
                    } else {
                        payload = "{\"state\":\"" + (pct > 0 ? "ON" : "OFF") + "\",\"brightness\":" + Math.round((pct / 100.0) * 255.0) + "}";
                    }
                    if (debugMode) addLog("DEBUG INGRESS (Event): [" + eName + "] Updating level status to: " + payload);
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

    private String getCacheKey(Class<?> clazz, String methodName, Class<?>[] parameterTypes) {
        StringBuilder sb = new StringBuilder(clazz.getName()).append("#").append(methodName);
        if (parameterTypes != null) {
            for (Class<?> p : parameterTypes) {
                sb.append(":").append(p.getName());
            }
        }
        return sb.toString();
    }

    public Object safeInvokeWithArgs(Object obj, String method, Class<?>[] types, Object[] args) {
        if (obj == null) return null;
        Class<?> clazz = obj.getClass();
        String key = getCacheKey(clazz, method, types);
        Method m = reflectionCache.get(key);
        if (m == null) {
            try {
                m = clazz.getMethod(method, types);
                m.setAccessible(true);
                reflectionCache.put(key, m);
            } catch (Exception e) {
                try {
                    m = clazz.getDeclaredMethod(method, types);
                    m.setAccessible(true);
                    reflectionCache.put(key, m);
                } catch (Exception e2) {
                    for (Class<?> iface : clazz.getInterfaces()) {
                        try {
                            m = iface.getMethod(method, types);
                            m.setAccessible(true);
                            reflectionCache.put(key, m);
                            break;
                        } catch (Exception e3) {}
                    }
                }
            }
        }
        if (m != null) {
            try {
                return m.invoke(obj, args);
            } catch (Exception e) {
                // Ignore invocation exceptions and return null
            }
        }
        return null;
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