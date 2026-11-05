package org.telegram.messenger.zephyr.plugins;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Shows plugin menu entries of {@link PluginMenus#TYPE_CHAT_ACTION} (chat header "three dots" menu) and
 * {@link PluginMenus#TYPE_PROFILE_ACTION} (profile header menu). {@link #sync} is called each time the popup is about to
 * open, so conditions and texts (for example an on/off toggle) are always current. Context passed to Python:
 * account, dialog_id (user id for private chats, negative chat/channel id otherwise), message=None, fragment.
 * Nothing here may throw: a plugin must never crash the host screen.
 */
public final class PluginHeaderMenu {

    private PluginHeaderMenu() {
    }

    /** Adds, updates and hides the plugin sub-items of {@code type} in {@code menuItem}'s popup. UI thread only. */
    public static void sync(ActionBarMenuItem menuItem, int type, int account, long dialogId, BaseFragment fragment) {
        sync(menuItem, type, account, dialogId, fragment, -1);
    }

    /**
     * Same as {@link #sync(ActionBarMenuItem, int, int, long, BaseFragment)}; for {@link PluginMenus#TYPE_CHAT_ACTION}
     * with {@code shownHeaderButtonId >= 0} it also lists the accepted chat header buttons except the one shown in the
     * header ({@code shownHeaderButtonId}, 0 when none is shown), so no plugin button is lost.
     */
    public static void sync(ActionBarMenuItem menuItem, int type, int account, long dialogId, BaseFragment fragment, int shownHeaderButtonId) {
        if (menuItem == null) {
            return;
        }
        try {
            ActionBarPopupWindow.ActionBarPopupWindowLayout layout = menuItem.getPopupLayout();
            if (layout == null) {
                return;
            }
            Context context = fragment != null && fragment.getParentActivity() != null ? fragment.getParentActivity() : ApplicationLoader.applicationContext;
            final boolean withHeaderButtons = type == PluginMenus.TYPE_CHAT_ACTION && shownHeaderButtonId >= 0;
            List<PluginMenus.Item> all = PluginMenus.getItems(type);
            if (withHeaderButtons && PluginMenus.hasItems(PluginMenus.TYPE_CHAT_HEADER_BUTTON)) {
                ArrayList<PluginMenus.Item> merged = new ArrayList<>(all);
                merged.addAll(PluginMenus.getItems(PluginMenus.TYPE_CHAT_HEADER_BUTTON));
                all = merged;
            }
            HashSet<Integer> known = new HashSet<>();
            for (PluginMenus.Item item : all) {
                known.add(item.id);
            }
            // Hide views of plugins that were removed or disabled since the last open.
            for (int i = 0, n = layout.getItemsCount(); i < n; i++) {
                View view = layout.getItemAt(i);
                if (view != null && view.getTag() instanceof Integer) {
                    int tag = (Integer) view.getTag();
                    if (PluginMenus.isPluginItemId(tag) && !known.contains(tag) && view.getVisibility() != View.GONE) {
                        menuItem.hideSubItem(tag);
                    }
                }
            }
            if (all.isEmpty()) {
                return;
            }
            List<PluginMenus.Item> accepted = PluginMenus.getItems(type, account, dialogId, null, fragment);
            if (withHeaderButtons && PluginMenus.hasItems(PluginMenus.TYPE_CHAT_HEADER_BUTTON)) {
                ArrayList<PluginMenus.Item> merged = new ArrayList<>(accepted);
                for (PluginMenus.Item b : PluginMenus.getItems(PluginMenus.TYPE_CHAT_HEADER_BUTTON, account, dialogId, null, fragment)) {
                    if (b.id != shownHeaderButtonId) {
                        merged.add(b);
                    }
                }
                accepted = merged;
            }
            // Compare by id: a plugin re-registering a key swaps in a new Item object.
            HashSet<Integer> acceptedIds = new HashSet<>();
            for (PluginMenus.Item a : accepted) {
                acceptedIds.add(a.id);
            }
            for (PluginMenus.Item item : all) {
                try {
                    if (!acceptedIds.contains(item.id)) {
                        if (menuItem.hasSubItem(item.id)) {
                            menuItem.hideSubItem(item.id);
                        }
                        continue;
                    }
                    int icon = item.iconRes(context);
                    if (icon == 0) {
                        icon = R.drawable.msg_info;
                    }
                    View view = layout.findViewWithTag(item.id);
                    if (view instanceof ActionBarMenuSubItem) {
                        ((ActionBarMenuSubItem) view).setTextAndIcon(item.text, icon);
                    } else {
                        menuItem.addSubItem(item.id, icon, item.text);
                    }
                    menuItem.showSubItem(item.id);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Routes a click on a plugin sub-item to the plugin. Returns true if {@code id} belongs to the plugin range. */
    public static boolean onClick(int id, int account, long dialogId, BaseFragment fragment) {
        if (!PluginMenus.isPluginItemId(id)) {
            return false;
        }
        try {
            PluginMenus.onClick(id, account, dialogId, null, fragment);
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return true;
    }
}
