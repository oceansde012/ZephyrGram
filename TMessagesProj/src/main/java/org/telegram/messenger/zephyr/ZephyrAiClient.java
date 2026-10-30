package org.telegram.messenger.zephyr;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal client for the ZephyrGram-AI server (OpenAI-compatible /v1/chat/completions with SSE, /v1/models).
 * All callbacks are delivered on the main thread. The token is only ever put into the Authorization header
 * and is never logged.
 */
public class ZephyrAiClient {

    public static final int ERR_NOT_CONFIGURED = 1;
    public static final int ERR_UNAUTHORIZED = 2;
    public static final int ERR_RATE_LIMIT = 3;
    public static final int ERR_STARTING = 4;
    public static final int ERR_NETWORK = 5;
    public static final int ERR_SERVER = 6;
    /** Admin API missing on the server (404). */
    public static final int ERR_ADMIN_UNAVAILABLE = 7;

    public interface StreamListener {
        void onDelta(String text);

        void onDone();

        void onError(int code, String message);
    }

    public interface ModelsListener {
        void onModels(List<String> models);

        void onError(int code, String message);
    }

    /** Handle returned by a running request. */
    public static class Call {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        public void cancel() {
            cancelled = true;
            final HttpURLConnection c = connection;
            if (c != null) {
                // disconnect() may touch the network on some stacks: keep it off the main thread.
                EXECUTOR.execute(() -> {
                    try {
                        c.disconnect();
                    } catch (Throwable ignore) {
                    }
                });
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static class ApiException extends IOException {
        final int code;

        ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** base + "/v1" + path, tolerating a base that already ends with /v1. */
    private static String endpoint(String path) {
        String base = ZephyrConfig.getAiServerUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        return base + (base.endsWith("/v1") ? "" : "/v1") + path;
    }

    /** Logs only the operation, error class and numeric codes: never the token, admin secret, prompt or reply text. */
    private static void logApiError(String op, ApiException e) {
        FileLog.w("ZephyrAI " + op + ": api error code=" + e.code);
    }

    private static void logFailure(String op, Throwable e) {
        FileLog.e("ZephyrAI " + op + " failed: " + e.getClass().getName());
    }

    private static HttpURLConnection open(String method, String path, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint(path)).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", method.equals("GET") ? "application/json" : "text/event-stream");
        c.setRequestProperty("User-Agent", "ZephyrGram");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < (1 << 20)) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static ApiException httpError(HttpURLConnection c, int status) {
        String msg = "";
        try {
            JSONObject o = new JSONObject(readAll(c.getErrorStream()));
            JSONObject e = o.optJSONObject("error");
            msg = e != null ? e.optString("message", "") : "";
        } catch (Throwable ignore) {
        }
        int code;
        if (status == 401 || status == 403) {
            code = ERR_UNAUTHORIZED;
        } else if (status == 429) {
            code = ERR_RATE_LIMIT;
        } else if (status == 503) {
            code = ERR_STARTING;
        } else {
            code = ERR_SERVER;
        }
        FileLog.w("ZephyrAI HTTP status=" + status);
        return new ApiException(code, msg.isEmpty() ? ("HTTP " + status) : msg);
    }

    private static boolean notConfigured() {
        return ZephyrConfig.getAiServerUrl().isEmpty() || ZephyrConfig.getAiToken().isEmpty();
    }

    public static Call listModels(ModelsListener listener) {
        final Call call = new Call();
        if (notConfigured()) {
            MAIN.post(() -> listener.onError(ERR_NOT_CONFIGURED, ""));
            return call;
        }
        EXECUTOR.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = open("GET", "/models", ZephyrConfig.getAiToken());
                call.connection = c;
                int status = c.getResponseCode();
                if (status != 200) {
                    throw httpError(c, status);
                }
                JSONArray data = new JSONObject(readAll(c.getInputStream())).optJSONArray("data");
                final ArrayList<String> ids = new ArrayList<>();
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject m = data.optJSONObject(i);
                        String id = m != null ? m.optString("id", "") : "";
                        if (!id.isEmpty()) {
                            ids.add(id);
                        }
                    }
                }
                if (!call.cancelled) {
                    MAIN.post(() -> listener.onModels(ids));
                }
            } catch (ApiException e) {
                if (!call.cancelled) {
                    logApiError("listModels", e);
                    MAIN.post(() -> listener.onError(e.code, e.getMessage()));
                }
            } catch (Throwable e) {
                if (!call.cancelled) {
                    logFailure("listModels", e);
                    final String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    MAIN.post(() -> listener.onError(ERR_NETWORK, m));
                }
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        });
        return call;
    }

    /** Streams a chat completion. messages: array of {role, content}. Model/temperature come from ZephyrConfig. */
    public static Call chat(JSONArray messages, StreamListener listener) {
        final Call call = new Call();
        if (notConfigured()) {
            MAIN.post(() -> listener.onError(ERR_NOT_CONFIGURED, ""));
            return call;
        }
        final String model = ZephyrConfig.getAiModel();
        final double temperature = ZephyrConfig.getAiTemperaturePct() / 100.0;
        EXECUTOR.execute(() -> {
            HttpURLConnection c = null;
            try {
                JSONObject body = new JSONObject();
                if (!model.isEmpty()) {
                    body.put("model", model);
                }
                body.put("messages", messages);
                body.put("stream", true);
                body.put("temperature", temperature);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

                c = open("POST", "/chat/completions", ZephyrConfig.getAiToken());
                call.connection = c;
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
                int status = c.getResponseCode();
                if (status != 200) {
                    throw httpError(c, status);
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (!call.cancelled && (line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    JSONObject o;
                    try {
                        o = new JSONObject(data);
                    } catch (Throwable parse) {
                        continue;
                    }
                    JSONObject err = o.optJSONObject("error");
                    if (err != null) {
                        throw new ApiException(ERR_SERVER, err.optString("message", "error"));
                    }
                    JSONArray choices = o.optJSONArray("choices");
                    if (choices == null || choices.length() == 0 || choices.optJSONObject(0) == null) {
                        continue;
                    }
                    JSONObject delta = choices.optJSONObject(0).optJSONObject("delta");
                    final String piece = delta == null ? "" : delta.optString("content", "");
                    if (!piece.isEmpty() && !piece.equals("null")) {
                        MAIN.post(() -> {
                            if (!call.cancelled) {
                                listener.onDelta(piece);
                            }
                        });
                    }
                }
                if (!call.cancelled) {
                    MAIN.post(() -> {
                        if (!call.cancelled) {
                            listener.onDone();
                        }
                    });
                }
            } catch (ApiException e) {
                if (!call.cancelled) {
                    logApiError("chat", e);
                    MAIN.post(() -> listener.onError(e.code, e.getMessage()));
                }
            } catch (Throwable e) {
                if (!call.cancelled) {
                    logFailure("chat", e);
                    final String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    MAIN.post(() -> listener.onError(ERR_NETWORK, m));
                }
            } finally {
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Throwable ignore) {
                    }
                }
            }
        });
        return call;
    }

    /** Failure of {@link #completeBlocking}; code is one of the ERR_* constants. */
    public static class AiException extends IOException {
        public final int code;

        public AiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * Blocking, non-streaming chat completion for plugins. Must NOT run on the UI thread.
     * model null/empty and NaN temperature fall back to ZephyrConfig. The token never leaves this class.
     */
    public static String completeBlocking(JSONArray messages, String model, double temperature, int timeoutSec) throws AiException {
        if (notConfigured()) {
            throw new AiException(ERR_NOT_CONFIGURED, "AI is not configured (server URL / token) in ZephyrGram settings");
        }
        if (model == null || model.isEmpty()) {
            model = ZephyrConfig.getAiModel();
        }
        if (Double.isNaN(temperature)) {
            temperature = ZephyrConfig.getAiTemperaturePct() / 100.0;
        }
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            if (!model.isEmpty()) {
                body.put("model", model);
            }
            body.put("messages", messages);
            body.put("stream", false);
            body.put("temperature", temperature);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c = open("POST", "/chat/completions", ZephyrConfig.getAiToken());
            c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(Math.min(20000, timeoutSec * 1000));
            c.setReadTimeout(timeoutSec * 1000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int status = c.getResponseCode();
            if (status != 200) {
                ApiException e = httpError(c, status);
                throw new AiException(e.code, e.getMessage());
            }
            JSONObject o = new JSONObject(readAll(c.getInputStream()));
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                throw new AiException(ERR_SERVER, err.optString("message", "error"));
            }
            JSONArray choices = o.optJSONArray("choices");
            JSONObject first = choices == null ? null : choices.optJSONObject(0);
            JSONObject message = first == null ? null : first.optJSONObject("message");
            String content = message == null ? "" : message.optString("content", "");
            if (content.equals("null")) {
                content = "";
            }
            if (content.isEmpty()) {
                throw new AiException(ERR_SERVER, "Empty response from AI server");
            }
            return content;
        } catch (AiException e) {
            FileLog.w("ZephyrAI completeBlocking: error code=" + e.code);
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            FileLog.w("ZephyrAI completeBlocking: timeout");
            throw new AiException(ERR_NETWORK, "AI request timed out");
        } catch (Throwable e) {
            logFailure("completeBlocking", e);
            throw new AiException(ERR_NETWORK, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ owner admin API (core only, never exposed to plugins)

    /** One access token record as listed by the server (the token value itself is never listed). */
    public static class TokenInfo {
        public String id = "";
        public String label = "";
        public long createdAt;
        public long lastUsedAt;
        public long requestCount;
        public boolean revoked;
    }

    /** Result of minting a token: the plain token is available only here, once. */
    public static class CreatedToken {
        public String id = "";
        public String label = "";
        public String token = "";
    }

    public interface AdminListener<T> {
        void onResult(T result);

        void onError(int code, String message);
    }

    /** Server root without trailing slash and without /v1. */
    private static String adminEndpoint(String path) {
        String base = ZephyrConfig.getAiServerUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        return base + path;
    }

    private static long parseTime(Object v) {
        if (v == null || v == JSONObject.NULL) {
            return 0;
        }
        if (v instanceof Number) {
            long n = ((Number) v).longValue();
            return n > 0 && n < 100000000000L ? n * 1000 : n;
        }
        String str = String.valueOf(v);
        if (str.isEmpty() || str.equals("null")) {
            return 0;
        }
        try {
            long n = Long.parseLong(str);
            return n > 0 && n < 100000000000L ? n * 1000 : n;
        } catch (NumberFormatException ignore) {
        }
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(str.length() >= 19 ? str.substring(0, 19) : str).getTime();
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private interface JsonParser<T> {
        T parse(String body) throws Exception;
    }

    private static <T> void adminRequest(String method, String path, JSONObject body, JsonParser<T> parser, AdminListener<T> listener) {
        final String secret = ZephyrConfig.getAiAdminSecret();
        if (ZephyrConfig.getAiServerUrl().isEmpty() || secret.isEmpty()) {
            MAIN.post(() -> listener.onError(ERR_NOT_CONFIGURED, ""));
            return;
        }
        EXECUTOR.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(adminEndpoint(path)).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setRequestProperty("Authorization", "Bearer " + secret);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "ZephyrGram");
                if (body != null) {
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    c.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(bytes);
                    }
                }
                int status = c.getResponseCode();
                if (status < 200 || status >= 300) {
                    String msg = "";
                    String errCode = "";
                    try {
                        JSONObject o = new JSONObject(readAll(c.getErrorStream()));
                        Object e = o.opt("error");
                        if (e instanceof JSONObject) {
                            msg = ((JSONObject) e).optString("message", "");
                            errCode = ((JSONObject) e).optString("code", "");
                        } else if (e != null) {
                            msg = String.valueOf(e);
                        }
                        if (msg.isEmpty()) {
                            msg = o.optString("message", "");
                        }
                    } catch (Throwable ignore) {
                    }
                    int code = status == 401 || status == 403 ? ERR_UNAUTHORIZED
                            : (status == 404 && !errCode.equals("token_not_found")) || status == 405 ? ERR_ADMIN_UNAVAILABLE
                            : status == 429 ? ERR_RATE_LIMIT
                            : status == 503 ? ERR_STARTING : ERR_SERVER;
                    throw new ApiException(code, msg.isEmpty() ? ("HTTP " + status) : msg);
                }
                final T result = parser.parse(readAll(c.getInputStream()));
                MAIN.post(() -> listener.onResult(result));
            } catch (ApiException e) {
                logApiError("admin " + method, e);
                MAIN.post(() -> listener.onError(e.code, e.getMessage()));
            } catch (Throwable e) {
                logFailure("admin " + method, e);
                final String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                MAIN.post(() -> listener.onError(ERR_NETWORK, m));
            } finally {
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Throwable ignore) {
                    }
                }
            }
        });
    }

    /** POST /admin/tokens. The returned token is shown by the server only once. */
    public static void adminCreateToken(String label, String note, int rateLimitPerMin, int dailyLimit, AdminListener<CreatedToken> listener) {
        JSONObject body = new JSONObject();
        try {
            body.put("label", label);
            if (note != null && !note.isEmpty()) {
                body.put("note", note);
            }
            if (rateLimitPerMin > 0) {
                body.put("rate_limit_per_min", rateLimitPerMin);
            }
            if (dailyLimit > 0) {
                body.put("daily_limit", dailyLimit);
            }
        } catch (Throwable ignore) {
        }
        adminRequest("POST", "/admin/tokens", body, raw -> {
            JSONObject o = new JSONObject(raw);
            CreatedToken t = new CreatedToken();
            t.id = o.optString("id", "");
            t.label = o.optString("label", label);
            t.token = o.optString("token", "");
            if (t.token.isEmpty()) {
                throw new ApiException(ERR_SERVER, "Server returned no token");
            }
            return t;
        }, listener);
    }

    /** GET /admin/tokens. */
    public static void adminListTokens(AdminListener<List<TokenInfo>> listener) {
        adminRequest("GET", "/admin/tokens", null, raw -> {
            JSONObject o = new JSONObject(raw);
            JSONArray arr = o.optJSONArray("data");
            if (arr == null) {
                arr = o.optJSONArray("tokens");
            }
            ArrayList<TokenInfo> out = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.optJSONObject(i);
                    if (j == null) {
                        continue;
                    }
                    TokenInfo t = new TokenInfo();
                    t.id = j.optString("id", "");
                    t.label = j.optString("label", "");
                    t.createdAt = parseTime(j.opt("created_at"));
                    t.lastUsedAt = parseTime(j.opt("last_used_at"));
                    t.requestCount = j.optLong("request_count", 0);
                    t.revoked = j.optBoolean("revoked", false);
                    out.add(t);
                }
            }
            return out;
        }, listener);
    }

    /** DELETE /admin/tokens/:id (revoke). */
    public static void adminRevokeToken(String id, AdminListener<Boolean> listener) {
        adminRequest("DELETE", "/admin/tokens/" + android.net.Uri.encode(id), null, raw -> Boolean.TRUE, listener);
    }

    /** PATCH /admin/tokens/:id with new limits; a value <= 0 is left out (unchanged). */
    public static void adminUpdateLimits(String id, int rateLimitPerMin, int dailyLimit, AdminListener<Boolean> listener) {
        JSONObject body = new JSONObject();
        try {
            if (rateLimitPerMin > 0) {
                body.put("rate_limit_per_min", rateLimitPerMin);
            }
            if (dailyLimit > 0) {
                body.put("daily_limit", dailyLimit);
            }
        } catch (Throwable ignore) {
        }
        adminRequest("PATCH", "/admin/tokens/" + android.net.Uri.encode(id), body, raw -> Boolean.TRUE, listener);
    }
}
