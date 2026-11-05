import org.telegram.messenger.zephyr.ZephyrAiSetupParser;

/** Plain-Java check: javac -d out TMessagesProj/src/main/java/org/telegram/messenger/zephyr/ZephyrAiSetupParser.java tools/SetupParserCheck.java && java -cp out SetupParserCheck */
public class SetupParserCheck {
    static int fails;

    static void eq(String name, String exp, String got) {
        if (!exp.equals(got)) { fails++; System.out.println("FAIL " + name + ": expected [" + exp + "] got [" + got + "]"); }
    }

    static void parse(String name, String text, String url, String token) {
        ZephyrAiSetupParser.Result r = ZephyrAiSetupParser.parse(text);
        eq(name + ".url", url, r.url);
        eq(name + ".token", token, r.token);
    }

    public static void main(String[] a) {
        String tok = "zgai_AbCdEfGh12345678-_xyz";
        String link = "zephyrai://setup?url=https%3A%2F%2Fzephyrgram-ai.onrender.com&token=" + tok;
        String msg = "ZephyrGram AI access\n\nOpen...\n\nServер: x\nServer: https://zephyrgram-ai.onrender.com\nToken: " + tok + "\n\n" + link;
        parse("full", msg, "https://zephyrgram-ai.onrender.com", tok);
        parse("linkonly", link, "https://zephyrgram-ai.onrender.com", tok);
        parse("trailing-punct", "see " + link + ".", "https://zephyrgram-ai.onrender.com", tok);
        parse("amp", link.replace("&token", "&amp;token"), "https://zephyrgram-ai.onrender.com", tok);
        parse("wrapped", "zephyrai://setup?url=https%3A%2F%2Fzephyrgram-ai.onrender.com\n&token=" + tok, "https://zephyrgram-ai.onrender.com", tok);
        parse("double-encoded", "zephyrai://setup?url=https%253A%252F%252Fa.onrender.com&token=" + tok, "https://a.onrender.com", tok);
        parse("raw-url", "zephyrai://setup?url=https://a.onrender.com/v1/&token=" + tok, "https://a.onrender.com", tok);
        parse("labels-only", "Сервер: a.onrender.com/v1\nТокен: " + tok, "https://a.onrender.com", tok);
        parse("zero-width", "zephyrai://setup?url=https%3A%2F%2Fa.onrender.com&token=zgai_​AbCdEfGh1234", "https://a.onrender.com", "zgai_AbCdEfGh1234");
        parse("token-only", tok, "", tok);
        parse("garbage", "hello world", "", "");
        eq("url1", "https://a.onrender.com", ZephyrAiSetupParser.normalizeServerUrl(" a.onrender.com/ "));
        eq("url2", "https://a.onrender.com", ZephyrAiSetupParser.normalizeServerUrl("HTTPS://A.onrender.com/v1/"));
        eq("url3", "https://a.onrender.com", ZephyrAiSetupParser.normalizeServerUrl("http://a.onrender.com/admin/tokens"));
        eq("url4", "http://192.168.1.5:8080", ZephyrAiSetupParser.normalizeServerUrl("http://192.168.1.5:8080/v1"));
        eq("url5", "", ZephyrAiSetupParser.normalizeServerUrl("   "));
        eq("url6", "https://a.onrender.com", ZephyrAiSetupParser.normalizeServerUrl("https://a.onrender.com/v1/chat/completions?x=1"));
        eq("sec1", "abc123", ZephyrAiSetupParser.sanitizeSecret(" Bearer abc\n123\n"));
        eq("sec2", "abc", ZephyrAiSetupParser.sanitizeSecret("\"abc\"​"));
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails != 0) System.exit(1);
    }
}
