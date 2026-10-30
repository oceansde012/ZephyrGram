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
    /** Server unreachable/waking up: HTML or empty page instead of JSON, 502/503/504 from the proxy. Message = HTTP status. */
    public static final int ERR_SERVER_DOWN = 8;

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
        return ZephyrAiSetupParser.normalizeServerUrl(ZephyrConfig.getAiServerUrl()) + "/v1" + path;
    }

    private static boolean serverUrlUsable() {
        return !ZephyrAiSetupParser.normalizeServerUrl(ZephyrConfig.getAiServerUrl()).isEmpty();
    }

    private static final int[] RETRY_PAUSE_MS = {3000, 6000};

    private static boolean transientStatus(int s) {
        return s == 502 || s == 503 || s == 504 || (s >= 520 && s <= 524);
    }

    private static boolean isHtml(HttpURLConnection c) {
        String ct = c.getContentType();
        return ct != null && ct.toLowerCase(java.util.Locale.ROOT).contains("text/html");
    }

    private interface Opener {
        /** Opens a NEW connection and writes the request body (if any). */
        HttpURLConnection open() throws IOException;
    }

    /**
     * Opens, sends and reads the status line; retries (2x, with pauses) on 502/503/504, HTML pages and connect timeouts, which is
     * what a sleeping/deploying Render service answers. Returns the connection of the last attempt; the caller checks the status.
     */
    private static HttpURLConnection openWithRetry(Call call, Opener opener) throws IOException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection c = null;
            boolean canRetry = attempt < RETRY_PAUSE_MS.length && (call == null || !call.cancelled);
            try {
                c = opener.open();
                if (call != null) {
                    call.connection = c;
                }
                int status = c.getResponseCode();
                boolean bad = transientStatus(status) || (status / 100 == 2 && isHtml(c));
                if (!bad || !canRetry) {
                    return c;
                }
            } catch (java.net.SocketTimeoutException e) {
                if (!canRetry) {
                    throw e;
                }
            }
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                }
            }
            try {
                Thread.sleep(RETRY_PAUSE_MS[attempt]);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            if (call != null && call.cancelled) {
                throw new IOException("cancelled");
            }
        }
    }

    /** Short, locale-neutral description for the UI: never a raw exception text. */
    private static String netMessage(Throwable e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return "timeout";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "unknown host";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "TLS";
        }
        if (e instanceof java.net.ConnectException) {
            return "connection refused";
        }
        return "no connection";
    }

    private static JSONObject parseJsonBody(String raw, int status) throws ApiException {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty() || t.charAt(0) == '<') {
            throw new ApiException(ERR_SERVER_DOWN, String.valueOf(status));
        }
        try {
            return new JSONObject(t);
        } catch (Throwable e) {
            throw new ApiException(ERR_SERVER_DOWN, String.valueOf(status));
        }
    }

    private static String clip(String m) {
        m = m == null ? "" : m.replace('\n', ' ').trim();
        return m.length() > 160 ? m.substring(0, 160) + "\u2026" : m;
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
        c.setConnectTimeout(45000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Authorization", "Bearer " + ZephyrAiSetupParser.sanitizeSecret(token));
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
        boolean notJson = false;
        try {
            String raw = readAll(c.getErrorStream()).trim();
            if (raw.isEmpty() || raw.charAt(0) == '<') {
                notJson = true;
            } else {
                JSONObject o = new JSONObject(raw);
                JSONObject e = o.optJSONObject("error");
                msg = e != null ? e.optString("message", "") : "";
            }
        } catch (Throwable ignore) {
            notJson = true;
        }
        int code;
        if (status == 401 || status == 403) {
            code = ERR_UNAUTHORIZED;
        } else if (status == 429) {
            code = ERR_RATE_LIMIT;
        } else if (notJson && (transientStatus(status) || status == 404 || status == 500)) {
            code = ERR_SERVER_DOWN;
            msg = String.valueOf(status);
        } else if (status == 503) {
            code = ERR_STARTING;
        } else {
            code = ERR_SERVER;
        }
        FileLog.w("ZephyrAI HTTP status=" + status);
        return new ApiException(code, msg.isEmpty() ? ("HTTP " + status) : clip(msg));
    }

    private static boolean notConfigured() {
        return !serverUrlUsable() || ZephyrAiSetupParser.sanitizeSecret(ZephyrConfig.getAiToken()).isEmpty();
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
                c = openWithRetry(call, () -> open("GET", "/models", ZephyrConfig.getAiToken()));
                int status = c.getResponseCode();
                if (status != 200) {
                    throw httpError(c, status);
                }
                JSONArray data = parseJsonBody(readAll(c.getInputStream()), status).optJSONArray("data");
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
                    final String m = netMessage(e);
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

                c = openWithRetry(call, () -> {
                    HttpURLConnection cc = open("POST", "/chat/completions", ZephyrConfig.getAiToken());
                    cc.setDoOutput(true);
                    cc.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    cc.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream os = cc.getOutputStream()) {
                        os.write(bytes);
                    }
                    return cc;
                });
                int status = c.getResponseCode();
                if (status != 200) {
                    throw httpError(c, status);
                }
                if (isHtml(c)) {
                    throw new ApiException(ERR_SERVER_DOWN, String.valueOf(status));
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
                    final String m = netMessage(e);
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
            throw new AiException(ERR_NOT_CONFIGURED, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ZephyrAiErrNotConfigured));
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
            final int tmo = timeoutSec;
            c = openWithRetry(null, () -> {
                HttpURLConnection cc = open("POST", "/chat/completions", ZephyrConfig.getAiToken());
                cc.setRequestProperty("Accept", "application/json");
                cc.setConnectTimeout(Math.min(45000, tmo * 1000));
                cc.setReadTimeout(tmo * 1000);
                cc.setDoOutput(true);
                cc.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                cc.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = cc.getOutputStream()) {
                    os.write(bytes);
                }
                return cc;
            });
            int status = c.getResponseCode();
            if (status != 200) {
                ApiException e = httpError(c, status);
                throw new AiException(e.code, e.getMessage());
            }
            JSONObject o;
            try {
                o = parseJsonBody(readAll(c.getInputStream()), status);
            } catch (ApiException pe) {
                throw new AiException(pe.code, pe.getMessage());
            }
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
                throw new AiException(ERR_SERVER, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ZephyrUxAiEmptyResponse));
            }
            return content;
        } catch (AiException e) {
            FileLog.w("ZephyrAI completeBlocking: error code=" + e.code);
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            FileLog.w("ZephyrAI completeBlocking: timeout");
            throw new AiException(ERR_NETWORK, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ZephyrUxAiTimeout));
        } catch (Throwable e) {
            logFailure("completeBlocking", e);
            throw new AiException(ERR_NETWORK, netMessage(e));
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Blocking speech-to-text for plugins: multipart POST {server}/v1/audio/transcriptions. Must NOT run on the UI
     * thread. language may be null/empty (auto). Returns the transcript (may be empty only if the server says so -> error).
     */
    public static String transcribeBlocking(byte[] audio, String mime, String language, int timeoutSec) throws AiException {
        if (notConfigured()) {
            throw new AiException(ERR_NOT_CONFIGURED, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ZephyrAiErrNotConfigured));
        }
        HttpURLConnection c = null;
        try {
            final String boundary = "----ZephyrBoundary" + Long.toHexString(System.nanoTime());
            String m = mime == null || mime.isEmpty() ? "audio/ogg" : mime;
            String ext = m.contains("mpeg") || m.contains("mp3") ? "mp3" : m.contains("mp4") || m.contains("m4a") || m.contains("aac") ? "m4a"
                    : m.contains("wav") ? "wav" : m.contains("webm") ? "webm" : "ogg";
            ByteArrayOutputStream body = new ByteArrayOutputStream(audio.length + 1024);
            writePart(body, boundary, "response_format", "json");
            if (language != null && !language.isEmpty()) {
                writePart(body, boundary, "language", language);
            }
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio." + ext
                    + "\"\r\nContent-Type: " + m + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(audio);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            final byte[] bytes = body.toByteArray();
            final int tmo = Math.max(1, timeoutSec);
            c = openWithRetry(null, () -> {
                HttpURLConnection cc = open("POST", "/audio/transcriptions", ZephyrConfig.getAiToken());
                cc.setRequestProperty("Accept", "application/json");
                cc.setConnectTimeout(Math.min(45000, tmo * 1000));
                cc.setReadTimeout(tmo * 1000);
                cc.setDoOutput(true);
                cc.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                cc.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = cc.getOutputStream()) {
                    os.write(bytes);
                }
                return cc;
            });
            int status = c.getResponseCode();
            if (status != 200) {
                ApiException e = httpError(c, status);
                throw new AiException(e.code, e.getMessage());
            }
            JSONObject o;
            try {
                o = parseJsonBody(readAll(c.getInputStream()), status);
            } catch (ApiException pe) {
                throw new AiException(pe.code, pe.getMessage());
            }
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                throw new AiException(ERR_SERVER, err.optString("message", "error"));
            }
            String text = o.optString("text", "").trim();
            if (text.isEmpty()) {
                throw new AiException(ERR_SERVER, "Empty transcription");
            }
            return text;
        } catch (AiException e) {
            FileLog.w("ZephyrAI transcribeBlocking: error code=" + e.code);
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            FileLog.w("ZephyrAI transcribeBlocking: timeout");
            throw new AiException(ERR_NETWORK, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ZephyrUxAiTimeout));
        } catch (Throwable e) {
            logFailure("transcribeBlocking", e);
            throw new AiException(ERR_NETWORK, netMessage(e));
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
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
        return ZephyrAiSetupParser.normalizeServerUrl(ZephyrConfig.getAiServerUrl()) + path;
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
        final String secret = ZephyrAiSetupParser.sanitizeSecret(ZephyrConfig.getAiAdminSecret());
        if (!serverUrlUsable() || secret.isEmpty()) {
            MAIN.post(() -> listener.onError(ERR_NOT_CONFIGURED, ""));
            return;
        }
        EXECUTOR.execute(() -> {
            HttpURLConnection c = null;
            try {
                final byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
                c = openWithRetry(null, () -> {
                    HttpURLConnection cc = (HttpURLConnection) new URL(adminEndpoint(path)).openConnection();
                    cc.setRequestMethod(method);
                    cc.setConnectTimeout(45000);
                    cc.setReadTimeout(60000);
                    cc.setRequestProperty("Authorization", "Bearer " + secret);
                    cc.setRequestProperty("Accept", "application/json");
                    cc.setRequestProperty("User-Agent", "ZephyrGram");
                    if (bytes != null) {
                        cc.setDoOutput(true);
                        cc.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        cc.setFixedLengthStreamingMode(bytes.length);
                        try (OutputStream os = cc.getOutputStream()) {
                            os.write(bytes);
                        }
                    }
                    return cc;
                });
                int status = c.getResponseCode();
                if (status < 200 || status >= 300) {
                    String msg = "";
                    String errCode = "";
                    boolean notJson = false;
                    try {
                        String raw = readAll(c.getErrorStream()).trim();
                        if (raw.isEmpty() || raw.charAt(0) == '<') {
                            notJson = true;
                        } else {
                            JSONObject o = new JSONObject(raw);
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
                        }
                    } catch (Throwable ignore) {
                        notJson = true;
                    }
                    int code;
                    if (status == 401 || status == 403) {
                        code = ERR_UNAUTHORIZED;
                    } else if (status == 429) {
                        code = ERR_RATE_LIMIT;
                    } else if (notJson && (transientStatus(status) || status == 404 || status == 500 || status == 405)) {
                        // HTML / empty body: proxy page of a sleeping or deploying service, or a wrong URL
                        code = transientStatus(status) ? ERR_SERVER_DOWN : ERR_ADMIN_UNAVAILABLE;
                        msg = String.valueOf(status);
                    } else if ((status == 404 && !errCode.equals("token_not_found")) || status == 405) {
                        code = ERR_ADMIN_UNAVAILABLE;
                    } else if (status == 503) {
                        code = ERR_STARTING;
                    } else {
                        code = ERR_SERVER;
                    }
                    throw new ApiException(code, msg.isEmpty() ? ("HTTP " + status) : clip(msg));
                }
                String rawOk = readAll(c.getInputStream());
                if (isHtml(c) || rawOk.trim().startsWith("<")) {
                    throw new ApiException(ERR_SERVER_DOWN, String.valueOf(status));
                }
                final T result = parser.parse(rawOk);
                MAIN.post(() -> listener.onResult(result));
            } catch (ApiException e) {
                logApiError("admin " + method, e);
                MAIN.post(() -> listener.onError(e.code, e.getMessage()));
            } catch (org.json.JSONException e) {
                logFailure("admin " + method, e);
                MAIN.post(() -> listener.onError(ERR_SERVER_DOWN, "?"));
            } catch (Throwable e) {
                logFailure("admin " + method, e);
                final String m = netMessage(e);
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
            body.put("label", label == null || label.trim().isEmpty() ? "friend" : (label.trim().length() > 100 ? label.trim().substring(0, 100) : label.trim()));
            if (note != null && !note.isEmpty()) {
                body.put("note", note.length() > 500 ? note.substring(0, 500) : note);
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
                throw new ApiException(ERR_SERVER, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ZephyrUxAiNoToken));
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
