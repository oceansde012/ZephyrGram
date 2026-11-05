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
        if (menuItem == null) {
            return;
        }
        try {
            ActionBarPopupWindow.ActionBarPopupWindowLayout layout = menuItem.getPopupLayout();
            if (layout == null) {
                return;
            }
            Context context = fragment != null && fragment.getParentActivity() != null ? fragment.getParentActivity() : ApplicationLoader.applicationContext;
            List<PluginMenus.Item> all = PluginMenus.getItems(type);
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
