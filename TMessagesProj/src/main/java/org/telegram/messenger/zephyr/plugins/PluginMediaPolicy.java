package org.telegram.messenger.zephyr.plugins;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Pure-Java rules behind client.send_media (no Android dependencies, unit tested on the JVM):
 * which files a plugin may send, how the media kind is chosen and checked against the file header,
 * and per-kind size limits. The Python side downloads URLs into the plugin data directory first, so
 * the native side only ever sees local files.
 */
public final class PluginMediaPolicy {

    private PluginMediaPolicy() { }

    public static final String PHOTO = "photo";
    public static final String VIDEO = "video";
    public static final String GIF = "gif";
    public static final String DOCUMENT = "document";

    public static final long MB = 1024L * 1024L;
    public static final long MAX_PHOTO = 10 * MB;
    public static final long MAX_GIF = 20 * MB;
    public static final long MAX_VIDEO = 100 * MB;
    public static final long MAX_DOCUMENT = 100 * MB;
    public static final int MAX_CAPTION = 4096;
    public static final int HEAD_BYTES = 16;

    public static long maxBytes(String kind) {
        switch (kind) {
            case PHOTO: return MAX_PHOTO;
            case GIF: return MAX_GIF;
            case VIDEO: return MAX_VIDEO;
            default: return MAX_DOCUMENT;
        }
    }

    /** True if file (canonical) lies strictly inside one of the roots (canonicalised here; null roots are ignored). */
    public static boolean isInside(File file, File... roots) throws IOException {
        if (file == null) return false;
        String path = file.getCanonicalPath();
        for (File root : roots) {
            if (root == null) continue;
            String base = root.getCanonicalPath();
            if (base.length() <= 1) continue; // never allow "/" as a root
            if (path.startsWith(base + File.separator)) return true;
        }
        return false;
    }

    static String extension(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static boolean isJpeg(byte[] h) { return h != null && h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF; }
    public static boolean isPng(byte[] h) { return h != null && h.length >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'; }
    public static boolean isGif(byte[] h) { return h != null && h.length >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8'; }
    /** ISO base media (mp4/mov/3gp): box type "ftyp" at offset 4. */
    public static boolean isIsoMedia(byte[] h) { return h != null && h.length >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p'; }
    /** Matroska / WebM: EBML magic. */
    public static boolean isMatroska(byte[] h) { return h != null && h.length >= 4 && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3; }

    /**
     * Resolves the media kind. requested is "photo" | "video" | "gif" | "document" | null/"" / "auto" (by extension).
     * An explicit photo/video/gif must match the file header, otherwise IllegalArgumentException; "auto" falls back
     * to document when the header does not match the extension (the file is still sent, just as a file).
     */
    public static String resolveKind(String requested, String fileName, byte[] head) {
        String kind = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        boolean auto = kind.isEmpty() || "auto".equals(kind);
        if (auto) {
            switch (extension(fileName)) {
                case "jpg": case "jpeg": case "png":
                    kind = PHOTO; break;
                case "gif":
                    kind = GIF; break;
                case "mp4": case "m4v": case "mov": case "3gp": case "webm": case "mkv":
                    kind = VIDEO; break;
                default:
                    kind = DOCUMENT;
            }
        }
        boolean ok;
        switch (kind) {
            case PHOTO: ok = isJpeg(head) || isPng(head); break;
            case GIF: ok = isGif(head) || isIsoMedia(head); break;
            case VIDEO: ok = isIsoMedia(head) || isMatroska(head); break;
            case DOCUMENT: ok = true; break;
            default: throw new IllegalArgumentException("kind must be photo, video, gif, document or auto");
        }
        if (ok) return kind;
        if (auto) return DOCUMENT;
        throw new IllegalArgumentException("File content does not look like a " + kind);
    }

    /** null stays null (no caption); strips NUL, truncates to MAX_CAPTION UTF-16 units without splitting a surrogate pair. */
    public static String cleanCaption(String caption) {
        if (caption == null) return null;
        String s = caption.indexOf('\0') >= 0 ? caption.replace("\0", "") : caption;
        if (s.length() > MAX_CAPTION) {
            int end = MAX_CAPTION;
            if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
            s = s.substring(0, end);
        }
        return s;
    }
}
