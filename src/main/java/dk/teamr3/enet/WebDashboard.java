package dk.teamr3.enet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import com.insta.instanet.instanetbox.simplecontrol.EndpointStatePower;

public class WebDashboard extends HttpServlet {
    private static final String BUNDLE_JAR_PATH = "/home/insta/felix-framework/bundle/startlevel4/enet-mqtt-2.0-PRODUCTION.jar";
    private final MqttActivator core;

    public WebDashboard(MqttActivator core) { 
        this.core = core; 
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action"); resp.setCharacterEncoding("UTF-8");
        if ("status".equals(action)) {
            resp.setContentType("application/json"); StringBuilder json = new StringBuilder();
            json.append("{").append("\"broker\":\"").append(core.getTopologyBuilder().escapeJson(core.getConfigManager().getMqttBroker())).append("\",")
                .append("\"user\":\"").append(core.getTopologyBuilder().escapeJson(core.getConfigManager().getMqttUser())).append("\",")
                .append("\"connected\":").append(core.getMqttManager().isConnected()).append(",")
                .append("\"debug\":").append(core.debugMode).append(",")
                .append("\"logs\":[");
            Object[] logs = core.getLogBuffer().toArray();
            for (int i = 0; i < logs.length; i++) {
                json.append("\"").append(core.getTopologyBuilder().escapeJson((String) logs[i])).append("\"");
                if (i < logs.length - 1) json.append(",");
            }
            json.append("]}"); resp.getWriter().write(json.toString());
        } else if ("devices".equals(action)) {
            resp.setContentType("application/json"); StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < core.getTopologyBuilder().dashboardItems.size(); i++) {
                Map<String, String> map = core.getTopologyBuilder().dashboardItems.get(i);
                json.append("{").append("\"uid\":\"").append(core.getTopologyBuilder().escapeJson(map.get("uid"))).append("\",")
                    .append("\"name\":\"").append(core.getTopologyBuilder().escapeJson(map.get("name"))).append("\",")
                    .append("\"room\":\"").append(core.getTopologyBuilder().escapeJson(map.get("room"))).append("\",")
                    .append("\"domain\":\"").append(core.getTopologyBuilder().escapeJson(map.get("domain"))).append("\",")
                    .append("\"topic\":\"").append(core.getTopologyBuilder().escapeJson(map.get("topic"))).append("\"").append("}");
                if (i < core.getTopologyBuilder().dashboardItems.size() - 1) json.append(",");
            }
            json.append("]"); resp.getWriter().write(json.toString());
        } else {
            resp.setContentType("text/html");
            String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>eNet MQTT Gateway</title>" +
                "<style>body{background:#0d1117;color:#c9d1d9;font-family:monospace;margin:0;padding:20px;} " +
                "h1,h2{color:#58a6ff;} .panel{background:#161b22;border:1px solid #30363d;border-radius:6px;padding:20px;margin-bottom:20px;} " +
                "input{background:#0d1117;color:#c9d1d9;border:1px solid #30363d;padding:8px;margin-right:10px;border-radius:4px;} " +
                "button{background:#238636;color:#fff;border:none;padding:8px 16px;border-radius:4px;cursor:pointer;margin-right:5px;} " +
                "button.off{background:#da3633;} button.warn{background:#d29922;color:#000;} button.info{background:#1f6feb;color:#fff;} " +
                "#logs{height:200px;overflow-y:auto;background:#000;color:#0f0;padding:10px;font-size:12px;border:1px solid #30363d;} " +
                "table{width:100%;border-collapse:collapse;} th,td{border:1px solid #30363d;padding:8px;text-align:left;} " +
                ".indicator{display:inline-block;width:12px;height:12px;border-radius:50%;margin-right:8px;} .red{background:red;} .green{background:green;} " +
                "</style></head><body><h1>eNet Native MQTT Gateway v11.0 (Modular)</h1><div class='panel'><h2>Configuration</h2>" +
                "<div><span id='status-dot' class='indicator red'></span> <span id='status-text'>Offline</span></div><br>" +
                "Broker IP: <input id='b' type='text' placeholder='tcp://10.0.0.6:1883' style='width:250px;'> " +
                "User: <input id='u' type='text'> Pass: <input id='p' type='password'> <button onclick='save()'>Save & Connect</button>" +
                "<button id='debugBtn' class='info' onclick='toggleDebug()' style='float:right; margin-left: 10px;'>Debug: OFF</button>" +
                "<button class='warn' onclick='reloadGateway()' style='float:right;'>Hot-Reload Gateway</button></div>" +
                "<div class='panel'><h2>Live Logs</h2><div id='logs'></div></div><div class='panel'><h2>Topology & Testing</h2>" +
                "<table><thead><tr><th>Room</th><th>Name</th><th>Domain</th><th>Actions</th></tr></thead><tbody id='devs'></tbody></table></div>" +
                "<script>let init=0; function uLog(){ fetch('/mqtt/api?action=status').then(r=>r.json()).then(d=>{ " +
                "if(!init){ document.getElementById('b').value=d.broker; document.getElementById('u').value=d.user; init=1; } " +
                "let dt=document.getElementById('status-dot'); let st=document.getElementById('status-text'); " +
                "if(d.connected){dt.className='indicator green'; st.innerText='Connected';}else{dt.className='indicator red'; st.innerText='Offline';} " +
                "let dbg = document.getElementById('debugBtn'); if(d.debug) { dbg.innerText = 'Debug: ON'; dbg.style.background = '#da3633'; } else { dbg.innerText = 'Debug: OFF'; dbg.style.background = '#1f6feb'; } " +
                "let lb=document.getElementById('logs'); lb.innerHTML=d.logs.join('<br>'); lb.scrollTop=lb.scrollHeight; });} " +
                "function uDev(){ fetch('/mqtt/api?action=devices').then(r=>r.json()).then(d=>{ let h=''; d.forEach(x=>{ " +
                "h+='<tr><td>'+x.room+'</td><td>'+x.name+'</td><td>'+x.domain+'</td><td>'; if(x.domain==='light' || x.domain==='switch') { " +
                "h+='<button onclick=\"tg(\\''+x.uid+'\\',\\'ON\\')\">ON</button> <button class=\"off\" onclick=\"tg(\\''+x.uid+'\\',\\'OFF\\')\">OFF</button>'; " +
                "} h+='</td></tr>'; }); document.getElementById('devs').innerHTML=h; });} " +
                "function save(){ let f=new URLSearchParams(); f.append('action', 'save'); f.append('b',document.getElementById('b').value); " +
                "f.append('u',document.getElementById('u').value); f.append('p',document.getElementById('p').value); fetch('/mqtt/api',{method:'POST',body:f}).then(()=>uLog()); } " +
                "function tg(id, st){ let f=new URLSearchParams(); f.append('action', 'toggle'); f.append('uid',id); f.append('st',st); fetch('/mqtt/api',{method:'POST',body:f}); } " +
                "function reloadGateway(){ fetch('/mqtt/api?action=reload',{method:'POST'}); alert('Reload signal sendt. Tjek logs.'); } " +
                "function toggleDebug(){ fetch('/mqtt/api?action=debug',{method:'POST'}).then(()=>uLog()); } " +
                "setInterval(uLog, 2000); uLog(); setInterval(uDev, 5000);</script></body></html>";
            resp.getWriter().write(html);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        if ("save".equals(action)) {
            core.getConfigManager().saveConfig(req.getParameter("b"), req.getParameter("u"), req.getParameter("p"));
            resp.setStatus(200);
        } else if ("debug".equals(action)) {
            core.debugMode = !core.debugMode;
            core.addLog("Debug Mode is now: " + (core.debugMode ? "ON" : "OFF"));
            resp.setStatus(200);
        } else if ("toggle".equals(action)) {
            String uid = req.getParameter("uid"); String st = req.getParameter("st");
            if (core.getSimpleControl() != null && uid != null && st != null) {
                try {
                    EndpointStatePower pwr = new EndpointStatePower();
                    pwr.setValue("ON".equals(st) ? EndpointStatePower.Value.ON : EndpointStatePower.Value.OFF);
                    core.getSimpleControl().handleControlRequest(uid, pwr, "dashboard_test");
                    core.addLog("Dashboard Test: Sent " + st + " to " + uid);
                } catch (Exception ex) {
                    core.addLog("Dashboard Test Error: " + ex.getMessage());
                }
            }
            resp.setStatus(200);
        } else if ("reload".equals(action)) {
            core.addLog("Hot-Reload signal modtaget. Genindlæser OSGi Bundle...");
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    core.getContext().getBundle().update(new java.io.FileInputStream(new java.io.File(BUNDLE_JAR_PATH)));
                } catch (Exception e) { System.out.println("Reload failed: " + e.getMessage()); }
            }).start();
            resp.setStatus(200);
        }
    }
}