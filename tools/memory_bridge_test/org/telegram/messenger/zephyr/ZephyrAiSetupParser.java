package org.telegram.messenger.zephyr;
public class ZephyrAiSetupParser {
    public static String normalizeServerUrl(String value) {
        value = value.trim().replaceAll("/+$", "");
        return value.endsWith("/v1") ? value.substring(0, value.length() - 3) : value;
    }
    public static String sanitizeSecret(String value) { return value.trim(); }
}
