package org.telegram.messenger.zephyr;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Local, client-side message filters. A matching incoming message is hidden in the chat view and does not
 * produce a notification. Nothing is sent to the server and no server-side state or counters are touched.
 * Stored as JSON in its own SharedPreferences file.
 */
public class ZephyrMessageFilters {

    private static final String PREFS = "zephyrfilters";
    private static final int MAX_TEXT = 4096;
    private static final long REGEX_BUDGET_MS = 50;

    public static class Filter {
        public long id;
        public String name = "";
        public String pattern = "";
        public boolean regex;
        public boolean matchCase;
        public boolean enabled = true;
        /** 0 = any chat. */
        public long dialogId;
        /** 0 = any sender. */
        public long senderId;

        private volatile Pattern compiled;
        private volatile boolean compileFailed;
        private volatile boolean budgetLogged;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("pattern", pattern);
            o.put("regex", regex);
            o.put("matchCase", matchCase);
            o.put("enabled", enabled);
            o.put("dialogId", dialogId);
            o.put("senderId", senderId);
            return o;
        }

        static Filter fromJson(JSONObject o) {
            Filter f = new Filter();
            f.id = o.optLong("id");
            f.name = o.optString("name", "");
            f.pattern = o.optString("pattern", "");
            f.regex = o.optBoolean("regex");
            f.matchCase = o.optBoolean("matchCase");
            f.enabled = o.optBoolean("enabled", true);
            f.dialogId = o.optLong("dialogId");
            f.senderId = o.optLong("senderId");
            return f;
        }

        public Filter copy() {
            Filter f = new Filter();
            f.id = id;
            f.name = name;
            f.pattern = pattern;
            f.regex = regex;
            f.matchCase = matchCase;
            f.enabled = enabled;
            f.dialogId = dialogId;
            f.senderId = senderId;
            return f;
        }

        /** Returns false if the filter has no condition at all or the regex is invalid. */
        public boolean isValid() {
            if (pattern.isEmpty() && dialogId == 0 && senderId == 0) {
                return false;
            }
            if (regex && !pattern.isEmpty()) {
                try {
                    Pattern.compile(pattern);
                } catch (Throwable e) {
                    return false;
                }
            }
            return true;
        }

        /** Text-only part of the match (also used by the "test" input). */
        public boolean matchesText(String text) {
            if (pattern.isEmpty()) {
                return true;
            }
            if (text == null || text.isEmpty()) {
                return false;
            }
            if (text.length() > MAX_TEXT) {
                text = text.substring(0, MAX_TEXT);
            }
            if (!regex) {
                if (matchCase) {
                    return text.contains(pattern);
                }
                return text.toLowerCase().contains(pattern.toLowerCase());
            }
            if (compileFailed) {
                return false;
            }
            Pattern p = compiled;
            if (p == null) {
                try {
                    p = Pattern.compile(pattern, matchCase ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                    compiled = p;
                } catch (Throwable e) {
                    compileFailed = true;
                    // Log only the filter id and error class: the pattern may contain private text.
                    FileLog.e("ZephyrFilters: regex compile failed, filter id=" + id + " " + e.getClass().getSimpleName());
                    return false;
                }
            }
            try {
                return p.matcher(new Budgeted(text, System.currentTimeMillis() + REGEX_BUDGET_MS)).find();
            } catch (Throwable e) {
                if (!budgetLogged) {
                    budgetLogged = true;
                    FileLog.w("ZephyrFilters: regex match aborted (budget/failure), filter id=" + id + " " + e.getClass().getSimpleName());
                }
                return false; // budget exceeded or other failure: do not hide
            }
        }

        boolean matches(long messageDialogId, long fromId, String text) {
            if (!enabled || !isValid()) {
                return false;
            }
            if (dialogId != 0 && dialogId != messageDialogId) {
                return false;
            }
            if (senderId != 0 && senderId != fromId) {
                return false;
            }
            return matchesText(text);
        }
    }

    /** Guards against catastrophic regex backtracking. */
    private static class Budgeted implements CharSequence {
        private final CharSequence base;
        private final long deadline;
        private int counter;

        Budgeted(CharSequence base, long deadline) {
            this.base = base;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++counter & 0x3FF) == 0 && System.currentTimeMillis() > deadline) {
                throw new RuntimeException("regex budget exceeded");
            }
            return base.charAt(index);
        }

        @Override
        public int length() {
            return base.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Budgeted(base.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return base.toString();
        }
    }

    private static volatile List<Filter> filters = new ArrayList<>();
    private static volatile boolean enabled = false;
    private static volatile boolean hasActive = false;
    private static volatile boolean loaded;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void load() {
        if (loaded || ApplicationLoader.applicationContext == null) {
            return;
        }
        SharedPreferences p = prefs();
        enabled = p.getBoolean("enabled", false);
        ArrayList<Filter> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p.getString("list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                list.add(Filter.fromJson(arr.getJSONObject(i)));
            }
        } catch (Throwable e) {
            FileLog.e("ZephyrFilters: load failed " + e.getClass().getSimpleName());
        }
        filters = list;
        updateActive();
        loaded = true;
    }

    private static void updateActive() {
        boolean any = false;
        for (Filter f : filters) {
            if (f.enabled && f.isValid()) {
                any = true;
                break;
            }
        }
        hasActive = enabled && any;
    }

    private static synchronized void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Filter f : filters) {
                arr.put(f.toJson());
            }
            prefs().edit().putBoolean("enabled", enabled).putString("list", arr.toString()).apply();
        } catch (Throwable e) {
            FileLog.e("ZephyrFilters: save failed " + e.getClass().getSimpleName());
        }
        updateActive();
    }

    public static boolean isEnabled() {
        load();
        return enabled;
    }

    public static synchronized void setEnabled(boolean value) {
        load();
        enabled = value;
        save();
    }

    public static List<Filter> getAll() {
        load();
        return filters;
    }

    /** Adds (id == 0) or replaces (by id) a filter. */
    public static synchronized void put(Filter filter) {
        load();
        ArrayList<Filter> list = new ArrayList<>(filters);
        if (filter.id == 0) {
            filter.id = System.currentTimeMillis();
            while (indexOf(list, filter.id) >= 0) {
                filter.id++;
            }
            list.add(filter);
        } else {
            int i = indexOf(list, filter.id);
            if (i >= 0) {
                list.set(i, filter);
            } else {
                list.add(filter);
            }
        }
        filters = list;
        save();
    }

    public static synchronized void remove(long id) {
        load();
        ArrayList<Filter> list = new ArrayList<>(filters);
        int i = indexOf(list, id);
        if (i >= 0) {
            list.remove(i);
            filters = list;
            save();
        }
    }

    private static int indexOf(List<Filter> list, long id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    /** Returns the first enabled filter whose text condition matches the sample text, or null. */
    public static Filter testText(String text) {
        load();
        for (Filter f : filters) {
            if (f.enabled && f.isValid() && !f.pattern.isEmpty() && f.matchesText(text)) {
                return f;
            }
        }
        return null;
    }

    /** True when the incoming message must be hidden locally. Outgoing, service and sponsored messages are never hidden. */
    public static boolean shouldHide(MessageObject obj) {
        if (!loaded) {
            load();
        }
        if (!hasActive || obj == null || obj.messageOwner == null) {
            return false;
        }
        try {
            TLRPC.Message m = obj.messageOwner;
            if (obj.isOutOwner() || m.out || obj.isSponsored() || obj.getId() <= 0) {
                return false;
            }
            if (m.action != null && !(m.action instanceof TLRPC.TL_messageActionEmpty)) {
                return false;
            }
            long dialog = obj.getDialogId();
            long from = m.from_id != null ? DialogObject.getPeerDialogId(m.from_id) : dialog;
            String text = m.message;
            for (Filter f : filters) {
                if (f.matches(dialog, from, text)) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /** Returns the same list when nothing is hidden, otherwise a filtered copy. The input list is never modified. */
    public static ArrayList<MessageObject> filterIncoming(ArrayList<MessageObject> in) {
        if (in == null) {
            return null;
        }
        if (!loaded) {
            load();
        }
        if (!hasActive) {
            return in;
        }
        ArrayList<MessageObject> out = null;
        for (int i = 0; i < in.size(); i++) {
            if (shouldHide(in.get(i))) {
                if (out == null) {
                    out = new ArrayList<>(in.subList(0, i));
                }
            } else if (out != null) {
                out.add(in.get(i));
            }
        }
        return out == null ? in : out;
    }
}
