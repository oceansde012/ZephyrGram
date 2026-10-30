package org.telegram.messenger.zephyr;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** In-memory assistant conversation, persisted as JSON in the app's private files dir. */
public class ZephyrAiStore {

    public static class Msg {
        public final String role; // "user" | "assistant"
        public String content;

        public Msg(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static final int MAX_STORED = 200;
    private static final int MAX_SENT = 40;
    private static final String FILE = "zephyr_ai_chat.json";

    private static ZephyrAiStore instance;
    public final ArrayList<Msg> messages = new ArrayList<>();

    public static synchronized ZephyrAiStore get() {
        if (instance == null) {
            instance = new ZephyrAiStore();
            instance.load();
        }
        return instance;
    }

    private File file() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), FILE);
    }

    private void load() {
        try {
            File f = file();
            if (!f.exists()) {
                return;
            }
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) {
                    off += n;
                }
            }
            JSONArray a = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                messages.add(new Msg(o.optString("r"), o.optString("c")));
            }
        } catch (Throwable ignore) {
        }
    }

    public synchronized void save() {
        try {
            while (messages.size() > MAX_STORED) {
                messages.remove(0);
            }
            JSONArray a = new JSONArray();
            for (Msg m : messages) {
                a.put(new JSONObject().put("r", m.role).put("c", m.content));
            }
            try (FileOutputStream out = new FileOutputStream(file())) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignore) {
        }
    }

    public synchronized void clear() {
        messages.clear();
        file().delete();
    }

    /** Used when the user has not set a custom system prompt. */
    static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant inside the ZephyrGram messenger. Always reply in the language of the user's message (default Ukrainian).";

    /** Request payload: optional system prompt + recent non-empty history. */
    public synchronized JSONArray buildRequest() {
        JSONArray a = new JSONArray();
        try {
            String sys = ZephyrConfig.getAiSystemPrompt();
            if (sys.trim().isEmpty()) {
                sys = DEFAULT_SYSTEM_PROMPT;
            }
            if (!sys.isEmpty()) {
                a.put(new JSONObject().put("role", "system").put("content", sys));
            }
            int start = Math.max(0, messages.size() - MAX_SENT);
            for (int i = start; i < messages.size(); i++) {
                Msg m = messages.get(i);
                if (m.content != null && !m.content.isEmpty()) {
                    a.put(new JSONObject().put("role", m.role).put("content", m.content));
                }
            }
        } catch (Throwable ignore) {
        }
        return a;
    }
}
