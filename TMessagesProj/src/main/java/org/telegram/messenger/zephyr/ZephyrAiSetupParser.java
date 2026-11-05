package org.telegram.messenger.zephyr;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java (no Android classes) helpers for the AI assistant: server URL normalization, secret sanitizing and parsing
 * of the shared access message (zephyrai://setup?url=..&token=.. plus "Server:/Token:" lines as a fallback).
 * Kept free of Android so it can be unit-checked with a plain main (see tools/SetupParserCheck.java).
 */
public final class ZephyrAiSetupParser {

    private ZephyrAiSetupParser() {
    }

    private static final String SCHEME_NAME = "zephyrai://setup";
    private static final Pattern TOKEN_PARAM = Pattern.compile("(?i)(?:^|[?&;])token=([^&\\s]+)");
    private static final Pattern LABEL_URL = Pattern.compile("(?imu)^[\\s>*_]*(?:server|сервер|url|адреса|адрес)[\\s*_]*[:：]\\s*(\\S+)");
    private static final Pattern LABEL_TOKEN = Pattern.compile("(?imu)^[\\s>*_]*(?:token|токен)[\\s*_]*[:：]\\s*(\\S+)");
    private static final Pattern ANY_TOKEN = Pattern.compile("zgai_[A-Za-z0-9_\\-]{8,}");
    private static final Pattern ANY_URL = Pattern.compile("(?i)https?://[^\\s<>\"']+");
    private static final Pattern CONTINUATION = Pattern.compile("^[A-Za-z0-9%._~:/?#&=+\\-]+$");

    /** Result: url (normalized, may be empty) and token (sanitized, may be empty). */
    public static final class Result {
        public final String url;
        public final String token;

        Result(String url, String token) {
            this.url = url;
            this.token = token;
        }

        public boolean complete() {
            return !url.isEmpty() && !token.isEmpty();
        }
    }

    private static boolean isInvisible(char c) {
        return c == '​' || c == '‌' || c == '‍' || c == '‎' || c == '‏' || c == '⁠'
                || c == '﻿' || c == '­' || c == '‪' || c == '‬';
    }

    /** True when the final ']' closes a "[...]" host literal (bare IPv6 host), so it is not trailing punctuation. */
    private static boolean closesIpv6Literal(String s) {
        int end = s.length() - 1;
        if (s.charAt(end) != ']') {
            return false;
        }
        int open = s.lastIndexOf('[', end);
        return open >= 0 && s.indexOf(']', open) == end && s.indexOf(':', open) > open;
    }

    /**
     * True only for a well-formed bracketed IPv6 literal that is loopback (::1), unique-local (fc00::/7) or link-local
     * (fe80::/10). Anything malformed, IPv4-embedded (::ffff:a.b.c.d) or public returns false (so http is upgraded to https).
     */
    static boolean isLocalIpv6Literal(String h) {
        if (h.length() < 4 || h.charAt(0) != '[' || h.charAt(h.length() - 1) != ']') {
            return false;
        }
        String a = h.substring(1, h.length() - 1);
        int zone = a.indexOf('%');
        if (zone >= 0) {
            a = a.substring(0, zone);
        }
        if (a.isEmpty() || a.indexOf('.') >= 0) {
            return false;
        }
        int dbl = a.indexOf("::");
        if (dbl >= 0 && a.indexOf("::", dbl + 1) >= 0) {
            return false;
        }
        int[] head = new int[8];
        int[] tail = new int[8];
        int headN;
        int tailN = 0;
        if (dbl >= 0) {
            headN = parseIpv6Groups(a.substring(0, dbl), head);
            tailN = parseIpv6Groups(a.substring(dbl + 2), tail);
            if (headN < 0 || tailN < 0 || headN + tailN > 7) {
                return false;
            }
        } else {
            headN = parseIpv6Groups(a, head);
            if (headN != 8) {
                return false;
            }
        }
        int[] g = new int[8];
        for (int i = 0; i < headN; i++) {
            g[i] = head[i];
        }
        for (int i = 0; i < tailN; i++) {
            g[8 - tailN + i] = tail[i];
        }
        boolean zeroPrefix = true;
        for (int i = 0; i < 7; i++) {
            if (g[i] != 0) {
                zeroPrefix = false;
            }
        }
        if (zeroPrefix && g[7] == 1) {
            return true;
        }
        return (g[0] & 0xfe00) == 0xfc00 || (g[0] & 0xffc0) == 0xfe80;
    }

    /** Parses "a:b:c" hex groups (1-4 digits each) into out; returns the count, 0 for an empty string, -1 when malformed. */
    private static int parseIpv6Groups(String s, int[] out) {
        if (s.isEmpty()) {
            return 0;
        }
        int n = 0;
        int start = 0;
        while (true) {
            int end = s.indexOf(':', start);
            String part = end < 0 ? s.substring(start) : s.substring(start, end);
            if (part.isEmpty() || part.length() > 4 || n >= 8) {
                return -1;
            }
            int v = 0;
            for (int i = 0; i < part.length(); i++) {
                int d = Character.digit(part.charAt(i), 16);
                if (d < 0 || part.charAt(i) > 'f') {
                    return -1;
                }
                v = v * 16 + d;
            }
            out[n++] = v;
            if (end < 0) {
                return n;
            }
            start = end + 1;
        }
    }

    private static boolean isLocalHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            return isLocalIpv6Literal(h);
        }
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".localhost") || h.endsWith(".lan")) {
            return true;
        }
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) {
            return true;
        }
        if (h.startsWith("172.")) {
            try {
                int second = Integer.parseInt(h.substring(4, h.indexOf('.', 4)));
                return second >= 16 && second <= 31;
            } catch (Throwable ignore) {
            }
        }
        return false;
    }

    /**
     * Server root without /v1, /admin, query or trailing slash; scheme added (https) when missing; http upgraded to https for
     * public hosts (Android blocks cleartext and Render redirects http to https, which would turn POST into GET).
     * Returns "" for blank/unusable input.
     */
    public static String normalizeServerUrl(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isWhitespace(c) && !isInvisible(c)) {
                sb.append(c);
            }
        }
        String s = sb.toString();
        // surrounding quotes / angle brackets / markdown
        while (s.length() > 1 && "\"'`<([".indexOf(s.charAt(0)) >= 0) {
            s = s.substring(1);
        }
        while (s.length() > 1 && "\"'`>).,;]".indexOf(s.charAt(s.length() - 1)) >= 0 && !closesIpv6Literal(s)) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return "";
        }
        String scheme = "https";
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            s = s.substring(8);
        } else if (lower.startsWith("http://")) {
            s = s.substring(7);
            scheme = "http";
        } else if (s.startsWith("//")) {
            s = s.substring(2);
        } else if (s.contains("://")) {
            return "";
        }
        int cut = s.length();
        for (char stop : new char[]{'?', '#'}) {
            int p = s.indexOf(stop);
            if (p >= 0 && p < cut) {
                cut = p;
            }
        }
        s = s.substring(0, cut);
        int slash = s.indexOf('/');
        String host = slash >= 0 ? s.substring(0, slash) : s;
        String path = slash >= 0 ? s.substring(slash) : "";
        if (host.isEmpty() || host.indexOf('@') >= 0) {
            return "";
        }
        String hostOnly = host;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(']') < colon) {
            hostOnly = host.substring(0, colon);
        }
        if (hostOnly.isEmpty() || (!hostOnly.startsWith("[") && hostOnly.indexOf('.') < 0 && !hostOnly.equalsIgnoreCase("localhost"))) {
            // "onrender" or similar with no dot is certainly not a usable public host
            return "";
        }
        if (scheme.equals("http") && !isLocalHost(hostOnly)) {
            scheme = "https";
            if (colon > 0 && host.endsWith(":80")) {
                host = hostOnly;
            }
        }
        // strip known API suffixes (repeatedly)
        String[] suffixes = {"/v1/chat/completions", "/chat/completions", "/v1/models", "/models", "/admin/tokens", "/admin", "/v1"};
        boolean changed = true;
        while (changed) {
            changed = false;
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
                changed = true;
            }
            String pl = path.toLowerCase(Locale.ROOT);
            for (String suf : suffixes) {
                if (pl.endsWith(suf)) {
                    path = path.substring(0, path.length() - suf.length());
                    changed = true;
                    break;
                }
            }
        }
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + path;
    }

    /** Token/secret as pasted: drops "Bearer ", quotes, any whitespace (even inside), zero-width and non-ASCII characters. */
    public static String sanitizeSecret(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.regionMatches(true, 0, "bearer", 0, 6) && s.length() > 6 && (Character.isWhitespace(s.charAt(6)) || s.charAt(6) == ':')) {
            s = s.substring(7);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 33 && c <= 126) {
                sb.append(c);
            }
        }
        s = sb.toString();
        while (s.length() > 1 && "\"'`<([*".indexOf(s.charAt(0)) >= 0) {
            s = s.substring(1);
        }
        while (s.length() > 1 && "\"'`>)],;*".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** %XX decoding only ('+' stays '+'); malformed escapes are kept literally. */
    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() + 0 && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        try {
            return out.toString("UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String stripTrailingPunct(String s) {
        while (!s.isEmpty() && ".,;:)>]\"'»*".indexOf(s.charAt(s.length() - 1)) >= 0 && !closesIpv6Literal(s)) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Extracts the (possibly line-wrapped) zephyrai://setup link, or null. */
    private static String extractLink(String text) {
        int i = text.toLowerCase(Locale.ROOT).indexOf(SCHEME_NAME);
        if (i < 0) {
            return null;
        }
        String[] chunks = text.substring(i).split("\\s+");
        StringBuilder link = new StringBuilder(chunks[0]);
        for (int k = 1; k < chunks.length; k++) {
            Matcher m = TOKEN_PARAM.matcher(link);
            if (m.find()) {
                break; // token present: link complete
            }
            String next = chunks[k];
            if (!CONTINUATION.matcher(next).matches() || next.endsWith(":")) {
                break;
            }
            link.append(next);
        }
        return stripTrailingPunct(link.toString());
    }

    private static String param(String query, String name) {
        for (String pair : query.split("[&;]")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (pair.substring(0, eq).trim().equalsIgnoreCase(name)) {
                String v = percentDecode(pair.substring(eq + 1));
                if (v.indexOf('%') >= 0 && v.toLowerCase(Locale.ROOT).matches(".*%[0-9a-f]{2}.*")) {
                    v = percentDecode(v); // double-encoded by a messenger / mail client
                }
                return v;
            }
        }
        return "";
    }

    /**
     * Finds server URL and/or token in arbitrary pasted text. Order: zephyrai:// link, then "Server:/Token:" lines,
     * then any https URL / zgai_ token. Never returns null; check {@link Result#complete()}.
     */
    public static Result parse(CharSequence input) {
        if (input == null) {
            return new Result("", "");
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!isInvisible(c) && c != '\r') {
                clean.append(c);
            }
        }
        String text = clean.toString().replace("&amp;", "&").replace("&#38;", "&");
        String url = "";
        String token = "";
        String link = extractLink(text);
        if (link != null) {
            int q = link.indexOf('?');
            if (q >= 0) {
                String query = link.substring(q + 1);
                url = normalizeServerUrl(param(query, "url"));
                token = sanitizeSecret(param(query, "token"));
            }
        }
        Matcher mt = LABEL_TOKEN.matcher(text);
        String labeledToken = mt.find() ? sanitizeSecret(mt.group(1)) : "";
        if (token.isEmpty() && !labeledToken.isEmpty()) {
            token = labeledToken;
        } else if (!token.isEmpty() && labeledToken.length() > token.length() && labeledToken.startsWith(token)) {
            token = labeledToken; // link got truncated by a line break
        }
        if (token.isEmpty()) {
            Matcher m = ANY_TOKEN.matcher(text);
            if (m.find()) {
                token = m.group();
            }
        }
        if (url.isEmpty()) {
            Matcher mu = LABEL_URL.matcher(text);
            if (mu.find()) {
                url = normalizeServerUrl(mu.group(1));
            }
        }
        if (url.isEmpty()) {
            Matcher m = ANY_URL.matcher(text);
            while (m.find()) {
                String cand = normalizeServerUrl(stripTrailingPunct(m.group()));
                if (!cand.isEmpty() && cand.indexOf("t.me") < 0) {
                    url = cand;
                    break;
                }
            }
        }
        if (token.length() < 8) {
            token = "";
        }
        return new Result(url, token);
    }
}
