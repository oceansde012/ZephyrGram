package org.telegram.messenger.zephyr.plugins;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Menu entries contributed by plugins. UI code asks {@link #getItems} when it builds a menu and adds
 * one entry per item using {@link Item#id} (reserved range starting at {@link #ID_BASE}); on click it
 * calls {@link #onClick}.
 */
public final class PluginMenus {

    private PluginMenus() {
    }

    public static final int TYPE_DRAWER = 0;
    public static final int TYPE_MESSAGE_CONTEXT = 1;
    public static final int TYPE_CHAT_ACTION = 2;
    public static final int TYPE_PROFILE_ACTION = 3;

    public static final int ID_BASE = 0x5A000;
    public static final int ID_MAX = ID_BASE + 0xFFF;

    public interface Listener {
        void onMenusChanged();
    }

    public static final class Item {
        public final int id;
        public final String pluginId;
        public final String key;
        public final int type;
        public final String text;
        public final String subtext;
        /** Name of a drawable resource (e.g. "msg_edit") or null. */
        public final String icon;
        public final int priority;
        public final boolean hasCondition;

        Item(int id, String pluginId, String key, int type, String text, String subtext, String icon, int priority, boolean hasCondition) {
            this.id = id;
            this.pluginId = pluginId;
            this.key = key;
            this.type = type;
            this.text = text;
            this.subtext = subtext;
            this.icon = icon;
            this.priority = priority;
            this.hasCondition = hasCondition;
        }

        /** Drawable resource id for {@link #icon}, or 0 if unknown. */
        public int iconRes(Context context) {
            if (icon == null || icon.isEmpty()) return 0;
            try {
                Context c = context != null ? context : ApplicationLoader.applicationContext;
                return c.getResources().getIdentifier(icon, "drawable", c.getPackageName());
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    private static final AtomicInteger nextId = new AtomicInteger(ID_BASE);
    private static final ConcurrentHashMap<String, Integer> idByKey = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Item> items = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Comparator<Item> ORDER = (a, b) -> a.priority != b.priority ? (a.priority > b.priority ? -1 : 1) : Integer.compare(a.id, b.id);

    public static boolean isPluginItemId(int id) {
        return id >= ID_BASE && id <= ID_MAX;
    }

    public static void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : listeners) {
                try { l.onMenusChanged(); } catch (Throwable ignore) {}
            }
        });
    }

    /** Called from Python. Replaces an existing item with the same plugin+key. */
    public static synchronized void add(String pluginId, String key, int type, String text, String subtext, String icon, int priority, boolean hasCondition) {
        String full = pluginId + "/" + key;
        Integer id = idByKey.get(full);
        if (id == null) {
            int n = nextId.getAndIncrement();
            if (n > ID_MAX) {
                throw new IllegalStateException("Plugin menu ID limit reached; restart the app before adding more items");
            }
            id = n;
            idByKey.put(full, id);
        }
        final int fid = id;
        items.removeIf(i -> i.id == fid);
        items.add(new Item(fid, pluginId, key, type, text, subtext, icon, priority, hasCondition));
        notifyChanged();
    }

    public static synchronized void remove(String pluginId, String key) {
        boolean changed = items.removeIf(i -> i.pluginId.equals(pluginId) && i.key.equals(key));
        if (changed) notifyChanged();
    }

    public static synchronized void removePlugin(String pluginId) {
        if (items.removeIf(i -> i.pluginId.equals(pluginId))) notifyChanged();
    }

    public static boolean hasItems(int type) {
        for (Item i : items) {
            if (i.type == type) return true;
        }
        return false;
    }

    /** All items of a type, highest priority first. Conditions are not evaluated. */
    public static List<Item> getItems(int type) {
        ArrayList<Item> out = null;
        for (Item i : items) {
            if (i.type == type) {
                if (out == null) out = new ArrayList<>();
                out.add(i);
            }
        }
        if (out == null) return Collections.emptyList();
        Collections.sort(out, ORDER);
        return out;
    }

    /** Items of a type whose Python condition (if any) accepts the given context. */
    public static List<Item> getItems(int type, int account, long dialogId, MessageObject message, BaseFragment fragment) {
        List<Item> all = getItems(type);
        if (all.isEmpty()) return all;
        ArrayList<Item> out = new ArrayList<>(all.size());
        for (Item i : all) {
            if (!i.hasCondition || PluginRuntime.callMenuCondition(i.pluginId, i.key, account, dialogId, message, fragment)) {
                out.add(i);
            }
        }
        return out;
    }

    public static Item byId(int id) {
        for (Item i : items) {
            if (i.id == id) return i;
        }
        return null;
    }

    /** Runs the plugin's on_click. Safe on the UI thread; exceptions are contained. */
    public static void onClick(Item item, int account, long dialogId, MessageObject message, BaseFragment fragment) {
        if (item == null) return;
        PluginRuntime.callMenuClick(item.pluginId, item.key, account, dialogId, message, fragment);
    }

    public static boolean onClick(int itemId, int account, long dialogId, MessageObject message, BaseFragment fragment) {
        Item i = byId(itemId);
        if (i == null) return false;
        onClick(i, account, dialogId, message, fragment);
        return true;
    }
}
