package org.telegram.messenger.zephyr.plugins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the dunder metadata constants at the top of a .plugin (Python) file WITHOUT running Python.
 * Only top-level (column 0) simple literal assignments are read: strings ('..' or ".."), ints, list of strings.
 * Pure Java, no Android dependencies.
 *
 * Public API:
 *   static PluginManifest parse(String source) throws InvalidException
 *   static boolean isValidId(String id)                 ^[a-z][a-z0-9_]{2,31}$
 *   static final Set&lt;String&gt; KNOWN_PERMISSIONS         network.requests, updates.read, messages.send, ui.menu, internet, java.raw
 *   static final int MAX_SOURCE_BYTES = 512 * 1024
 *   fields: String id, name, description, author, version, minAppVersion, maxAppVersion (null if absent), icon (null);
 *           int versionCode, apiVersion (default 1); List&lt;String&gt; permissions (unmodifiable)
 *   boolean isCompatible(String appVersion, int apiLevel)
 *   boolean hasPermission(String p)
 *   InvalidException extends Exception (message is human readable)
 *
 * Required: __id__, __name__, __version__ (semver), __version_code__ (int &gt; 0). Unknown permissions are rejected.
 */
public final class PluginManifest {

    public static final int MAX_SOURCE_BYTES = 512 * 1024;
    public static final Set<String> KNOWN_PERMISSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "network.requests", "updates.read", "messages.send", "ui.menu", "internet", "java.raw")));

    private static final Pattern ID_RE = Pattern.compile("^[a-z][a-z0-9_]{2,31}$");
    private static final String STR = "(?:\"((?:[^\"\\\\\\n]|\\\\.)*)\"|'((?:[^'\\\\\\n]|\\\\.)*)')";
    private static final Pattern STR_ITEM = Pattern.compile(STR);

    public static final class InvalidException extends Exception {
        public InvalidException(String message) {
            super(message);
        }
    }

    public String id;
    public String name;
    public String description = "";
    public String author = "";
    public String version;
    public int versionCode;
    public int apiVersion = 1;
    public String minAppVersion;
    public String maxAppVersion;
    public String icon;
    public List<String> permissions = Collections.emptyList();

    public static boolean isValidId(String id) {
        return id != null && ID_RE.matcher(id).matches();
    }

    public boolean hasPermission(String p) {
        return permissions.contains(p);
    }

    public boolean isCompatible(String appVersion, int apiLevel) {
        PluginIndex.VersionEntry v = new PluginIndex.VersionEntry();
        v.apiVersion = apiVersion;
        v.minAppVersion = minAppVersion;
        v.maxAppVersion = maxAppVersion;
        return v.isCompatible(appVersion, apiLevel);
    }

    public static PluginManifest parse(String source) throws InvalidException {
        if (source == null || source.isEmpty()) {
            throw new InvalidException("empty plugin file");
        }
        if (source.length() > MAX_SOURCE_BYTES) {
            throw new InvalidException("plugin file too large");
        }
        if (source.charAt(0) == '﻿') {
            source = source.substring(1);
        }
        PluginManifest m = new PluginManifest();
        m.id = str(source, "__id__");
        m.name = str(source, "__name__");
        String d = str(source, "__description__");
        if (d != null) {
            m.description = d;
        }
        String a = str(source, "__author__");
        if (a != null) {
            m.author = a;
        }
        m.version = str(source, "__version__");
        m.minAppVersion = str(source, "__min_app_version__");
        m.maxAppVersion = str(source, "__max_app_version__");
        m.icon = str(source, "__icon__");
        Integer vc = integer(source, "__version_code__");
        Integer api = integer(source, "__api_version__");

        if (m.id == null) {
            throw new InvalidException("missing __id__");
        }
        if (!isValidId(m.id)) {
            throw new InvalidException("invalid __id__ (use a-z, 0-9, _; 3-32 chars; start with a letter)");
        }
        if (m.name == null || m.name.trim().isEmpty()) {
            throw new InvalidException("missing __name__");
        }
        if (m.version == null || !SemVer.isValid(m.version)) {
            throw new InvalidException("missing or invalid __version__");
        }
        if (vc == null || vc <= 0) {
            throw new InvalidException("missing or invalid __version_code__");
        }
        m.versionCode = vc;
        if (api != null) {
            if (api <= 0) {
                throw new InvalidException("invalid __api_version__");
            }
            m.apiVersion = api;
        }
        if (m.minAppVersion != null && !SemVer.isValid(m.minAppVersion)) {
            throw new InvalidException("invalid __min_app_version__");
        }
        if (m.maxAppVersion != null && !SemVer.isValid(m.maxAppVersion)) {
            throw new InvalidException("invalid __max_app_version__");
        }
        m.permissions = Collections.unmodifiableList(permissionList(source));
        for (String p : m.permissions) {
            if (!KNOWN_PERMISSIONS.contains(p)) {
                throw new InvalidException("unknown permission: " + p);
            }
        }
        return m;
    }

    private static String str(String src, String key) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "[ \\t]*=[ \\t]*" + STR + "[ \\t]*(?:#.*)?\\r?$").matcher(src);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1) != null ? m.group(1) : m.group(2);
        return unescape(raw);
    }

    private static Integer integer(String src, String key) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "[ \\t]*=[ \\t]*(\\d{1,9})[ \\t]*(?:#.*)?\\r?$").matcher(src);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static List<String> permissionList(String src) throws InvalidException {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^__permissions__[ \\t]*=[ \\t]*[\\[(]([^\\]\\)]*)[\\])]").matcher(src);
        if (!m.find()) {
            return out;
        }
        String body = m.group(1);
        // strip comments inside the list
        body = body.replaceAll("(?m)#.*$", "");
        Matcher it = STR_ITEM.matcher(body);
        int last = 0;
        while (it.find()) {
            String between = body.substring(last, it.start());
            if (!between.replace(",", "").trim().isEmpty()) {
                throw new InvalidException("invalid __permissions__ list");
            }
            last = it.end();
            String p = unescape(it.group(1) != null ? it.group(1) : it.group(2));
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        if (!body.substring(last).replace(",", "").trim().isEmpty()) {
            throw new InvalidException("invalid __permissions__ list");
        }
        return out;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                b.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n':
                    b.append('\n');
                    break;
                case 't':
                    b.append('\t');
                    break;
                case 'r':
                    b.append('\r');
                    break;
                case 'u':
                    if (i + 4 < s.length() + 0 && i + 4 <= s.length() - 1 + 1) {
                        try {
                            b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (Exception ignore) {
                        }
                    }
                    b.append("\\u");
                    break;
                case '\\':
                case '\'':
                case '"':
                    b.append(n);
                    break;
                default:
                    b.append('\\').append(n);
            }
        }
        return b.toString();
    }
}
