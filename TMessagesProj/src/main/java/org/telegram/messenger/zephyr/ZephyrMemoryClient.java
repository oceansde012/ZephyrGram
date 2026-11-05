package org.telegram.messenger.zephyr;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Fixed-path private memory transport. No token or arbitrary authenticated HTTP method is exposed to Python. */
public final class ZephyrMemoryClient {
    public static final int API_VERSION = 1;
    public static final int ERR_UNSUPPORTED = 9;
    public static final int ERR_ACCOUNT = 10;
    public static final int ERR_CANCELLED = 11;
    public static final int ERR_INVALID = 12;
    public static final int ERR_BINDING = 13;
    public static final int ERR_DISABLED = 14;
    public static final int ERR_CONSENT = 15;
    private static final int MAX_RESPONSE_BYTES = 65536;
    private static final int MAX_REQUEST_BYTES = 256 * 1024;
    /** Style layer (memory stage 2): POST /style and /feedback accept 1 MiB on the server, everything else 256 KiB. */
    public static final int STYLE_API_VERSION = 1;
    private static final int MAX_STYLE_BULK_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 4096;
    private static final String DATE_RE = "[0-9]{4}-[0-9]{2}-[0-9]{2}T.+(Z|[+-][0-9]{2}:[0-9]{2})";
    static final String AREA_RE = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}";
    static final List<String> STYLE_OPERATIONS = Arrays.asList(
            "style_add", "style_search", "feedback", "facts_add", "facts_search", "digest", "delete_area", "delete_all");
    private static final List<String> STYLE_SOURCES = Arrays.asList("owner_real", "accepted", "edited_final");
    private static final List<String> STYLE_OUTCOMES = Arrays.asList("accepted", "edited", "replaced", "rejected", "owner_first");
    private static final List<String> STYLE_SUBJECTS = Arrays.asList("self", "peer");
    private static final List<String> STYLE_EMBEDDING = Arrays.asList("used", "partial", "disabled", "skipped_quota", "unavailable", "not_needed");
    private static final ScheduledExecutorService DEADLINES = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "ZephyrMemoryDeadline");
        thread.setDaemon(true);
        return thread;
    });

    public interface Guard { boolean isCurrent(); }
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    private final Connections connections;
    ZephyrMemoryClient(Connections connections) { this.connections = connections; }
    private static final ZephyrMemoryClient DEFAULT = new ZephyrMemoryClient(url -> (HttpURLConnection) url.openConnection());

    private static ZephyrAiClient.AiException error(int code, String key) {
        return new ZephyrAiClient.AiException(code, key);
    }

    private static String baseUrl() throws ZephyrAiClient.AiException {
        try {
            String base = ZephyrAiSetupParser.normalizeServerUrl(ZephyrConfig.getAiServerUrl());
            URI uri = new URI(base);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw error(ZephyrAiClient.ERR_NOT_CONFIGURED, "memory_https_required");
            }
            return base;
        } catch (ZephyrAiClient.AiException e) { throw e; }
        catch (Exception e) { throw error(ZephyrAiClient.ERR_NOT_CONFIGURED, "memory_https_required"); }
    }

    /** Inert assertion, not an authentication credential. Length-prefixing avoids ambiguous concatenation. */
    static String binding(String base, String token, int account, long owner) throws ZephyrAiClient.AiException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{"zephyr-memory-v1", base, token, String.valueOf(account), String.valueOf(owner)}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (Exception e) { throw error(ERR_BINDING, "memory_binding_unavailable"); }
    }

    private static final class Snapshot {
        final int account, selected, maxMs;
        final long owner, deadline;
        final String base, token, binding;
        final Guard allowed;
        volatile boolean expired;
        Snapshot(int account, int timeout, Guard allowed) throws ZephyrAiClient.AiException {
            this(account, timeout, 30, allowed);
        }
        /** maxSeconds is 30 for every call except digest generation (one model call on the server, up to 60). */
        Snapshot(int account, int timeout, int maxSeconds, Guard allowed) throws ZephyrAiClient.AiException {
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) throw error(ERR_ACCOUNT, "memory_invalid_account");
            this.account = account;
            this.selected = UserConfig.selectedAccount;
            this.owner = UserConfig.getInstance(account).getClientUserId();
            if (owner <= 0) throw error(ERR_ACCOUNT, "memory_account_inactive");
            this.base = baseUrl();
            this.token = ZephyrAiSetupParser.sanitizeSecret(ZephyrConfig.getAiToken());
            if (token.isEmpty()) throw error(ZephyrAiClient.ERR_NOT_CONFIGURED, "memory_not_configured");
            this.binding = binding(base, token, account, owner);
            this.allowed = allowed;
            this.maxMs = Math.max(1, Math.min(60, maxSeconds)) * 1000;
            this.deadline = System.nanoTime() + Math.max(1, Math.min(maxMs / 1000, timeout)) * 1000000000L;
            check();
        }
        int remaining() throws ZephyrAiClient.AiException {
            long ms = (deadline - System.nanoTime()) / 1000000L;
            if (expired || ms <= 0) throw error(ZephyrAiClient.ERR_NETWORK, "memory_timeout");
            return (int) Math.min(maxMs, ms);
        }
        void check() throws ZephyrAiClient.AiException {
            if (Thread.currentThread().isInterrupted() || allowed == null || !allowed.isCurrent()
                    || selected != UserConfig.selectedAccount || owner != UserConfig.getInstance(account).getClientUserId()) {
                throw error(ERR_CANCELLED, "memory_operation_cancelled");
            }
            if (!base.equals(baseUrl()) || !token.equals(ZephyrAiSetupParser.sanitizeSecret(ZephyrConfig.getAiToken()))) {
                throw error(ERR_BINDING, "memory_binding_changed");
            }
            remaining();
        }
    }

    private static void expected(Snapshot snapshot, String value) throws ZephyrAiClient.AiException {
        if (value == null || !value.matches("[a-f0-9]{64}") || !MessageDigest.isEqual(
                snapshot.binding.getBytes(StandardCharsets.US_ASCII), value.getBytes(StandardCharsets.US_ASCII))) {
            throw error(ERR_BINDING, "memory_binding_changed");
        }
    }

    private JSONObject request(Snapshot snapshot, String method, String path, JSONObject body) throws ZephyrAiClient.AiException {
        return request(snapshot, method, path, body, MAX_REQUEST_BYTES, false);
    }

    /** styleErrors: map the server's fixed error code (never its message) to a fixed memory_* key; v1 keeps status-only mapping. */
    private JSONObject request(Snapshot snapshot, String method, String path, JSONObject body, int maxBytes,
                               boolean styleErrors) throws ZephyrAiClient.AiException {
        snapshot.check();
        byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes != null && bytes.length > maxBytes) throw error(ERR_INVALID, "memory_request_too_large");
        if (body != null && body.toString().contains(snapshot.token)) throw error(ERR_INVALID, "memory_sensitive_data");
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        try {
            connection = connections.open(new URL(snapshot.base + "/v1/memory" + path));
            final HttpURLConnection active = connection;
            deadline = DEADLINES.schedule(() -> {
                snapshot.expired = true;
                try { active.disconnect(); } catch (Exception ignored) { }
            }, snapshot.remaining(), TimeUnit.MILLISECONDS);
            connection.setInstanceFollowRedirects(false); // Never forward the credential to redirects.
            connection.setRequestMethod(method);
            connection.setConnectTimeout(Math.min(10000, snapshot.remaining()));
            connection.setReadTimeout(snapshot.remaining());
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            snapshot.check();
            connection.setRequestProperty("Authorization", "Bearer " + snapshot.token);
            connection.setRequestProperty("X-Zephyr-Account-Id", String.valueOf(snapshot.owner));
            if (bytes != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                snapshot.check();
                try (OutputStream out = connection.getOutputStream()) {
                    for (int offset = 0; offset < bytes.length; offset += 4096) {
                        snapshot.check();
                        out.write(bytes, offset, Math.min(4096, bytes.length - offset));
                    }
                }
            }
            snapshot.check();
            int status = connection.getResponseCode();
            snapshot.check();
            if (status / 100 != 2) {
                // No server body or exception message reaches logs/Python; they can contain private data.
                if (styleErrors) throw styleError(status, errorCode(connection));
                if (status == 401 || status == 403) throw error(ZephyrAiClient.ERR_UNAUTHORIZED, "memory_not_authorized");
                if (status == 429) throw error(ZephyrAiClient.ERR_RATE_LIMIT, "memory_rate_limited");
                if (status == 409) throw error(ERR_CONSENT, "memory_consent_required");
                if (status == 404) throw error(ERR_UNSUPPORTED, "memory_not_supported");
                if (status == 503) throw error(ZephyrAiClient.ERR_STARTING, "memory_unavailable");
                throw error(ZephyrAiClient.ERR_SERVER, "memory_request_failed");
            }
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
                throw error(ERR_UNSUPPORTED, "memory_invalid_response");
            }
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    snapshot.check();
                    if (response.size() + count > MAX_RESPONSE_BYTES) throw error(ERR_INVALID, "memory_response_too_large");
                    response.write(buffer, 0, count);
                    connection.setReadTimeout(snapshot.remaining());
                }
            }
            JSONObject result = new JSONObject(new String(response.toByteArray(), StandardCharsets.UTF_8));
            if (result.toString().contains(snapshot.token)) throw error(ERR_INVALID, "memory_invalid_response");
            snapshot.check();
            return result;
        } catch (ZephyrAiClient.AiException e) { throw e; }
        catch (Exception e) { throw error(ZephyrAiClient.ERR_NETWORK, "memory_network_error"); }
        finally {
            if (deadline != null) deadline.cancel(false);
            if (connection != null) try { connection.disconnect(); } catch (Exception ignored) { }
        }
    }

    private JSONObject verify(Snapshot snapshot) throws ZephyrAiClient.AiException {
        try {
            JSONObject response = request(snapshot, "GET", "/status", null);
            JSONObject base = verifiedBase(snapshot, response);
            JSONObject sourceLimits = response.getJSONObject("limits"), limits = new JSONObject();
            String[] names = {"maxScopes", "maxMessagesPerScope", "maxBatchMessages", "maxMessageChars", "maxContextMessages", "maxContextChars", "minRetentionDays", "maxRetentionDays"};
            int[] max = {20, 200, 50, 4000, 20, 6000, 90, 90};
            for (int i = 0; i < names.length; i++) limits.put(names[i], integer(sourceLimits, names[i], 1, max[i]));
            if (limits.getInt("minRetentionDays") > limits.getInt("maxRetentionDays")) throw error(ERR_INVALID, "memory_invalid_response");
            JSONObject sourceFeatures = response.optJSONObject("features");
            boolean legacyImport = sourceFeatures != null && sourceFeatures.has("legacyImport") && bool(sourceFeatures, "legacyImport");
            if (legacyImport) limits.put("maxLegacySummaryChars", integer(sourceLimits, "maxLegacySummaryChars", 1, 8000));
            return base.put("limits", limits).put("features", new JSONObject().put("legacyImport", legacyImport))
                    .put("usage", safeUsage(response.opt("usage")));
        } catch (ZephyrAiClient.AiException e) { throw e; }
        catch (Exception e) { throw error(ERR_UNSUPPORTED, "memory_invalid_response"); }
    }

    /**
     * Counters for the memory gauge, copied field by field: only numbers and ISO dates, never text. Any wrong shape
     * gives JSONObject.NULL (the plugin then shows no cloud line) instead of failing the whole status.
     */
    static Object safeUsage(Object raw) {
        try {
            if (!(raw instanceof JSONObject)) return JSONObject.NULL;
            JSONObject usage = (JSONObject) raw, safe = new JSONObject();
            safe.put("scopes", integer(usage, "scopes", 0, 1000000));
            JSONArray source = usage.getJSONArray("chats"), chats = new JSONArray();
            if (source.length() > 100) return JSONObject.NULL;
            for (int i = 0; i < source.length(); i++) {
                JSONObject c = source.getJSONObject(i);
                chats.put(new JSONObject()
                        .put("dialogId", usageDialog(c.get("dialogId")))
                        .put("topicId", c.isNull("topicId") ? 0 : integer(c, "topicId", 0, Integer.MAX_VALUE))
                        .put("messages", integer(c, "messages", 0, 1000000))
                        .put("messagesUntil", usageDate(c.get("messagesUntil")))
                        .put("summaryChars", integer(c, "summaryChars", 0, 1000000))
                        .put("summaryUntil", usageDate(c.get("summaryUntil"))));
            }
            safe.put("chats", chats);
            JSONObject style = usage.getJSONObject("style");
            safe.put("style", new JSONObject().put("examples", integer(style, "examples", 0, 1000000))
                    .put("feedback", integer(style, "feedback", 0, 1000000))
                    .put("factsSelf", integer(style, "factsSelf", 0, 1000000))
                    .put("factsPeer", integer(style, "factsPeer", 0, 1000000)));
            return safe;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }
    private static long usageDialog(Object value) throws Exception {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException();
        return ((Number) value).longValue();
    }
    /** ISO-8601 timestamp (at most 40 chars of digits, - : T Z . + ) or null. */
    private static Object usageDate(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        if (!(value instanceof String)) throw new IllegalArgumentException();
        String date = (String) value;
        if (date.length() > 40 || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}(T[0-9:.]{1,20})?(Z|[+-][0-9]{2}:?[0-9]{2})?")) throw new IllegalArgumentException();
        return date;
    }

    /** Fields shared by the v1 and style status: protocol, verified owner, binding, storage, collection switch. */
    private static JSONObject verifiedBase(Snapshot snapshot, JSONObject response) throws Exception {
        if (integer(response, "protocolVersion", 1, 1) != 1) throw error(ERR_UNSUPPORTED, "memory_not_supported");
        if (!String.valueOf(snapshot.owner).equals(response.optString("ownerId", ""))) {
            throw error(ERR_ACCOUNT, "memory_account_mismatch");
        }
        JSONObject storage = response.getJSONObject("storage");
        JSONObject safeStorage = new JSONObject().put("configured", bool(storage, "configured")).put("ready", bool(storage, "ready"));
        return new JSONObject().put("protocolVersion", API_VERSION).put("ownerId", String.valueOf(snapshot.owner))
                .put("ownerMatch", true).put("binding", snapshot.binding).put("storage", safeStorage)
                .put("collectionEnabled", bool(response, "collectionEnabled"));
    }

    static int integer(JSONObject object, String key, int min, int max) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw error(ERR_INVALID, "memory_invalid_data");
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw error(ERR_INVALID, "memory_invalid_data");
        return (int) number;
    }
    private static boolean bool(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Boolean)) throw error(ERR_INVALID, "memory_invalid_response");
        return (Boolean) value;
    }
    private static String text(JSONObject object, String key, int max, boolean empty) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String)) throw error(ERR_INVALID, "memory_invalid_data");
        String string = (String) value;
        if ((!empty && string.trim().isEmpty()) || string.length() > max || string.indexOf('\0') >= 0) throw error(ERR_INVALID, "memory_invalid_data");
        return string;
    }
    private static void keys(JSONObject object, String... allowed) throws Exception {
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        for (Iterator<String> it = object.keys(); it.hasNext();) if (!names.contains(it.next())) throw error(ERR_INVALID, "memory_invalid_data");
    }
    private static String sender(JSONObject message) throws Exception {
        String value = text(message, "senderId", 20, false);
        if (!value.matches("-?[1-9][0-9]{0,18}") || Long.parseLong(value) == 0) throw error(ERR_INVALID, "memory_invalid_data");
        return value;
    }

    static JSONObject validateMessages(JSONArray array, long owner, long dialogId, int countMax, int charMax) throws Exception {
        if (array.length() < 1 || array.length() > countMax) throw error(ERR_INVALID, "memory_invalid_data");
        Set<Integer> seen = new HashSet<>();
        JSONArray safe = new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject message = array.getJSONObject(i);
            keys(message, "messageId", "senderId", "speaker", "text", "date");
            int id = integer(message, "messageId", 1, Integer.MAX_VALUE);
            if (!seen.add(id)) throw error(ERR_INVALID, "memory_invalid_data");
            String from = sender(message), role = text(message, "speaker", 5, false);
            if (!role.equals("owner") && !role.equals("other")) throw error(ERR_INVALID, "memory_invalid_data");
            if (role.equals("owner") != from.equals(String.valueOf(owner))
                    || (dialogId > 0 && role.equals("other") && !from.equals(String.valueOf(dialogId)))) {
                throw error(ERR_INVALID, "memory_invalid_data");
            }
            String date = text(message, "date", 40, false);
            if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.+(Z|[+-][0-9]{2}:[0-9]{2})")) throw error(ERR_INVALID, "memory_invalid_data");
            safe.put(new JSONObject().put("messageId", id).put("senderId", from).put("speaker", role)
                    .put("text", text(message, "text", charMax, false)).put("date", date));
        }
        return new JSONObject().put("messages", safe);
    }

    JSONObject status(int account, int timeout, Guard allowed) throws ZephyrAiClient.AiException {
        Snapshot snapshot = new Snapshot(account, timeout, allowed);
        JSONObject result = verify(snapshot);
        snapshot.check();
        return result;
    }

    JSONObject operate(int account, String operation, long dialogId, int topicId, JSONObject body, int timeout,
                       String expectedBinding, Guard allowed) throws ZephyrAiClient.AiException {
        if (dialogId == 0 || topicId < 0 || !Arrays.asList("consent", "write", "context", "delete", "import").contains(operation)
                || (operation.equals("import") && (dialogId < 0 || topicId != 0))) {
            throw error(ERR_INVALID, "memory_invalid_scope");
        }
        Snapshot snapshot = new Snapshot(account, timeout, allowed);
        expected(snapshot, expectedBinding); // Check BEFORE a handshake or any data upload.
        try {
            // Validate all data before networking; validate again against the server's tighter bounds below.
            JSONObject safe = null;
            if (operation.equals("write")) {
                keys(body, "messages"); safe = validateMessages(body.getJSONArray("messages"), snapshot.owner, dialogId, 50, 4000);
            } else if (operation.equals("consent")) {
                keys(body, "retentionDays"); safe = new JSONObject().put("retentionDays", integer(body, "retentionDays", 1, 90));
            } else if (operation.equals("context")) {
                keys(body, "query"); safe = new JSONObject().put("query", text(body, "query", 200, true));
            } else if (operation.equals("import")) {
                keys(body, "summary", "throughId", "revision", "updatedAt");
                String updatedAt = text(body, "updatedAt", 40, false);
                if (!updatedAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.+(Z|[+-][0-9]{2}:[0-9]{2})")) throw error(ERR_INVALID, "memory_invalid_data");
                safe = new JSONObject().put("summary", text(body, "summary", 8000, false))
                        .put("throughId", integer(body, "throughId", 0, Integer.MAX_VALUE))
                        .put("revision", integer(body, "revision", 1, Integer.MAX_VALUE)).put("updatedAt", updatedAt);
            } else if (body != null) throw error(ERR_INVALID, "memory_invalid_data");
            JSONObject status = verify(snapshot);
            JSONObject storage = status.getJSONObject("storage"), limits = status.getJSONObject("limits");
            if (!storage.getBoolean("configured")) throw error(ZephyrAiClient.ERR_NOT_CONFIGURED, "memory_not_configured");
            if (!storage.getBoolean("ready")) throw error(ZephyrAiClient.ERR_STARTING, "memory_unavailable");
            if (!operation.equals("delete") && !status.getBoolean("collectionEnabled")) throw error(ERR_DISABLED, "memory_disabled");
            if (operation.equals("write")) safe = validateMessages(safe.getJSONArray("messages"), snapshot.owner, dialogId,
                    limits.getInt("maxBatchMessages"), limits.getInt("maxMessageChars"));
            if (operation.equals("consent")) integer(safe, "retentionDays", limits.getInt("minRetentionDays"), limits.getInt("maxRetentionDays"));
            if (operation.equals("import") && (!status.getJSONObject("features").getBoolean("legacyImport")
                    || safe.getString("summary").length() > limits.getInt("maxLegacySummaryChars"))) {
                throw error(ERR_UNSUPPORTED, "memory_import_not_supported");
            }
            snapshot.check(); expected(snapshot, expectedBinding);
            String path = "/chats/" + dialogId + "/topics/" + topicId;
            String method = operation.equals("write") ? "PUT" : operation.equals("delete") ? "DELETE" : "POST";
            if (!operation.equals("delete")) path += operation.equals("write") ? "/messages" : "/" + operation;
            JSONObject response = request(snapshot, method, path, safe);
            JSONObject result;
            if (operation.equals("write")) {
                result = new JSONObject().put("accepted", integer(response, "accepted", 0, safe.getJSONArray("messages").length()));
            } else if (operation.equals("consent")) {
                if (!bool(response, "enabled") || integer(response, "retentionDays", 1, 90) != safe.getInt("retentionDays")) throw error(ERR_INVALID, "memory_invalid_response");
                result = new JSONObject().put("enabled", true).put("retentionDays", safe.getInt("retentionDays"))
                        .put("maxMessages", integer(response, "maxMessages", 1, 200));
            } else if (operation.equals("delete")) {
                if (bool(response, "enabled")) throw error(ERR_INVALID, "memory_invalid_response");
                result = new JSONObject().put("enabled", false).put("deleted", bool(response, "deleted"));
            } else if (operation.equals("import")) {
                String storedAt = text(response, "updatedAt", 40, false);
                if (!storedAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.+(Z|[+-][0-9]{2}:[0-9]{2})")) throw error(ERR_INVALID, "memory_invalid_response");
                result = new JSONObject().put("imported", bool(response, "imported"))
                        .put("revision", integer(response, "revision", 1, Integer.MAX_VALUE))
                        .put("throughId", integer(response, "throughId", 0, Integer.MAX_VALUE)).put("updatedAt", storedAt);
                if (response.has("reason")) {
                    String reason = response.optString("reason");
                    if (!Arrays.asList("unchanged", "stale_summary", "revision_conflict").contains(reason)) throw error(ERR_INVALID, "memory_invalid_response");
                    result.put("reason", reason);
                }
            } else {
                if (!"untrusted_chat_history".equals(response.optString("kind")) || bool(response, "instructions")
                        || !String.valueOf(dialogId).equals(response.optString("dialogId"))
                        || integer(response, "topicId", 0, Integer.MAX_VALUE) != topicId) throw error(ERR_INVALID, "memory_invalid_response");
                JSONArray array = response.getJSONArray("messages");
                if (array.length() > limits.getInt("maxContextMessages")) throw error(ERR_INVALID, "memory_response_too_large");
                JSONArray validated = array.length() == 0 ? new JSONArray() : validateMessages(array, snapshot.owner, dialogId,
                        limits.getInt("maxContextMessages"), limits.getInt("maxMessageChars")).getJSONArray("messages");
                int chars = 0;
                for (int i = 0; i < validated.length(); i++) chars += validated.getJSONObject(i).getString("text").length();
                if (chars > limits.getInt("maxContextChars")) throw error(ERR_INVALID, "memory_response_too_large");
                JSONArray notes = response.optJSONArray("notes"), safeNotes = new JSONArray();
                if (notes != null) {
                    if (notes.length() > 1 || (notes.length() > 0 && (dialogId < 0 || topicId != 0
                            || !status.getJSONObject("features").getBoolean("legacyImport")))) throw error(ERR_INVALID, "memory_invalid_response");
                    for (int i = 0; i < notes.length(); i++) {
                        JSONObject note = notes.getJSONObject(i);
                        keys(note, "kind", "source", "speakers", "text", "throughId", "revision", "updatedAt");
                        if (!"untrusted_legacy_summary".equals(note.optString("kind"))
                                || !"local_autoreply_summary".equals(note.optString("source"))
                                || !"mixed_unknown".equals(note.optString("speakers"))) throw error(ERR_INVALID, "memory_invalid_response");
                        String date = text(note, "updatedAt", 40, false);
                        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.+(Z|[+-][0-9]{2}:[0-9]{2})")) throw error(ERR_INVALID, "memory_invalid_response");
                        safeNotes.put(new JSONObject().put("kind", "untrusted_legacy_summary")
                                .put("source", "local_autoreply_summary").put("speakers", "mixed_unknown")
                                .put("text", text(note, "text", 8000, false)).put("throughId", integer(note, "throughId", 0, Integer.MAX_VALUE))
                                .put("revision", integer(note, "revision", 1, Integer.MAX_VALUE)).put("updatedAt", date));
                    }
                }
                result = new JSONObject().put("kind", "untrusted_chat_history").put("instructions", false)
                        .put("chatType", dialogId > 0 ? "private" : "group").put("dialogId", String.valueOf(dialogId))
                        .put("topicId", topicId).put("messages", validated).put("bodyCharacters", chars).put("notes", safeNotes);
            }
            snapshot.check(); expected(snapshot, expectedBinding);
            return result;
        } catch (ZephyrAiClient.AiException e) { throw e; }
        catch (Exception e) { throw error(ERR_INVALID, "memory_invalid_data"); }
    }

    // ------------------------------------------------------------------ style layer (memory stage 2)

    /** Server error codes are read from a bounded error body and mapped to fixed keys; nothing else of the body is kept. */
    private static String errorCode(HttpURLConnection connection) {
        try (InputStream in = connection.getErrorStream()) {
            if (in == null) return "";
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (body.size() + count > MAX_ERROR_BYTES) return "";
                body.write(buffer, 0, count);
            }
            JSONObject error = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8)).optJSONObject("error");
            String code = error == null ? "" : error.optString("code", "");
            return code.matches("[a-z_]{1,40}") ? code : "";
        } catch (Exception e) { return ""; }
    }

    static ZephyrAiClient.AiException styleError(int status, String code) {
        switch (status) {
            case 400: return "memory_account_required".equals(code) ? error(ERR_ACCOUNT, "memory_account_required")
                    : error(ERR_INVALID, "memory_invalid_data");
            case 401: return error(ZephyrAiClient.ERR_UNAUTHORIZED, "memory_not_authorized");
            case 403: return "memory_account_mismatch".equals(code) ? error(ERR_ACCOUNT, "memory_account_mismatch")
                    : "telegram_owned_token_required".equals(code) ? error(ZephyrAiClient.ERR_UNAUTHORIZED, "memory_token_not_owned")
                    : error(ZephyrAiClient.ERR_UNAUTHORIZED, "memory_not_authorized");
            case 404: return error(ERR_UNSUPPORTED, "memory_style_not_supported");
            case 413: return error(ERR_INVALID, "memory_request_too_large");
            case 429: return "daily_limit_exceeded".equals(code) || "daily_budget_exceeded".equals(code)
                    ? error(ZephyrAiClient.ERR_RATE_LIMIT, "memory_daily_limit") : error(ZephyrAiClient.ERR_RATE_LIMIT, "memory_rate_limited");
            case 502: return "digest_failed".equals(code) ? error(ZephyrAiClient.ERR_SERVER, "memory_digest_failed")
                    : error(ZephyrAiClient.ERR_SERVER, "memory_request_failed");
            case 503:
                if ("memory_disabled".equals(code)) return error(ERR_DISABLED, "memory_disabled");
                if ("memory_not_configured".equals(code)) return error(ZephyrAiClient.ERR_NOT_CONFIGURED, "memory_not_configured");
                if ("memory_starting".equals(code)) return error(ZephyrAiClient.ERR_STARTING, "memory_starting");
                if ("digest_unavailable".equals(code) || "upstream_not_configured".equals(code) || "upstream_keys_disabled".equals(code)) {
                    return error(ERR_UNSUPPORTED, "memory_digest_unavailable");
                }
                return error(ZephyrAiClient.ERR_STARTING, "memory_unavailable");
            default: return error(ZephyrAiClient.ERR_SERVER, "memory_request_failed");
        }
    }

    /** Bounds a request is validated against: the contract maxima before networking, then the server's advertised ones. */
    static final class StyleLimits {
        final int batch, text, query, searchK, searchChars, factChars, factsK, periodDays, digestChars, retentionDays;
        StyleLimits(int batch, int text, int query, int searchK, int searchChars, int factChars, int factsK, int periodDays,
                    int digestChars, int retentionDays) {
            this.batch = batch; this.text = text; this.query = query; this.searchK = searchK; this.searchChars = searchChars;
            this.factChars = factChars; this.factsK = factsK; this.periodDays = periodDays; this.digestChars = digestChars;
            this.retentionDays = retentionDays;
        }
        static final StyleLimits CONTRACT = new StyleLimits(50, 4000, 2000, 8, 8000, 500, 20, 31, 4000, 365);
        static StyleLimits of(JSONObject style) throws Exception {
            JSONObject l = style.getJSONObject("limits");
            return new StyleLimits(l.getInt("maxBatch"), l.getInt("maxTextChars"), l.getInt("maxQueryChars"), l.getInt("maxSearchK"),
                    l.getInt("maxSearchChars"), l.getInt("maxFactChars"), l.getInt("maxFactsK"), l.getInt("maxPeriodDays"),
                    l.getInt("maxDigestChars"), style.getInt("retentionDays"));
        }
    }

    private static boolean present(JSONObject object, String key) { return object.has(key) && !object.isNull(key); }
    private static String choice(JSONObject object, String key, List<String> allowed) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String) || !allowed.contains(value)) throw error(ERR_INVALID, "memory_invalid_data");
        return (String) value;
    }
    private static double fraction(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw error(ERR_INVALID, "memory_invalid_data");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || number < 0 || number > 1) throw error(ERR_INVALID, "memory_invalid_data");
        return number;
    }
    private static JSONArray rows(JSONObject object, String key, int max) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof JSONArray) || ((JSONArray) value).length() < 1 || ((JSONArray) value).length() > max) {
            throw error(ERR_INVALID, "memory_invalid_data");
        }
        return (JSONArray) value;
    }
    private static String date(JSONObject object, String key) throws Exception {
        String value = text(object, key, 40, false);
        if (!value.matches(DATE_RE)) throw error(ERR_INVALID, "memory_invalid_data");
        return value;
    }
    private static String rowId(JSONObject object) throws Exception {
        String value = text(object, "id", 20, false);
        if (!value.matches("[0-9]{1,20}")) throw error(ERR_INVALID, "memory_invalid_data");
        return value;
    }
    private static void untrusted(JSONObject response, String kind) throws Exception {
        if (!kind.equals(response.optString("kind")) || bool(response, "instructions")) throw error(ERR_INVALID, "memory_invalid_response");
    }

    static String area(String value) throws ZephyrAiClient.AiException {
        if (value == null || !value.matches(AREA_RE)) throw error(ERR_INVALID, "memory_invalid_area");
        return value;
    }

    /** Whitelists one plugin request. Optional fields are omitted (never sent as null); unknown fields are rejected. */
    static JSONObject validateStyle(String operation, String area, JSONObject body, StyleLimits l) throws Exception {
        if (operation.equals("delete_all")) {
            if (area != null || body != null) throw error(ERR_INVALID, "memory_invalid_data");
            return null;
        }
        area(area);
        if (operation.equals("delete_area")) {
            if (body != null) throw error(ERR_INVALID, "memory_invalid_data");
            return null;
        }
        if (body == null) throw error(ERR_INVALID, "memory_invalid_data");
        JSONObject safe = new JSONObject().put("area", area);
        switch (operation) {
            case "style_add": {
                keys(body, "examples");
                JSONArray in = rows(body, "examples", l.batch), out = new JSONArray();
                for (int i = 0; i < in.length(); i++) {
                    JSONObject row = in.getJSONObject(i);
                    keys(row, "contextText", "replyText", "source", "quality");
                    JSONObject item = new JSONObject().put("contextText", text(row, "contextText", l.text, false))
                            .put("replyText", text(row, "replyText", l.text, false)).put("source", choice(row, "source", STYLE_SOURCES));
                    if (present(row, "quality")) item.put("quality", integer(row, "quality", 1, 5));
                    out.put(item);
                }
                return safe.put("examples", out);
            }
            case "style_search":
                keys(body, "query", "k");
                safe.put("query", text(body, "query", l.query, false));
                if (present(body, "k")) safe.put("k", integer(body, "k", 1, l.searchK));
                return safe;
            case "feedback": {
                keys(body, "items");
                JSONArray in = rows(body, "items", l.batch), out = new JSONArray();
                for (int i = 0; i < in.length(); i++) {
                    JSONObject row = in.getJSONObject(i);
                    keys(row, "outcome", "draftText", "finalText", "contextText"); // editDistance is computed by the server
                    String outcome = choice(row, "outcome", STYLE_OUTCOMES);
                    JSONObject item = new JSONObject().put("outcome", outcome);
                    for (String key : new String[]{"draftText", "finalText", "contextText"}) {
                        if (present(row, key)) item.put(key, text(row, key, l.text, false));
                    }
                    boolean draft = item.has("draftText"), last = item.has("finalText");
                    if ((!outcome.equals("owner_first") && !draft) || (outcome.equals("rejected") && last)
                            || ((outcome.equals("edited") || outcome.equals("replaced") || outcome.equals("owner_first")) && !last)) {
                        throw error(ERR_INVALID, "memory_invalid_data");
                    }
                    out.put(item);
                }
                return safe.put("items", out);
            }
            case "facts_add": {
                keys(body, "facts");
                JSONArray in = rows(body, "facts", l.batch), out = new JSONArray();
                for (int i = 0; i < in.length(); i++) {
                    JSONObject row = in.getJSONObject(i);
                    keys(row, "subject", "fact", "confidence", "sourceMsg", "expiresInDays");
                    String subject = choice(row, "subject", STYLE_SUBJECTS);
                    JSONObject item = new JSONObject().put("subject", subject).put("fact", text(row, "fact", l.factChars, false));
                    if (present(row, "confidence")) item.put("confidence", fraction(row, "confidence"));
                    if (present(row, "sourceMsg")) {
                        String source = text(row, "sourceMsg", 128, false);
                        if (!source.matches("[A-Za-z0-9_.:-]{1,128}")) throw error(ERR_INVALID, "memory_invalid_data");
                        item.put("sourceMsg", source);
                    }
                    // Facts about the peer are someone else's data: never longer than the server's retention.
                    if (present(row, "expiresInDays")) {
                        item.put("expiresInDays", integer(row, "expiresInDays", 1, subject.equals("peer") ? l.retentionDays : 3650));
                    }
                    out.put(item);
                }
                return safe.put("facts", out);
            }
            case "facts_search":
                keys(body, "subject", "query", "k");
                safe.put("subject", choice(body, "subject", STYLE_SUBJECTS));
                if (present(body, "query")) safe.put("query", text(body, "query", 200, true));
                if (present(body, "k")) safe.put("k", integer(body, "k", 1, l.factsK));
                return safe;
            case "digest":
                keys(body, "generate", "periodDays");
                if (present(body, "generate") && !(body.get("generate") instanceof Boolean)) throw error(ERR_INVALID, "memory_invalid_data");
                boolean generate = present(body, "generate") && body.getBoolean("generate");
                safe.put("generate", generate);
                if (present(body, "periodDays")) safe.put("periodDays", integer(body, "periodDays", 1, l.periodDays));
                return safe;
            default:
                throw error(ERR_INVALID, "memory_invalid_scope");
        }
    }

    private static JSONObject digestView(JSONObject digest, String area, StyleLimits l) throws Exception {
        untrusted(digest, "untrusted_digest");
        if (!area.equals(digest.optString("area"))) throw error(ERR_INVALID, "memory_invalid_response");
        return new JSONObject().put("kind", "untrusted_digest").put("instructions", false).put("id", rowId(digest))
                .put("area", area).put("periodStart", date(digest, "periodStart")).put("periodEnd", date(digest, "periodEnd"))
                .put("text", text(digest, "text", l.digestChars, false)).put("createdAt", date(digest, "createdAt"))
                .put("expiresAt", date(digest, "expiresAt"));
    }

    /** Rebuilds the server answer from known fields only, checked against the request (area, subject, k) and limits. */
    static JSONObject styleResult(String operation, String area, JSONObject safe, JSONObject r, StyleLimits l) throws Exception {
        switch (operation) {
            case "style_add": {
                int n = safe.getJSONArray("examples").length();
                return new JSONObject().put("accepted", integer(r, "accepted", 0, n)).put("inserted", integer(r, "inserted", 0, n))
                        .put("embedded", integer(r, "embedded", 0, n)).put("embedding", choice(r, "embedding", STYLE_EMBEDDING));
            }
            case "style_search": {
                untrusted(r, "untrusted_style_examples");
                String mode = r.optString("mode");
                if (!area.equals(r.optString("area")) || (!mode.equals("hybrid") && !mode.equals("text"))) {
                    throw error(ERR_INVALID, "memory_invalid_response");
                }
                JSONArray in = r.getJSONArray("examples"), out = new JSONArray();
                if (in.length() > Math.min(safe.optInt("k", 5), l.searchK)) throw error(ERR_INVALID, "memory_response_too_large");
                int chars = 0;
                for (int i = 0; i < in.length(); i++) {
                    JSONObject row = in.getJSONObject(i);
                    Object score = row.get("score");
                    double value = score instanceof Number ? ((Number) score).doubleValue() : Double.NaN;
                    if (Double.isNaN(value) || Double.isInfinite(value) || value < -1 || value > 2) throw error(ERR_INVALID, "memory_invalid_response");
                    JSONObject item = new JSONObject().put("id", rowId(row)).put("contextText", text(row, "contextText", l.text, false))
                            .put("replyText", text(row, "replyText", l.text, false)).put("source", choice(row, "source", STYLE_SOURCES))
                            .put("quality", integer(row, "quality", 1, 5)).put("score", value).put("createdAt", date(row, "createdAt"));
                    chars += item.getString("contextText").length() + item.getString("replyText").length();
                    out.put(item);
                }
                if (chars > l.searchChars) throw error(ERR_INVALID, "memory_response_too_large");
                return new JSONObject().put("kind", "untrusted_style_examples").put("instructions", false).put("area", area)
                        .put("mode", mode).put("examples", out).put("textCharacters", chars)
                        .put("embedding", choice(r, "embedding", STYLE_EMBEDDING));
            }
            case "feedback": {
                int n = safe.getJSONArray("items").length();
                JSONArray in = r.getJSONArray("items"), out = new JSONArray();
                if (in.length() > n) throw error(ERR_INVALID, "memory_invalid_response");
                for (int i = 0; i < in.length(); i++) {
                    JSONObject row = in.getJSONObject(i);
                    out.put(new JSONObject().put("outcome", choice(row, "outcome", STYLE_OUTCOMES)).put("editDistance",
                            present(row, "editDistance") ? (Object) integer(row, "editDistance", 0, l.text) : JSONObject.NULL));
                }
                return new JSONObject().put("accepted", integer(r, "accepted", 0, n)).put("styleExamples", integer(r, "styleExamples", 0, n))
                        .put("embedded", integer(r, "embedded", 0, n)).put("items", out).put("embedding", choice(r, "embedding", STYLE_EMBEDDING));
            }
            case "facts_add": {
                int n = safe.getJSONArray("facts").length();
                return new JSONObject().put("accepted", integer(r, "accepted", 0, n)).put("inserted", integer(r, "inserted", 0, n));
            }
            case "facts_search": {
                untrusted(r, "untrusted_facts");
                String subject = safe.getString("subject");
                if (!area.equals(r.optString("area")) || !subject.equals(r.optString("subject"))) throw error(ERR_INVALID, "memory_invalid_response");
                JSONArray in = r.getJSONArray("facts"), out = new JSONArray();
                if (in.length() > Math.min(safe.optInt("k", 10), l.factsK)) throw error(ERR_INVALID, "memory_response_too_large");
                for (int i = 0; i < in.length(); i++) {
                    JSONObject row = in.getJSONObject(i);
                    if (!subject.equals(row.optString("subject"))) throw error(ERR_INVALID, "memory_invalid_response");
                    Object source = JSONObject.NULL;
                    if (present(row, "sourceMsg")) {
                        String value = text(row, "sourceMsg", 128, false);
                        if (!value.matches("[A-Za-z0-9_.:-]{1,128}")) throw error(ERR_INVALID, "memory_invalid_response");
                        source = value;
                    }
                    out.put(new JSONObject().put("id", rowId(row)).put("subject", subject).put("fact", text(row, "fact", l.factChars, false))
                            .put("confidence", fraction(row, "confidence")).put("sourceMsg", source).put("createdAt", date(row, "createdAt"))
                            .put("expiresAt", present(row, "expiresAt") ? (Object) date(row, "expiresAt") : JSONObject.NULL));
                }
                return new JSONObject().put("kind", "untrusted_facts").put("instructions", false).put("area", area)
                        .put("subject", subject).put("facts", out);
            }
            case "digest": {
                boolean generated = bool(r, "generated");
                JSONObject result = new JSONObject().put("generated", generated);
                if (present(r, "reason")) {
                    if (generated || !"no_data".equals(r.optString("reason"))) throw error(ERR_INVALID, "memory_invalid_response");
                    result.put("reason", "no_data");
                }
                if (generated && !present(r, "digest")) throw error(ERR_INVALID, "memory_invalid_response");
                return result.put("digest", present(r, "digest") ? digestView(r.getJSONObject("digest"), area, l) : JSONObject.NULL);
            }
            case "delete_area":
            case "delete_all": {
                JSONObject deleted = r.getJSONObject("deleted"), counts = new JSONObject();
                for (String key : new String[]{"examples", "feedback", "facts", "digests"}) counts.put(key, integer(deleted, key, 0, Integer.MAX_VALUE));
                boolean all = operation.equals("delete_all");
                if (all ? !r.isNull("area") : !area.equals(r.optString("area"))) throw error(ERR_INVALID, "memory_invalid_response");
                return new JSONObject().put("deleted", counts).put("area", all ? JSONObject.NULL : area);
            }
            default:
                throw error(ERR_INVALID, "memory_invalid_scope");
        }
    }

    private JSONObject verifyStyle(Snapshot snapshot) throws ZephyrAiClient.AiException {
        try {
            JSONObject response = request(snapshot, "GET", "/status", null, MAX_REQUEST_BYTES, true);
            JSONObject safe = verifiedBase(snapshot, response);
            JSONObject features = response.optJSONObject("features");
            boolean supported = features != null && features.has("style") && bool(features, "style");
            safe.put("features", new JSONObject().put("style", supported));
            if (!supported) return safe.put("style", JSONObject.NULL);
            JSONObject style = response.getJSONObject("style"), sourceLimits = style.getJSONObject("limits"), limits = new JSONObject();
            String[] names = {"maxBatch", "maxTextChars", "embedChars", "maxQueryChars", "maxSearchK", "maxSearchChars", "maxFactChars",
                    "maxFactsK", "maxPeriodDays", "maxDigestChars", "maxExamplesPerOwner", "maxFeedbackPerOwner", "maxFactsPerOwner"};
            int[] max = {50, 4000, 2000, 2000, 8, 8000, 500, 20, 31, 4000, 1000000, 1000000, 1000000};
            for (int i = 0; i < names.length; i++) limits.put(names[i], integer(sourceLimits, names[i], 1, max[i]));
            Object storage = style.isNull("embeddingStorage") ? JSONObject.NULL : style.get("embeddingStorage");
            if (storage != JSONObject.NULL && !"pgvector".equals(storage) && !"real[]".equals(storage)) {
                throw error(ERR_INVALID, "memory_invalid_response");
            }
            return safe.put("style", new JSONObject().put("ready", bool(style, "ready")).put("embeddings", bool(style, "embeddings"))
                    .put("embeddingStorage", storage).put("retentionDays", integer(style, "retentionDays", 1, 365)).put("limits", limits));
        } catch (ZephyrAiClient.AiException e) { throw e; }
        catch (Exception e) { throw error(ERR_UNSUPPORTED, "memory_invalid_response"); }
    }

    JSONObject styleStatus(int account, int timeout, Guard allowed) throws ZephyrAiClient.AiException {
        Snapshot snapshot = new Snapshot(account, timeout, allowed);
        JSONObject result = verifyStyle(snapshot);
        snapshot.check();
        return result;
    }

    /**
     * One style-layer operation for the verified owner of {@code account}. Same rules as v1: binding checked before any
     * I/O, a fresh status handshake, input validated before networking and again against the advertised limits,
     * fixed paths only, no retries. Deletion works with collection disabled; the v1 chat memory is never touched.
     */
    JSONObject operateStyle(int account, String operation, String area, JSONObject body, int timeout,
                            String expectedBinding, Guard allowed) throws ZephyrAiClient.AiException {
        if (operation == null || !STYLE_OPERATIONS.contains(operation)) throw error(ERR_INVALID, "memory_invalid_scope");
        boolean generate = operation.equals("digest") && body != null && Boolean.TRUE.equals(body.opt("generate"));
        Snapshot snapshot = new Snapshot(account, timeout, generate ? 60 : 30, allowed);
        expected(snapshot, expectedBinding); // Check BEFORE a handshake or any data upload.
        try {
            validateStyle(operation, area, body, StyleLimits.CONTRACT);
            JSONObject status = verifyStyle(snapshot);
            JSONObject storage = status.getJSONObject("storage");
            if (!storage.getBoolean("configured")) throw error(ZephyrAiClient.ERR_NOT_CONFIGURED, "memory_not_configured");
            if (!storage.getBoolean("ready")) throw error(ZephyrAiClient.ERR_STARTING, "memory_unavailable");
            if (!status.getJSONObject("features").getBoolean("style")) throw error(ERR_UNSUPPORTED, "memory_style_not_supported");
            JSONObject style = status.getJSONObject("style");
            if (!style.getBoolean("ready")) throw error(ZephyrAiClient.ERR_STARTING, "memory_starting");
            boolean deletion = operation.startsWith("delete");
            if (!deletion && !status.getBoolean("collectionEnabled")) throw error(ERR_DISABLED, "memory_disabled");
            StyleLimits limits = StyleLimits.of(style);
            JSONObject safe = validateStyle(operation, area, body, limits);
            snapshot.check(); expected(snapshot, expectedBinding);
            String path;
            switch (operation) {
                case "style_add": path = "/style"; break;
                case "style_search": path = "/style/search"; break;
                case "feedback": path = "/feedback"; break;
                case "facts_add": path = "/facts"; break;
                case "facts_search": path = "/facts/search"; break;
                case "digest": path = "/digest"; break;
                case "delete_area": path = "/area/" + URLEncoder.encode(area, "UTF-8"); break;
                default: path = "/areas"; break;
            }
            boolean bulk = operation.equals("style_add") || operation.equals("feedback");
            JSONObject response = request(snapshot, deletion ? "DELETE" : "POST", path, safe,
                    bulk ? MAX_STYLE_BULK_BYTES : MAX_REQUEST_BYTES, true);
            JSONObject result = styleResult(operation, area, safe, response, limits);
            snapshot.check(); expected(snapshot, expectedBinding);
            return result;
        } catch (ZephyrAiClient.AiException e) { throw e; }
        catch (Exception e) { throw error(ERR_INVALID, "memory_invalid_data"); }
    }

    public static JSONObject styleStatusBlocking(int account, int timeout, Guard allowed) throws ZephyrAiClient.AiException {
        return DEFAULT.styleStatus(account, timeout, allowed);
    }
    public static JSONObject styleRequestBlocking(int account, String operation, String area, JSONObject body, int timeout,
                                                  String expectedBinding, Guard allowed) throws ZephyrAiClient.AiException {
        return DEFAULT.operateStyle(account, operation, area, body, timeout, expectedBinding, allowed);
    }

    public static JSONObject statusBlocking(int account, int timeout, Guard allowed) throws ZephyrAiClient.AiException {
        return DEFAULT.status(account, timeout, allowed);
    }
    public static JSONObject requestBlocking(int account, String operation, long dialogId, int topicId, JSONObject body,
                                            int timeout, String expectedBinding, Guard allowed) throws ZephyrAiClient.AiException {
        return DEFAULT.operate(account, operation, dialogId, topicId, body, timeout, expectedBinding, allowed);
    }
}
