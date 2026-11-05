package org.telegram.messenger.zephyr;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual production memory transport against synthetic connections/config/account state. No TCP or AI calls. */
public final class MemoryBridgeTest {
    interface Case { void run() throws Exception; }
    static int passed;
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void reset() {
        UserConfig.selectedAccount=0;UserConfig.owners=new long[]{2000,3000,0};
        ZephyrConfig.server="https://memory.example";ZephyrConfig.token="synthetic-memory-token-A-not-a-real-secret";
    }
    static void test(String name,Case action) throws Exception {
        reset();action.run();passed++;System.out.println("PASS "+name);
    }
    static void denied(int code,Case action) throws Exception {
        try {action.run();throw new AssertionError("expected error "+code);}
        catch (ZephyrAiClient.AiException e) {
            check(e.code==code);check(e.getMessage().matches("memory_[a-z_]+"));
            check(!e.getMessage().contains(ZephyrConfig.token));
        }
    }
    static JSONObject status(boolean enabled) throws Exception {
        return new JSONObject().put("protocolVersion",1).put("ownerId","2000")
            .put("collectionEnabled",enabled).put("storage",new JSONObject().put("configured",true).put("ready",true))
            .put("features",new JSONObject().put("legacyImport",true))
            .put("limits",new JSONObject().put("maxScopes",20).put("maxMessagesPerScope",200).put("maxBatchMessages",50)
                .put("maxMessageChars",4000).put("maxContextMessages",20).put("maxContextChars",6000)
                .put("minRetentionDays",1).put("maxRetentionDays",90).put("maxLegacySummaryChars",8000));
    }
    static String binding() throws Exception {return ZephyrMemoryClient.binding(ZephyrConfig.server,ZephyrConfig.token,0,2000);}
    static JSONObject message(int id,String sender) throws Exception {
        return new JSONObject().put("messageId",id).put("senderId",sender).put("speaker","other")
            .put("text","synthetic message").put("date","2026-11-03T08:00:00Z");
    }
    static JSONObject batch() throws Exception {return new JSONObject().put("messages",new JSONArray().put(message(1,"3000")));}
    static JSONObject context(long dialog,int topic,JSONArray messages) throws Exception {
        return new JSONObject().put("kind","untrusted_chat_history").put("instructions",false)
            .put("dialogId",String.valueOf(dialog)).put("topicId",topic).put("messages",messages).put("notes",new JSONArray());
    }
    static JSONObject legacy() throws Exception {
        return new JSONObject().put("summary","old synthetic summary").put("throughId",12).put("revision",2).put("updatedAt","2026-11-02T10:00:00Z");
    }
    static class Plan {
        int code=200;String type="application/json";String body;Runnable onResponse=()->{};Runnable onOutput=()->{};
        Plan(JSONObject body) {this.body=body.toString();}
    }
    static class Conn extends HttpURLConnection {
        final Plan plan;final ByteArrayOutputStream written=new ByteArrayOutputStream();volatile boolean disconnected;
        Conn(URL url,Plan plan) {super(url);this.plan=plan;}
        public void connect() { }
        public void disconnect() {disconnected=true;}
        public boolean usingProxy() {return false;}
        public int getResponseCode() {plan.onResponse.run();return plan.code;}
        public String getContentType() {return plan.type;}
        public InputStream getInputStream() {return new ByteArrayInputStream(plan.body.getBytes(StandardCharsets.UTF_8));}
        public OutputStream getOutputStream() {plan.onOutput.run();return written;}
    }
    static class Mock implements ZephyrMemoryClient.Connections {
        final ArrayDeque<Plan> plans=new ArrayDeque<>();final List<Conn> calls=new ArrayList<>();
        Mock add(JSONObject body) {plans.add(new Plan(body));return this;}
        Mock add(Plan plan) {plans.add(plan);return this;}
        public HttpURLConnection open(URL url) {check(!plans.isEmpty());Conn c=new Conn(url,plans.remove());calls.add(c);return c;}
        ZephyrMemoryClient client() {return new ZephyrMemoryClient(this);}
    }

    public static void main(String[] args) throws Exception {
        test("status binds owner and keeps credentials inside native transport",()->{
            Mock m=new Mock().add(status(false));JSONObject result=m.client().status(0,10,()->true);
            check(result.getBoolean("ownerMatch"));check(result.getString("ownerId").equals("2000"));
            check(result.getString("binding").equals(binding()));check(!result.toString().contains(ZephyrConfig.token));
            Conn c=m.calls.get(0);check(c.getURL().toString().equals("https://memory.example/v1/memory/status"));
            check(c.getRequestProperty("Authorization").equals("Bearer "+ZephyrConfig.token));
            check(c.getRequestProperty("X-Zephyr-Account-Id").equals("2000"));check(!c.getInstanceFollowRedirects());check(c.disconnected);
        });
        test("opaque binding changes for endpoint/token/account/owner",()->{
            String a=binding();check(a.matches("[a-f0-9]{64}"));
            check(!a.equals(ZephyrMemoryClient.binding(ZephyrConfig.server,ZephyrConfig.token,1,2000)));
            check(!a.equals(ZephyrMemoryClient.binding(ZephyrConfig.server,"other-token",0,2000)));
            check(!a.equals(ZephyrMemoryClient.binding("https://other.example",ZephyrConfig.token,0,2000)));
            check(!a.equals(ZephyrMemoryClient.binding(ZephyrConfig.server,ZephyrConfig.token,0,3000)));
        });
        test("missing and stale binding stop before every request",()->{
            Mock m=new Mock();denied(13,()->m.client().operate(0,"write",3000,0,batch(),10,null,()->true));
            String prior=binding();ZephyrConfig.token="rotated-test-token";
            denied(13,()->m.client().operate(0,"delete",3000,0,null,10,prior,()->true));check(m.calls.isEmpty());
        });
        test("wrong authenticated owner never receives chat data",()->{
            Mock m=new Mock().add(status(true).put("ownerId","3000"));
            denied(10,()->m.client().operate(0,"write",3000,0,batch(),10,binding(),()->true));check(m.calls.size()==1);
        });
        test("config swap during handshake cancels before data upload",()->{
            Plan p=new Plan(status(true));p.onResponse=()->ZephyrConfig.server="https://changed.example";
            Mock m=new Mock().add(p);denied(13,()->m.client().operate(0,"write",3000,0,batch(),10,binding(),()->true));check(m.calls.size()==1);
        });
        test("owner slot reuse cancels before data upload",()->{
            Plan p=new Plan(status(true));p.onResponse=()->UserConfig.owners[0]=9999;
            Mock m=new Mock().add(p);denied(11,()->m.client().operate(0,"write",3000,0,batch(),10,binding(),()->true));check(m.calls.size()==1);
        });
        test("selection change cancels old operation",()->{
            Plan p=new Plan(status(true));p.onResponse=()->UserConfig.selectedAccount=1;
            Mock m=new Mock().add(p);denied(11,()->m.client().status(0,10,()->true));
        });
        test("unloaded plugin guard fails before network",()->{
            Mock m=new Mock();denied(11,()->m.client().status(0,10,()->false));check(m.calls.isEmpty());
        });
        test("synchronous cancellation during handshake prevents data request",()->{
            AtomicBoolean allowed=new AtomicBoolean(true);Plan p=new Plan(status(true));p.onResponse=()->allowed.set(false);
            Mock m=new Mock().add(p);denied(11,()->m.client().operate(0,"write",3000,0,batch(),10,binding(),allowed::get));check(m.calls.size()==1);
        });
        test("synchronous cancellation before body write sends no text",()->{
            AtomicBoolean allowed=new AtomicBoolean(true);Plan p=new Plan(new JSONObject().put("accepted",1));p.onOutput=()->allowed.set(false);
            Mock m=new Mock().add(status(true)).add(p);denied(11,()->m.client().operate(0,"write",3000,0,batch(),10,binding(),allowed::get));
            check(m.calls.get(1).written.size()==0);
        });
        test("inactive and invalid account fail closed",()->{
            Mock m=new Mock();denied(10,()->m.client().status(2,10,()->true));denied(10,()->m.client().status(-1,10,()->true));check(m.calls.isEmpty());
        });
        test("HTTPS only without credentials query or fragment in URL",()->{
            Mock m=new Mock();for(String url:new String[]{"http://memory.example","https://user:pass@memory.example","https://memory.example?token=x","https://memory.example/#x"}) {
                ZephyrConfig.server=url;denied(1,()->m.client().status(0,10,()->true));
            }check(m.calls.isEmpty());
        });
        test("collection disabled blocks writes but permits confirmed deletion",()->{
            Mock write=new Mock().add(status(false));denied(14,()->write.client().operate(0,"write",3000,0,batch(),10,binding(),()->true));check(write.calls.size()==1);
            Mock del=new Mock().add(status(false)).add(new JSONObject().put("enabled",false).put("deleted",true));
            check(del.client().operate(0,"delete",3000,0,null,10,binding(),()->true).getBoolean("deleted"));
            check(del.calls.get(1).getRequestMethod().equals("DELETE"));check(del.calls.get(1).written.size()==0);
        });
        test("status works without storage, delete does not falsely succeed",()->{
            JSONObject noDb=status(false).put("storage",new JSONObject().put("configured",false).put("ready",false));
            check(!new Mock().add(noDb).client().status(0,10,()->true).getJSONObject("storage").getBoolean("configured"));
            Mock m=new Mock().add(noDb);denied(1,()->m.client().operate(0,"delete",3000,0,null,10,binding(),()->true));check(m.calls.size()==1);
        });
        test("batch validates identity roles fields and duplicates before network",()->{
            Mock m=new Mock();for(JSONObject invalid:new JSONObject[]{
                new JSONObject().put("messages",new JSONArray().put(message(1,"9999"))),
                new JSONObject().put("messages",new JSONArray().put(message(1,"3000")).put(message(1,"3000"))),
                new JSONObject().put("messages",new JSONArray().put(message(1,"3000").put("ownerId","9999"))),
                new JSONObject().put("messages",new JSONArray().put(message(1,"3000").put("messageId",1.5)))}) {
                denied(12,()->m.client().operate(0,"write",3000,0,invalid,10,binding(),()->true));
            }check(m.calls.isEmpty());
        });
        test("write uses exact path body and same-owner header on both calls",()->{
            Mock m=new Mock().add(status(true)).add(new JSONObject().put("accepted",1));
            check(m.client().operate(0,"write",3000,0,batch(),10,binding(),()->true).getInt("accepted")==1);
            Conn c=m.calls.get(1);check(c.getURL().toString().equals("https://memory.example/v1/memory/chats/3000/topics/0/messages"));
            check(c.getRequestMethod().equals("PUT"));check(c.getRequestProperty("X-Zephyr-Account-Id").equals("2000"));
            check(new JSONObject(c.written.toString("UTF-8")).getJSONArray("messages").getJSONObject(0).getString("senderId").equals("3000"));
        });
        test("response config change never returns success",()->{
            Plan p=new Plan(new JSONObject().put("accepted",1));p.onResponse=()->ZephyrConfig.token="changed-after-send";
            Mock m=new Mock().add(status(true)).add(p);denied(13,()->m.client().operate(0,"write",3000,0,batch(),10,binding(),()->true));
        });
        test("token change while opening request body prevents first text byte",()->{
            Plan p=new Plan(new JSONObject().put("accepted",1));p.onOutput=()->ZephyrConfig.token="changed-before-body";
            Mock m=new Mock().add(status(true)).add(p);denied(13,()->m.client().operate(0,"write",3000,0,batch(),10,binding(),()->true));
            check(m.calls.get(1).written.size()==0);
        });
        test("owner change while opening request body prevents first text byte",()->{
            Plan p=new Plan(new JSONObject().put("accepted",1));p.onOutput=()->UserConfig.owners[0]=9999;
            Mock m=new Mock().add(status(true)).add(p);denied(11,()->m.client().operate(0,"write",3000,0,batch(),10,binding(),()->true));
            check(m.calls.get(1).written.size()==0);
        });
        test("redirects are rejected without following a second host",()->{
            Plan p=new Plan(new JSONObject());p.code=302;
            Mock m=new Mock().add(p);denied(6,()->m.client().status(0,10,()->true));check(m.calls.size()==1);check(!m.calls.get(0).getInstanceFollowRedirects());
        });
        test("server errors never expose body secrets",()->{
            Plan p=new Plan(new JSONObject().put("error",new JSONObject().put("message",ZephyrConfig.token)));p.code=403;
            denied(2,()->new Mock().add(p).client().status(0,10,()->true));
        });
        test("server cannot echo native credential into Python data",()->{
            Mock m=new Mock().add(status(true).put("secret",ZephyrConfig.token));denied(12,()->m.client().status(0,10,()->true));
        });
        test("oversized or non-JSON response fails closed",()->{
            Plan large=new Plan(new JSONObject());large.body="x".repeat(65537);
            denied(12,()->new Mock().add(large).client().status(0,10,()->true));
            Plan html=new Plan(new JSONObject());html.type="text/html";
            denied(9,()->new Mock().add(html).client().status(0,10,()->true));
        });
        test("group context preserves separate participants and accepts empty notes",()->{
            JSONObject result=context(-1001234567890L,3,new JSONArray().put(message(1,"3000")).put(message(2,"4000")));
            Mock m=new Mock().add(status(true)).add(result);
            JSONArray rows=m.client().operate(0,"context",-1001234567890L,3,new JSONObject().put("query","example"),10,binding(),()->true).getJSONArray("messages");
            check(rows.getJSONObject(0).getString("senderId").equals("3000"));check(rows.getJSONObject(1).getString("senderId").equals("4000"));
        });
        test("cross-topic or cross-dialog context is never returned",()->{
            Mock m=new Mock().add(status(true)).add(context(-1001234567890L,4,new JSONArray()));
            denied(12,()->m.client().operate(0,"context",-1001234567890L,3,new JSONObject().put("query",""),10,binding(),()->true));
        });
        test("legacy import is private topic0 only before networking",()->{
            Mock m=new Mock();denied(12,()->m.client().operate(0,"import",-1001234567890L,3,legacy(),10,binding(),()->true));
            denied(12,()->m.client().operate(0,"import",3000,1,legacy(),10,binding(),()->true));check(m.calls.isEmpty());
        });
        test("legacy import requires server capability before upload",()->{
            JSONObject old=status(true).put("features",new JSONObject());
            Mock m=new Mock().add(old);denied(9,()->m.client().operate(0,"import",3000,0,legacy(),10,binding(),()->true));check(m.calls.size()==1);
        });
        test("legacy import keeps stored source metadata on unchanged input",()->{
            JSONObject stored=new JSONObject().put("imported",false).put("revision",3).put("throughId",20)
                .put("updatedAt","2026-11-03T08:00:00Z").put("reason","stale_summary");
            Mock m=new Mock().add(status(true)).add(stored);
            JSONObject result=m.client().operate(0,"import",3000,0,legacy(),10,binding(),()->true);
            check(!result.getBoolean("imported"));check(result.getInt("revision")==3);check(result.getInt("throughId")==20);
            check(m.calls.get(1).getURL().getPath().endsWith("/import"));
        });
        test("legacy context notes remain untrusted mixed-speaker notes",()->{
            JSONObject note=new JSONObject().put("kind","untrusted_legacy_summary").put("source","local_autoreply_summary")
                .put("speakers","mixed_unknown").put("text","synthetic old summary").put("throughId",12).put("revision",2).put("updatedAt","2026-11-02T10:00:00Z");
            JSONObject result=context(3000,0,new JSONArray()).put("notes",new JSONArray().put(note));
            JSONObject out=new Mock().add(status(true)).add(result).client().operate(0,"context",3000,0,new JSONObject().put("query",""),10,binding(),()->true);
            check(out.getJSONArray("messages").length()==0);check(out.getJSONArray("notes").getJSONObject(0).getString("speakers").equals("mixed_unknown"));
        });
        test("unsupported arbitrary operations and invalid retention never open a connection",()->{
            Mock m=new Mock();denied(12,()->m.client().operate(0,"https://evil.example",3000,0,null,10,binding(),()->true));
            denied(12,()->m.client().operate(0,"consent",3000,0,new JSONObject().put("retentionDays",91),10,binding(),()->true));check(m.calls.isEmpty());
        });
        test("one shared deadline disconnects blocked handshake",()->{
            Plan p=new Plan(status(true));Mock m=new Mock().add(p);
            p.onResponse=()->{while(!m.calls.get(0).disconnected) {try {Thread.sleep(10);}catch(InterruptedException e){throw new RuntimeException(e);}}};
            long start=System.nanoTime();denied(5,()->m.client().status(0,1,()->true));
            check((System.nanoTime()-start)/1000000<2500);
        });
        System.out.println("Memory bridge JVM cases passed: "+passed);
    }
}
