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

/** Production style-layer transport (memory stage 2) against synthetic connections/config/accounts. No TCP or AI calls. */
public final class MemoryStyleBridgeTest {
    interface Case { void run() throws Exception; }
    static int passed;
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void reset() {
        UserConfig.selectedAccount = 0; UserConfig.owners = new long[]{2000, 3000, 0};
        ZephyrConfig.server = "https://memory.example"; ZephyrConfig.token = "synthetic-memory-token-A-not-a-real-secret";
    }
    static void test(String name, Case action) throws Exception {
        reset(); action.run(); passed++; System.out.println("PASS " + name);
    }
    static void denied(int code, String key, Case action) throws Exception {
        try { action.run(); throw new AssertionError("expected error " + code); }
        catch (ZephyrAiClient.AiException e) {
            if (e.code != code) throw new AssertionError("code " + e.code + " " + e.getMessage() + ", expected " + code);
            check(e.getMessage().matches("memory_[a-z_]+")); check(!e.getMessage().contains(ZephyrConfig.token));
            if (key != null && !key.equals(e.getMessage())) throw new AssertionError(e.getMessage() + ", expected " + key);
        }
    }
    static String binding() throws Exception { return ZephyrMemoryClient.binding(ZephyrConfig.server, ZephyrConfig.token, 0, 2000); }

    static JSONObject limits() throws Exception {
        return new JSONObject().put("maxBatch", 50).put("maxTextChars", 4000).put("embedChars", 2000).put("maxQueryChars", 2000)
            .put("maxSearchK", 8).put("maxSearchChars", 8000).put("maxFactChars", 500).put("maxFactsK", 20).put("maxPeriodDays", 31)
            .put("maxDigestChars", 4000).put("maxExamplesPerOwner", 3000).put("maxFeedbackPerOwner", 5000).put("maxFactsPerOwner", 1000);
    }
    static JSONObject status(boolean enabled) throws Exception {
        return new JSONObject().put("protocolVersion", 1).put("ownerId", "2000").put("collectionEnabled", enabled)
            .put("storage", new JSONObject().put("configured", true).put("ready", true))
            .put("features", new JSONObject().put("legacyImport", true).put("style", true))
            .put("limits", new JSONObject().put("maxScopes", 20))
            .put("style", new JSONObject().put("ready", true).put("embeddings", false).put("embeddingStorage", "real[]")
                .put("retentionDays", 90).put("limits", limits()));
    }
    static JSONObject pair() throws Exception {
        return new JSONObject().put("contextText", "synthetic question?").put("replyText", "synthetic answer").put("source", "owner_real");
    }
    static JSONObject add(JSONObject... rows) {
        JSONArray array = new JSONArray(); for (JSONObject row : rows) array.put(row);
        return new JSONObject().put("examples", array);
    }
    static JSONObject example(String id, String context, String reply) throws Exception {
        return new JSONObject().put("id", id).put("contextText", context).put("replyText", reply).put("source", "owner_real")
            .put("quality", 3).put("score", 0.8123).put("createdAt", "2026-11-05T10:00:00.000Z");
    }
    static JSONObject search(String area, JSONObject... rows) throws Exception {
        JSONArray array = new JSONArray(); for (JSONObject row : rows) array.put(row);
        return new JSONObject().put("kind", "untrusted_style_examples").put("instructions", false).put("area", area)
            .put("mode", "text").put("examples", array).put("textCharacters", 0).put("embedding", "disabled");
    }
    static JSONObject digest(String area, String text) throws Exception {
        return new JSONObject().put("kind", "untrusted_digest").put("instructions", false).put("id", "3").put("area", area)
            .put("periodStart", "2026-10-29T10:00:00.000Z").put("periodEnd", "2026-11-05T10:00:00.000Z").put("text", text)
            .put("createdAt", "2026-11-05T10:00:00.000Z").put("expiresAt", "2027-02-03T10:00:00.000Z");
    }

    static class Plan {
        int code = 200; String type = "application/json"; String body; Runnable onResponse = () -> { };
        Plan(JSONObject body) { this.body = body.toString(); }
        Plan(int code, String errorCode) throws Exception {
            this(new JSONObject().put("error", new JSONObject().put("type", "x").put("code", errorCode).put("message", ZephyrConfig.token)));
            this.code = code;
        }
    }
    static class Conn extends HttpURLConnection {
        final Plan plan; final ByteArrayOutputStream written = new ByteArrayOutputStream(); volatile boolean disconnected;
        Conn(URL url, Plan plan) { super(url); this.plan = plan; }
        public void connect() { }
        public void disconnect() { disconnected = true; }
        public boolean usingProxy() { return false; }
        public int getResponseCode() { plan.onResponse.run(); return plan.code; }
        public String getContentType() { return plan.type; }
        public InputStream getInputStream() { return new ByteArrayInputStream(plan.body.getBytes(StandardCharsets.UTF_8)); }
        public InputStream getErrorStream() { return plan.code >= 400 ? getInputStream() : null; }
        public OutputStream getOutputStream() { return written; }
        JSONObject body() throws Exception { return new JSONObject(written.toString("UTF-8")); }
    }
    static class Mock implements ZephyrMemoryClient.Connections {
        final ArrayDeque<Plan> plans = new ArrayDeque<>(); final List<Conn> calls = new ArrayList<>();
        Mock add(JSONObject body) { plans.add(new Plan(body)); return this; }
        Mock add(Plan plan) { plans.add(plan); return this; }
        public HttpURLConnection open(URL url) { check(!plans.isEmpty()); Conn c = new Conn(url, plans.remove()); calls.add(c); return c; }
        JSONObject run(String op, String area, JSONObject body) throws Exception {
            return new ZephyrMemoryClient(this).operateStyle(0, op, area, body, 10, binding(), () -> true);
        }
    }

    public static void main(String[] args) throws Exception {
        test("style status is whitelisted and bound to the verified owner", () -> {
            Mock m = new Mock().add(status(true).put("debug", ZephyrConfig.server));
            JSONObject s = new ZephyrMemoryClient(m).styleStatus(0, 10, () -> true);
            check(s.getString("binding").equals(binding())); check(s.getJSONObject("features").getBoolean("style"));
            check(s.getJSONObject("style").getInt("retentionDays") == 90); check(!s.has("debug")); check(!s.has("limits"));
            check(s.getJSONObject("style").getJSONObject("limits").getInt("maxSearchK") == 8);
            Conn c = m.calls.get(0); check(c.getURL().toString().equals("https://memory.example/v1/memory/status"));
            check(c.getRequestProperty("X-Zephyr-Account-Id").equals("2000")); check(!c.getInstanceFollowRedirects());
        });
        test("older server without the style feature reports style null and refuses operations", () -> {
            JSONObject old = status(true); old.getJSONObject("features").remove("style"); old.remove("style");
            check(new ZephyrMemoryClient(new Mock().add(old)).styleStatus(0, 10, () -> true).isNull("style"));
            Mock m = new Mock().add(old); denied(9, "memory_style_not_supported", () -> m.run("style_add", "global", add(pair())));
            check(m.calls.size() == 1);
        });
        test("missing or stale binding stops before any request", () -> {
            Mock m = new Mock();
            denied(13, null, () -> new ZephyrMemoryClient(m).operateStyle(0, "style_add", "global", add(pair()), 10, null, () -> true));
            String prior = binding(); ZephyrConfig.token = "rotated-test-token";
            denied(13, null, () -> new ZephyrMemoryClient(m).operateStyle(0, "delete_all", null, null, 10, prior, () -> true));
            check(m.calls.isEmpty());
        });
        test("cancelled plugin and unknown operation never open a connection", () -> {
            Mock m = new Mock();
            denied(11, null, () -> new ZephyrMemoryClient(m).operateStyle(0, "style_add", "global", add(pair()), 10, binding(), () -> false));
            denied(12, null, () -> m.run("https://evil.example", "global", add(pair())));
            denied(12, null, () -> m.run("status", "global", null));
            check(m.calls.isEmpty());
        });
        test("style_add posts whitelisted pairs to the fixed path and whitelists the answer", () -> {
            Mock m = new Mock().add(status(true)).add(new JSONObject().put("accepted", 1).put("inserted", 1).put("embedded", 0)
                .put("embedding", "disabled").put("extra", "x"));
            JSONObject r = m.run("style_add", "dialog:3000", add(pair().put("quality", 4)));
            check(r.getInt("inserted") == 1 && r.getString("embedding").equals("disabled") && !r.has("extra"));
            Conn c = m.calls.get(1); check(c.getURL().toString().equals("https://memory.example/v1/memory/style"));
            check(c.getRequestMethod().equals("POST")); check(c.getRequestProperty("Authorization").equals("Bearer " + ZephyrConfig.token));
            JSONObject sent = c.body(); check(sent.getString("area").equals("dialog:3000"));
            check(sent.getJSONArray("examples").getJSONObject(0).getInt("quality") == 4);
        });
        test("invalid requests fail before networking", () -> {
            Mock m = new Mock();
            for (String area : new String[]{null, "", "-x", "a b", "dialog/3000", "x".repeat(65)}) {
                denied(12, null, () -> m.run("style_search", area, new JSONObject().put("query", "q")));
            }
            JSONObject many = new JSONObject().put("examples", new JSONArray()); for (int i = 0; i < 51; i++) many.getJSONArray("examples").put(pair());
            for (JSONObject bad : new JSONObject[]{many, add(), add(pair().put("source", "model")), add(pair().put("quality", 6)),
                    add(pair().put("replyText", "   ")), add(pair().put("replyText", "x".repeat(4001))), add(pair().put("replyText", "a\0b")),
                    add(pair().put("ownerId", "9999")), add(pair()).put("area", "other")}) {
                denied(12, null, () -> m.run("style_add", "global", bad));
            }
            JSONObject edited = new JSONObject().put("outcome", "edited").put("draftText", "a").put("finalText", "b");
            for (JSONObject item : new JSONObject[]{new JSONObject(edited.toString()).put("editDistance", 1),
                    new JSONObject().put("outcome", "edited").put("draftText", "a"), new JSONObject().put("outcome", "rejected")
                    .put("draftText", "a").put("finalText", "b"), new JSONObject().put("outcome", "owner_first").put("draftText", "a"),
                    new JSONObject().put("outcome", "accepted").put("finalText", "a"), new JSONObject().put("outcome", "maybe").put("draftText", "a")}) {
                denied(12, null, () -> m.run("feedback", "global", new JSONObject().put("items", new JSONArray().put(item))));
            }
            JSONObject fact = new JSONObject().put("subject", "peer").put("fact", "synthetic fact");
            for (JSONObject bad : new JSONObject[]{new JSONObject(fact.toString()).put("subject", "other"),
                    new JSONObject(fact.toString()).put("confidence", 1.5), new JSONObject(fact.toString()).put("confidence", true),
                    new JSONObject(fact.toString()).put("sourceMsg", "a/b"), new JSONObject(fact.toString()).put("expiresInDays", 366),
                    new JSONObject(fact.toString()).put("fact", "x".repeat(501))}) {
                denied(12, null, () -> m.run("facts_add", "global", new JSONObject().put("facts", new JSONArray().put(bad))));
            }
            denied(12, null, () -> m.run("style_search", "global", new JSONObject().put("query", "q").put("k", 9)));
            denied(12, null, () -> m.run("facts_search", "global", new JSONObject().put("subject", "peer").put("k", 21)));
            denied(12, null, () -> m.run("facts_search", "global", new JSONObject().put("query", "q")));
            denied(12, null, () -> m.run("digest", "global", new JSONObject().put("generate", "yes")));
            denied(12, null, () -> m.run("digest", "global", new JSONObject().put("periodDays", 32)));
            denied(12, null, () -> m.run("delete_area", "global", new JSONObject()));
            denied(12, null, () -> m.run("delete_all", "global", null));
            check(m.calls.isEmpty());
        });
        test("tighter advertised limits are enforced after the handshake and before upload", () -> {
            JSONObject s = status(true); s.getJSONObject("style").getJSONObject("limits").put("maxSearchK", 3);
            Mock m = new Mock().add(s);
            denied(12, null, () -> m.run("style_search", "global", new JSONObject().put("query", "q").put("k", 5)));
            Mock peer = new Mock().add(status(true)); // server retention is 90 days: a peer fact may not outlive it
            denied(12, null, () -> peer.run("facts_add", "global", new JSONObject().put("facts", new JSONArray()
                .put(new JSONObject().put("subject", "peer").put("fact", "f").put("expiresInDays", 120)))));
            check(m.calls.size() == 1 && peer.calls.size() == 1);
        });
        test("collection disabled blocks writes but deletes an area by encoded fixed path", () -> {
            Mock write = new Mock().add(status(false)); denied(14, "memory_disabled", () -> write.run("style_add", "global", add(pair())));
            check(write.calls.size() == 1);
            JSONObject deleted = new JSONObject().put("examples", 3).put("feedback", 2).put("facts", 2).put("digests", 1);
            Mock del = new Mock().add(status(false)).add(new JSONObject().put("deleted", deleted).put("area", "dialog:-100123:7"));
            JSONObject r = del.run("delete_area", "dialog:-100123:7", null);
            check(r.getJSONObject("deleted").getInt("examples") == 3 && r.getString("area").equals("dialog:-100123:7"));
            Conn c = del.calls.get(1); check(c.getRequestMethod().equals("DELETE")); check(c.written.size() == 0);
            check(c.getURL().toString().equals("https://memory.example/v1/memory/area/dialog%3A-100123%3A7"));
        });
        test("delete of all areas requires a null area in the answer", () -> {
            JSONObject deleted = new JSONObject().put("examples", 0).put("feedback", 0).put("facts", 0).put("digests", 0);
            Mock ok = new Mock().add(status(false)).add(new JSONObject().put("deleted", deleted).put("area", JSONObject.NULL));
            check(ok.run("delete_all", null, null).isNull("area"));
            check(ok.calls.get(1).getURL().toString().equals("https://memory.example/v1/memory/areas"));
            Mock bad = new Mock().add(status(false)).add(new JSONObject().put("deleted", deleted).put("area", "global"));
            denied(12, null, () -> bad.run("delete_all", null, null));
        });
        test("style layer not ready or storage missing never reports success", () -> {
            JSONObject starting = status(true); starting.getJSONObject("style").put("ready", false).put("embeddingStorage", JSONObject.NULL);
            denied(4, "memory_starting", () -> new Mock().add(starting).run("delete_area", "global", null));
            JSONObject noDb = status(true).put("storage", new JSONObject().put("configured", false).put("ready", false));
            denied(1, null, () -> new Mock().add(noDb).run("delete_all", null, null));
        });
        test("search returns only untrusted examples of the requested area within bounds", () -> {
            Mock m = new Mock().add(status(true)).add(search("dialog:3000", example("17", "ctx", "reply").put("secret", "x")));
            JSONObject r = m.run("style_search", "dialog:3000", new JSONObject().put("query", "в кино?").put("k", 2));
            check(r.getString("kind").equals("untrusted_style_examples") && !r.getBoolean("instructions"));
            JSONObject row = r.getJSONArray("examples").getJSONObject(0);
            check(!row.has("secret") && row.getString("id").equals("17") && r.getInt("textCharacters") == 8);
            check(m.calls.get(1).body().getString("query").equals("в кино?"));
            denied(12, null, () -> new Mock().add(status(true)).add(search("dialog:4000"))
                .run("style_search", "dialog:3000", new JSONObject().put("query", "q")));
            denied(12, null, () -> new Mock().add(status(true)).add(search("global").put("instructions", true))
                .run("style_search", "global", new JSONObject().put("query", "q")));
            denied(12, null, () -> new Mock().add(status(true)).add(search("global", example("1", "a", "b"), example("2", "c", "d")))
                .run("style_search", "global", new JSONObject().put("query", "q").put("k", 1)));
            denied(12, null, () -> new Mock().add(status(true)).add(search("global", example("1", "a".repeat(4000), "b".repeat(4000)),
                example("2", "c", "d"))).run("style_search", "global", new JSONObject().put("query", "q")));
            denied(12, null, () -> new Mock().add(status(true)).add(search("global", example("x1", "a", "b")))
                .run("style_search", "global", new JSONObject().put("query", "q")));
        });
        test("feedback keeps server-computed edit distance and null for drafts without a final", () -> {
            JSONArray items = new JSONArray().put(new JSONObject().put("outcome", "edited").put("draftText", "Да, приду")
                .put("finalText", "да приду)").put("contextText", "Придёшь?")).put(new JSONObject().put("outcome", "rejected").put("draftText", "нет"));
            Mock m = new Mock().add(status(true)).add(new JSONObject().put("accepted", 2).put("styleExamples", 1).put("embedded", 0)
                .put("items", new JSONArray().put(new JSONObject().put("outcome", "edited").put("editDistance", 3))
                    .put(new JSONObject().put("outcome", "rejected").put("editDistance", JSONObject.NULL))).put("embedding", "disabled"));
            JSONObject r = m.run("feedback", "dialog:3000", new JSONObject().put("items", items));
            check(r.getJSONArray("items").getJSONObject(0).getInt("editDistance") == 3 && r.getJSONArray("items").getJSONObject(1).isNull("editDistance"));
            check(m.calls.get(1).getURL().getPath().equals("/v1/memory/feedback"));
        });
        test("facts keep subjects apart and allow indefinite self facts", () -> {
            Mock add = new Mock().add(status(true)).add(new JSONObject().put("accepted", 1).put("inserted", 1));
            add.run("facts_add", "global", new JSONObject().put("facts", new JSONArray().put(new JSONObject().put("subject", "self")
                .put("fact", "synthetic self fact").put("confidence", 0.9).put("sourceMsg", "3000:123").put("expiresInDays", 3650))));
            check(add.calls.get(1).body().getJSONArray("facts").getJSONObject(0).getDouble("confidence") == 0.9);
            JSONObject fact = new JSONObject().put("id", "5").put("subject", "self").put("fact", "synthetic").put("confidence", 0.7)
                .put("sourceMsg", JSONObject.NULL).put("createdAt", "2026-11-05T10:00:00.000Z").put("expiresAt", JSONObject.NULL);
            JSONObject answer = new JSONObject().put("kind", "untrusted_facts").put("instructions", false).put("area", "global")
                .put("subject", "self").put("facts", new JSONArray().put(fact));
            JSONObject r = new Mock().add(status(true)).add(answer).run("facts_search", "global", new JSONObject().put("subject", "self"));
            check(r.getJSONArray("facts").getJSONObject(0).isNull("expiresAt") && r.getJSONArray("facts").getJSONObject(0).isNull("sourceMsg"));
            JSONObject mixed = new JSONObject(answer.toString()); mixed.getJSONArray("facts").getJSONObject(0).put("subject", "peer");
            denied(12, null, () -> new Mock().add(status(true)).add(mixed).run("facts_search", "global", new JSONObject().put("subject", "self")));
        });
        test("digest: stored, no data, generated with a longer budget, and mismatched area", () -> {
            JSONObject none = new Mock().add(status(true)).add(new JSONObject().put("generated", false).put("digest", JSONObject.NULL))
                .run("digest", "global", new JSONObject());
            check(!none.getBoolean("generated") && none.isNull("digest"));
            Mock m = new Mock().add(status(true)).add(new JSONObject().put("generated", true).put("digest", digest("global", "synthetic")));
            JSONObject r = new ZephyrMemoryClient(m).operateStyle(0, "digest", "global", new JSONObject().put("generate", true)
                .put("periodDays", 7), 60, binding(), () -> true);
            check(r.getJSONObject("digest").getString("kind").equals("untrusted_digest"));
            check(m.calls.get(1).getReadTimeout() > 30000);
            JSONObject noData = new Mock().add(status(true)).add(new JSONObject().put("generated", false).put("reason", "no_data")
                .put("digest", JSONObject.NULL)).run("digest", "global", new JSONObject().put("generate", true));
            check(noData.getString("reason").equals("no_data"));
            denied(12, null, () -> new Mock().add(status(true)).add(new JSONObject().put("generated", true).put("digest", digest("other", "x")))
                .run("digest", "global", new JSONObject().put("generate", true)));
            denied(12, null, () -> new Mock().add(status(true)).add(new JSONObject().put("generated", true).put("digest", JSONObject.NULL))
                .run("digest", "global", new JSONObject().put("generate", true)));
            denied(12, null, () -> new Mock().add(status(true)).add(new JSONObject().put("generated", true).put("digest", digest("global", "x".repeat(4001))))
                .run("digest", "global", new JSONObject().put("generate", true)));
        });
        test("server error codes map to fixed keys and never expose the body", () -> {
            Object[][] cases = {{429, "rate_limit_exceeded", 3, "memory_rate_limited"}, {429, "daily_limit_exceeded", 3, "memory_daily_limit"},
                {503, "memory_disabled", 14, "memory_disabled"}, {503, "memory_starting", 4, "memory_starting"},
                {503, "digest_unavailable", 9, "memory_digest_unavailable"}, {503, "something_else", 4, "memory_unavailable"},
                {502, "digest_failed", 6, "memory_digest_failed"}, {500, "x", 6, "memory_request_failed"},
                {403, "memory_account_mismatch", 10, "memory_account_mismatch"}, {401, "invalid_api_key", 2, "memory_not_authorized"},
                {413, "x", 12, "memory_request_too_large"}, {404, "x", 9, "memory_style_not_supported"}, {400, "invalid_memory_input", 12, "memory_invalid_data"}};
            for (Object[] c : cases) {
                Plan p = new Plan((Integer) c[0], (String) c[1]);
                denied((Integer) c[2], (String) c[3], () -> new Mock().add(status(true)).add(p).run("digest", "global", new JSONObject().put("generate", true)));
            }
            Plan redirect = new Plan(new JSONObject()); redirect.code = 302;
            Mock m = new Mock().add(status(true)).add(redirect); denied(6, null, () -> m.run("style_add", "global", add(pair())));
            check(m.calls.size() == 2);
            Plan huge = new Plan(503, "memory_disabled"); huge.body = "{\"error\":{\"code\":\"memory_disabled\",\"message\":\"" + "x".repeat(5000) + "\"}}";
            denied(4, "memory_unavailable", () -> new Mock().add(status(true)).add(huge).run("style_add", "global", add(pair())));
        });
        test("bulk routes accept up to 1 MiB, others stay at 256 KiB", () -> {
            String cyr = "я".repeat(3998);
            JSONObject big = new JSONObject().put("examples", new JSONArray());
            for (int i = 0; i < 50; i++) big.getJSONArray("examples").put(pair().put("contextText", cyr + i).put("replyText", cyr));
            Mock ok = new Mock().add(status(true)).add(new JSONObject().put("accepted", 50).put("inserted", 50).put("embedded", 0).put("embedding", "disabled"));
            check(ok.run("style_add", "global", big).getInt("inserted") == 50);
            check(ok.calls.get(1).written.size() > 256 * 1024);
            JSONArray items = new JSONArray();
            for (int i = 0; i < 50; i++) items.put(new JSONObject().put("outcome", "replaced").put("draftText", cyr).put("finalText", cyr).put("contextText", cyr));
            Mock tooBig = new Mock().add(status(true));
            denied(12, "memory_request_too_large", () -> tooBig.run("feedback", "global", new JSONObject().put("items", items)));
            check(tooBig.calls.size() == 1);
        });
        test("credential echoed by the server is never returned", () -> {
            Mock m = new Mock().add(status(true)).add(new JSONObject().put("accepted", 1).put("inserted", 1).put("note", ZephyrConfig.token));
            denied(12, null, () -> m.run("facts_add", "global", new JSONObject().put("facts", new JSONArray()
                .put(new JSONObject().put("subject", "self").put("fact", "f")))));
        });
        test("owner switch during the handshake cancels before the data request", () -> {
            Plan p = new Plan(status(true)); p.onResponse = () -> UserConfig.owners[0] = 9999;
            Mock m = new Mock().add(p); denied(11, null, () -> m.run("style_add", "global", add(pair()))); check(m.calls.size() == 1);
        });
        System.out.println("Memory style JVM cases passed: " + passed);
    }
}
