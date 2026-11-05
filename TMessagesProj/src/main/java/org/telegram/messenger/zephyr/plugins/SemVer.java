package org.telegram.messenger.zephyr.plugins;

/**
 * Minimal semantic version (major.minor.patch[-prerelease][+build]) for plugin / app versions.
 * Pure Java, no Android dependencies (unit-testable on the JVM).
 *
 * Public API:
 *   static SemVer parse(String s)            -> null when invalid (1..3 numeric components accepted, missing = 0)
 *   static boolean isValid(String s)
 *   static int compare(String a, String b)   -> invalid strings sort lowest (equal to each other)
 *   int compareTo(SemVer o)                  -> build metadata ignored, prerelease < release
 *   final int major, minor, patch; final String pre ("" if none)
 */
public final class SemVer implements Comparable<SemVer> {

    public final int major, minor, patch;
    public final String pre;

    private SemVer(int major, int minor, int patch, String pre) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.pre = pre;
    }

    public static SemVer parse(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        String pre = "";
        int dash = s.indexOf('-');
        if (dash >= 0) {
            pre = s.substring(dash + 1);
            s = s.substring(0, dash);
            if (pre.isEmpty()) {
                return null;
            }
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length < 1 || parts.length > 3) {
            return null;
        }
        int[] n = new int[3];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 9) {
                return null;
            }
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
            }
            n[i] = Integer.parseInt(p);
        }
        return new SemVer(n[0], n[1], n[2], pre);
    }

    public static boolean isValid(String s) {
        return parse(s) != null;
    }

    public static int compare(String a, String b) {
        SemVer x = parse(a), y = parse(b);
        if (x == null && y == null) {
            return 0;
        }
        if (x == null) {
            return -1;
        }
        if (y == null) {
            return 1;
        }
        return x.compareTo(y);
    }

    @Override
    public int compareTo(SemVer o) {
        if (major != o.major) {
            return major < o.major ? -1 : 1;
        }
        if (minor != o.minor) {
            return minor < o.minor ? -1 : 1;
        }
        if (patch != o.patch) {
            return patch < o.patch ? -1 : 1;
        }
        if (pre.isEmpty() && o.pre.isEmpty()) {
            return 0;
        }
        if (pre.isEmpty()) {
            return 1;
        }
        if (o.pre.isEmpty()) {
            return -1;
        }
        String[] a = pre.split("\\.");
        String[] b = o.pre.split("\\.");
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            boolean an = isNum(a[i]), bn = isNum(b[i]);
            int c;
            if (an && bn) {
                c = Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]));
            } else if (an) {
                c = -1;
            } else if (bn) {
                c = 1;
            } else {
                c = a[i].compareTo(b[i]);
            }
            if (c != 0) {
                return c < 0 ? -1 : 1;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static boolean isNum(String s) {
        if (s.isEmpty() || s.length() > 18) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SemVer && compareTo((SemVer) o) == 0;
    }

    @Override
    public int hashCode() {
        return ((major * 31 + minor) * 31 + patch) * 31 + pre.hashCode();
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (pre.isEmpty() ? "" : "-" + pre);
    }
}
