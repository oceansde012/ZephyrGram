package org.telegram.messenger.zephyr.plugins;

import android.content.Context;
import android.os.Build;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin icon button in the chat header ({@link PluginMenus#TYPE_CHAT_HEADER_BUTTON}). Only one button is shown: the
 * accepted item with the highest priority. The other accepted header buttons are listed in the "three dots" menu by
 * {@link PluginHeaderMenu#sync(ActionBarMenuItem, int, int, long, BaseFragment, int)}. Conditions run when the chat
 * opens, on resume and when plugin menus change, never per frame. Nothing here may throw into the chat screen.
 */
public final class PluginHeaderButton {

    private PluginHeaderButton() {
    }

    private static final Set<String> missingIconsLogged = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Header button to show for this chat, or null. UI thread. */
    public static PluginMenus.Item pick(int account, long dialogId, BaseFragment fragment) {
        try {
            if (!PluginMenus.hasItems(PluginMenus.TYPE_CHAT_HEADER_BUTTON)) {
                return null;
            }
            List<PluginMenus.Item> accepted = PluginMenus.getItems(PluginMenus.TYPE_CHAT_HEADER_BUTTON, account, dialogId, null, fragment);
            return accepted.isEmpty() ? null : accepted.get(0);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /**
     * Shows {@code item} on {@code button} (or hides the button when null). Returns the shown item id, or 0.
     * An unknown icon name falls back to the stock AI icon and is written to the plugin log once per app run.
     */
    public static int apply(ActionBarMenuItem button, PluginMenus.Item item, Context context) {
        if (button == null) {
            return 0;
        }
        try {
            if (item == null) {
                button.setVisibility(View.GONE);
                return 0;
            }
            Context c = context != null ? context : ApplicationLoader.applicationContext;
            int icon = item.iconRes(c);
            if (icon == 0) {
                icon = R.drawable.ai_chat;
                if (item.icon != null && !item.icon.isEmpty() && missingIconsLogged.add(item.pluginId + "/" + item.icon)) {
                    PluginRuntime.INSTANCE.logNative(item.pluginId, "Header button icon not found: " + item.icon);
                }
            }
            button.setIcon(icon);
            button.setContentDescription(item.text);
            if (Build.VERSION.SDK_INT >= 26) {
                button.setTooltipText(item.text);
            }
            button.setVisibility(View.VISIBLE);
            return item.id;
        } catch (Throwable t) {
            FileLog.e(t);
            try {
                button.setVisibility(View.GONE);
            } catch (Throwable ignore) {
            }
            return 0;
        }
    }

    /** Runs on_click of the shown button. Exceptions are contained. */
    public static void onClick(int itemId, int account, long dialogId, BaseFragment fragment) {
        if (itemId == 0) {
            return;
        }
        try {
            PluginMenus.onClick(itemId, account, dialogId, null, fragment);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
