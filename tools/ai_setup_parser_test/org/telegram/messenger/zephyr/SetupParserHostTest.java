package org.telegram.messenger.zephyr;

/** JVM-free check of ZephyrAiSetupParser host handling. Run: tools/run_setup_parser_test.sh */
public final class SetupParserHostTest {
    static int failed;

    static void eq(String in, String want) {
        String got = ZephyrAiSetupParser.normalizeServerUrl(in);
        if (!want.equals(got)) {
            failed++;
            System.out.println("FAIL " + in + " -> " + got + " (want " + want + ")");
        }
    }

    public static void main(String[] a) {
        // local IPv6 literals keep http
        eq("http://[::1]:8080", "http://[::1]:8080");
        eq("http://[fe80::1]", "http://[fe80::1]");
        eq("http://[fd12:3456::9]:80", "http://[fd12:3456::9]:80");
        eq("http://[fc00::1]", "http://[fc00::1]");
        eq("http://[0:0:0:0:0:0:0:1]", "http://[0:0:0:0:0:0:0:1]");
        // public / malformed / v4-mapped upgrade to https
        eq("http://[2001:db8::1]", "https://[2001:db8::1]");
        eq("http://[2606:4700::1111]:8443", "https://[2606:4700::1111]:8443");
        eq("http://[::]", "https://[::]");
        eq("http://[::2]", "https://[::2]");
        eq("http://[fec0::1]", "https://[fec0::1]");
        eq("http://[::ffff:127.0.0.1]", "https://[::ffff:127.0.0.1]");
        eq("http://[fe80::1::2]", "https://[fe80::1::2]");
        eq("http://[fe80:zz::1]", "https://[fe80:zz::1]");
        eq("http://[fe80::1", "https://[fe80::1");
        eq("http://[1:2:3:4:5:6:7:8:9]", "https://[1:2:3:4:5:6:7:8:9]");
        // ipv4 / names unchanged
        eq("http://192.168.1.5:3000", "http://192.168.1.5:3000");
        eq("http://example.com", "https://example.com");
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("OK");
    }
}
