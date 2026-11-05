package org.telegram.messenger.zephyr.plugins;

import java.io.File;
import java.nio.file.Files;

/** JVM check of PluginMediaPolicy (send_media rules). Run: tools/run_plugin_media_test.sh */
public final class PluginMediaPolicyTest {
    static int failed;

    static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    static void kind(String requested, String name, byte[] head, String want) {
        String got;
        try {
            got = PluginMediaPolicy.resolveKind(requested, name, head);
        } catch (IllegalArgumentException e) {
            got = "error";
        }
        check(want.equals(got), "resolveKind(" + requested + ", " + name + ") = " + got + ", want " + want);
    }

    static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    public static void main(String[] args) throws Exception {
        byte[] jpeg = b(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1);
        byte[] png = b(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
        byte[] gif = b('G', 'I', 'F', '8', '9', 'a');
        byte[] mp4 = b(0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm');
        byte[] webm = b(0x1A, 0x45, 0xDF, 0xA3, 0x9F);
        byte[] html = "<!doctype html>".getBytes("UTF-8");

        // auto by extension, header must agree or it degrades to a plain file
        kind(null, "a.JPG", jpeg, "photo");
        kind("auto", "a.png", png, "photo");
        kind("", "a.gif", gif, "gif");
        kind(null, "clip.mp4", mp4, "video");
        kind(null, "clip.webm", webm, "video");
        kind(null, "fake.jpg", html, "document");
        kind(null, "report.pdf", html, "document");
        kind(null, "noext", jpeg, "document");
        // explicit kinds are verified
        kind("photo", "x.bin", jpeg, "photo");
        kind("photo", "x.jpg", html, "error");
        kind("gif", "loop.mp4", mp4, "gif");
        kind("gif", "x.gif", html, "error");
        kind("video", "x.mp4", gif, "error");
        kind("document", "x.exe", html, "document");
        kind("sticker", "x.webp", html, "error");
        kind("photo", "x.jpg", new byte[0], "error");

        check(PluginMediaPolicy.maxBytes("photo") == 10L * 1024 * 1024, "photo limit");
        check(PluginMediaPolicy.maxBytes("gif") == 20L * 1024 * 1024, "gif limit");
        check(PluginMediaPolicy.maxBytes("video") == 100L * 1024 * 1024, "video limit");
        check(PluginMediaPolicy.maxBytes("document") == 100L * 1024 * 1024, "document limit");

        // captions
        check(PluginMediaPolicy.cleanCaption(null) == null, "null caption stays null");
        check("ab".equals(PluginMediaPolicy.cleanCaption("a\0b")), "NUL stripped");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 4095; i++) longText.append('x');
        longText.append("😀"); // surrogate pair straddles the limit
        String cut = PluginMediaPolicy.cleanCaption(longText.toString());
        check(cut.length() == 4095 && !Character.isHighSurrogate(cut.charAt(cut.length() - 1)), "no split surrogate");

        // path containment (canonical, symlinks resolved, sibling prefixes rejected)
        File tmp = Files.createTempDirectory("zg_media").toFile().getCanonicalFile();
        File data = new File(tmp, "data/plugin_a");
        File other = new File(tmp, "data/plugin_ab");
        File secret = new File(tmp, "shared_prefs");
        check(data.mkdirs() && other.mkdirs() && secret.mkdirs(), "mkdirs");
        File inside = new File(data, "send_media/p.jpg");
        check(inside.getParentFile().mkdirs() && inside.createNewFile(), "create inside");
        File sibling = new File(other, "p.jpg");
        check(sibling.createNewFile(), "create sibling");
        File token = new File(secret, "userconfing.xml");
        check(token.createNewFile(), "create secret");
        check(PluginMediaPolicy.isInside(inside, null, data), "inside data dir");
        check(!PluginMediaPolicy.isInside(sibling, data), "sibling prefix rejected");
        check(!PluginMediaPolicy.isInside(new File(data, "../../shared_prefs/userconfing.xml"), data), "dot-dot rejected");
        check(!PluginMediaPolicy.isInside(data, data), "root itself is not a file inside");
        check(!PluginMediaPolicy.isInside(token, new File("/")), "filesystem root never allowed");
        try {
            File link = new File(data, "link.xml");
            Files.createSymbolicLink(link.toPath(), token.toPath());
            check(!PluginMediaPolicy.isInside(link, data), "symlink out of the data dir rejected");
        } catch (UnsupportedOperationException | java.io.IOException ignore) {
            // file system without symlinks
        }

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("OK");
    }
}
