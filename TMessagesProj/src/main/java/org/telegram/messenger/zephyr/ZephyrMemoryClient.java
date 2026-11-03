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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
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
        final int account, selected;
        final long owner, deadline;
        final String base, token, binding;
        final Guard allowed;
        volatile boolean expired;
        Snapshot(int account, int timeout, Guard allowed) throws ZephyrAiClient.AiException {
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
            this.deadline = System.nanoTime() + Math.max(1, Math.min(30, timeout)) * 1000000000L;
            check();
        }
        int remaining() throws ZephyrAiClient.AiException {
            long ms = (deadline - System.nanoTime()) / 1000000L;
            if (expired || ms <= 0) throw error(ZephyrAiClient.ERR_NETWORK, "memory_timeout");
            return (int) Math.min(30000, ms);
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
        snapshot.check();
        byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes != null && bytes.length > MAX_REQUEST_BYTES) throw error(ERR_INVALID, "memory_request_too_large");
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
            if (integer(response, "protocolVersion", 1, 1) != 1) throw error(ERR_UNSUPPORTED, "memory_not_supported");
            if (!String.valueOf(snapshot.owner).equals(response.optString("ownerId", ""))) {
                throw error(ERR_ACCOUNT, "memory_account_mismatch");
            }
            JSONObject storage = response.getJSONObject("storage");
            JSONObject safeStorage = new JSONObject().put("configured", bool(storage, "configured")).put("ready", bool(storage, "ready"));
            JSONObject sourceLimits = response.getJSONObject("limits"), limits = new JSONObject();
            String[] names = {"maxScopes", "maxMessagesPerScope", "maxBatchMessages", "maxMessageChars", "maxContextMessages", "maxContextChars", "minRetentionDays", "maxRetentionDays"};
            int[] max = {20, 200, 50, 4000, 20, 6000, 90, 90};
            for (int i = 0; i < names.length; i++) limits.put(names[i], integer(sourceLimits, names[i], 1, max[i]));
            if (limits.getInt("minRetentionDays") > limits.getInt("maxRetentionDays")) throw error(ERR_INVALID, "memory_invalid_response");
            JSONObject sourceFeatures = response.optJSONObject("features");
            boolean legacyImport = sourceFeatures != null && sourceFeatures.has("legacyImport") && bool(sourceFeatures, "legacyImport");
            if (legacyImport) limits.put("maxLegacySummaryChars", integer(sourceLimits, "maxLegacySummaryChars", 1, 8000));
            return new JSONObject().put("protocolVersion", API_VERSION).put("ownerId", String.valueOf(snapshot.owner))
                    .put("ownerMatch", true).put("binding", snapshot.binding).put("storage", safeStorage)
                    .put("collectionEnabled", bool(response, "collectionEnabled")).put("limits", limits)
                    .put("features", new JSONObject().put("legacyImport", legacyImport));
        } catch (ZephyrAiClient.AiException e) { throw e; }
        catch (Exception e) { throw error(ERR_UNSUPPORTED, "memory_invalid_response"); }
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

    public static JSONObject statusBlocking(int account, int timeout, Guard allowed) throws ZephyrAiClient.AiException {
        return DEFAULT.status(account, timeout, allowed);
    }
    public static JSONObject requestBlocking(int account, String operation, long dialogId, int topicId, JSONObject body,
                                            int timeout, String expectedBinding, Guard allowed) throws ZephyrAiClient.AiException {
        return DEFAULT.operate(account, operation, dialogId, topicId, body, timeout, expectedBinding, allowed);
    }
}
