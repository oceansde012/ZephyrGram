package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zephyr.ZephyrAutoReplyStore;
import org.telegram.messenger.zephyr.plugins.PluginManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

import java.util.List;

/**
 * Installed plugins as compact menu rows with an on/off switch that follows the real result of
 * PluginManager.setEnabled. Used by the main screen menu (DialogsActivity) and by the "Plugins" item of the chat menu.
 */
public final class ZephyrPluginsQuickMenu {

    private ZephyrPluginsQuickMenu() {
    }

    /**
     * Adds one row per installed plugin to an existing menu. With tapOpensSettings == false (main screen) a tap toggles the
     * plugin and a long press opens its settings; with true (chat menu) a tap on the name opens the settings and the
     * switch toggles. Returns false (and adds nothing) when no plugin is installed.
     */
    public static boolean addPluginRows(BaseFragment fragment, ItemOptions io, Theme.ResourcesProvider resourcesProvider,
                                        int textColor, int iconColor, boolean tapOpensSettings) {
        final Context context = fragment.getContext();
        if (context == null) {
            return false;
        }
        final List<PluginManager.InstalledPlugin> plugins;
        try {
            plugins = PluginManager.INSTANCE.getInstalled();
        } catch (Throwable t) {
            return false;
        }
        if (plugins == null || plugins.isEmpty()) {
            return false;
        }
        for (final PluginManager.InstalledPlugin plugin : plugins) {
            if (plugin == null) {
                continue;
            }
            io.addView(createPluginRow(fragment, io, context, resourcesProvider, textColor, iconColor, plugin, tapOpensSettings));
        }
        return true;
    }

    /**
     * The window of the "Plugins" item in the chat menu: for a private chat (not a bot) with the ai_autoreply plugin installed
     * first "Auto-reply in this chat" and "Auto-reply settings for this chat", then the installed plugins, then "All plugins".
     * In groups and channels (user == null) only the plugins.
     */
    public static void showForChat(BaseFragment fragment, android.view.View anchor, int account, long dialogId, TLRPC.User user) {
        final Context context = fragment.getContext();
        if (context == null || anchor == null) {
            return;
        }
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        final int textColor = Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, resourcesProvider);
        final int iconColor = Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, resourcesProvider);
        final ItemOptions io = ItemOptions.makeOptions(fragment, anchor);
        io.setMinWidth(260);

        if (canUseAutoReply(dialogId, user)) {
            io.addView(createAutoReplyRow(fragment, context, resourcesProvider, textColor, iconColor, account, dialogId, UserObject.getUserName(user)));
            io.add(R.drawable.msg_settings, LocaleController.getString(R.string.ZephyrNavArChatSettings),
                    () -> openAutoReplyChatSettings(fragment, account, dialogId));
            io.addGap();
        }

        if (!addPluginRows(fragment, io, resourcesProvider, textColor, iconColor, true)) {
            io.addText(LocaleController.getString(R.string.ZephyrNavNoPlugins), 13);
        }
        io.addGap();
        io.add(R.drawable.msg_customize, LocaleController.getString(R.string.ZephyrNavAllPlugins),
                () -> fragment.presentFragment(new ZephyrPluginsActivity()));
        io.show();
    }

    /**
     * Where "Auto-reply settings for this chat" leads. For now the auto-reply chat list; kept in one place so the target
     * can be switched to a per-chat screen later.
     */
    public static void openAutoReplyChatSettings(BaseFragment fragment, int account, long dialogId) {
        fragment.presentFragment(new ZephyrAutoReplyChatsActivity());
    }

    /** Only private chats with a person (no bots, no secret chats, not Saved Messages) and only with the plugin installed. */
    private static boolean canUseAutoReply(long dialogId, TLRPC.User user) {
        if (user == null || user.bot || UserObject.isUserSelf(user)) {
            return false;
        }
        if (dialogId <= 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        try {
            return PluginManager.INSTANCE.getInstalled(ZephyrAutoReplyStore.PLUGIN_ID) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ZephyrAutoReplyStore.Entry findAutoReplyEntry(int account, long dialogId) {
        final String key = ZephyrAutoReplyStore.chatKey(account, dialogId);
        for (ZephyrAutoReplyStore.Entry e : ZephyrAutoReplyStore.load()) {
            if (key.equals(e.key)) {
                return e;
            }
        }
        return null;
    }

    private static ActionBarMenuSubItem createAutoReplyRow(BaseFragment fragment, Context context, Theme.ResourcesProvider resourcesProvider,
                                                           int textColor, int iconColor, int account, long dialogId, String title) {
        final ActionBarMenuSubItem cell = createCell(context, resourcesProvider, textColor, iconColor,
                LocaleController.getString(R.string.ZephyrNavArThisChat), R.drawable.msg_reply_small);
        boolean enabled = false;
        try {
            final ZephyrAutoReplyStore.Entry entry = findAutoReplyEntry(account, dialogId);
            enabled = entry != null && entry.enabled;
        } catch (Throwable ignore) {
        }
        final Switch switchView = createSwitch(context, resourcesProvider, enabled);
        cell.addView(switchView, LayoutHelper.createFrame(37, 20, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));
        setTextEndMargin(cell, 48);

        final boolean[] wanted = {enabled};
        final boolean[] busy = {false};
        cell.setOnClickListener(v -> {
            if (busy[0]) {
                return;
            }
            busy[0] = true;
            final boolean target = !wanted[0];
            wanted[0] = target;
            switchView.setChecked(target, true);
            Utilities.globalQueue.postRunnable(() -> {
                boolean real = target;
                try {
                    // Same as ZephyrAutoReplyChatsActivity: a known chat is switched on / paused, a new one is added.
                    final ZephyrAutoReplyStore.Entry entry = findAutoReplyEntry(account, dialogId);
                    if (entry != null) {
                        ZephyrAutoReplyStore.setEnabled(entry.key, target);
                    } else if (target) {
                        ZephyrAutoReplyStore.upsert(account, dialogId, title, "user", true);
                    }
                    final ZephyrAutoReplyStore.Entry now = findAutoReplyEntry(account, dialogId);
                    real = now != null && now.enabled;
                } catch (Throwable ignore) {
                }
                final boolean result = real;
                AndroidUtilities.runOnUIThread(() -> {
                    busy[0] = false;
                    wanted[0] = result;
                    switchView.setChecked(result, true);
                    showAutoReplyBulletin(fragment, result);
                });
            });
        });
        return cell;
    }

    private static void showAutoReplyBulletin(BaseFragment fragment, boolean enabled) {
        try {
            final int text;
            if (!enabled) {
                text = R.string.ZephyrNavArOff;
            } else if (!ZephyrAutoReplyStore.isInstalledAndEnabled()) {
                text = R.string.ZephyrNavArPluginOff;
            } else if (!ZephyrAutoReplyStore.isMaster()) {
                text = R.string.ZephyrNavArMasterOff;
            } else {
                text = R.string.ZephyrNavArOn;
            }
            BulletinFactory.of(fragment).createSimpleBulletin(enabled ? R.raw.done : R.raw.chats_hide, LocaleController.getString(text)).show();
        } catch (Throwable ignore) {
        }
    }

    private static ActionBarMenuSubItem createPluginRow(BaseFragment fragment, ItemOptions io, Context context, Theme.ResourcesProvider resourcesProvider,
                                                        int textColor, int iconColor, PluginManager.InstalledPlugin plugin, boolean tapOpensSettings) {
        final ActionBarMenuSubItem cell = createCell(context, resourcesProvider, textColor, iconColor, plugin.name, R.drawable.msg_settings);
        final Switch switchView = createSwitch(context, resourcesProvider, plugin.enabled);

        final boolean[] wanted = {plugin.enabled};
        final Runnable toggle = () -> {
            if (PluginManager.INSTANCE.isBusy()) {
                return;
            }
            wanted[0] = !wanted[0];
            switchView.setChecked(wanted[0], true);
            PluginManager.INSTANCE.setEnabled(plugin.id, wanted[0], error -> {
                final PluginManager.InstalledPlugin now = PluginManager.INSTANCE.getInstalled(plugin.id);
                if (now != null) {
                    wanted[0] = now.enabled;
                    switchView.setChecked(now.enabled, true);
                }
                if (error != null && !error.isEmpty()) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, error).show();
                }
            });
        };
        final Runnable openSettings = () -> {
            io.dismiss();
            fragment.presentFragment(new ZephyrPluginSettingsActivity(plugin.id));
        };

        if (tapOpensSettings) {
            // The switch sits in its own wide touch area: tapping it toggles, tapping the name opens the settings.
            final FrameLayout switchArea = new FrameLayout(context);
            switchArea.addView(switchView, LayoutHelper.createFrame(37, 20, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));
            switchArea.setOnClickListener(v -> toggle.run());
            cell.addView(switchArea, LayoutHelper.createFrame(64, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));
            setTextEndMargin(cell, 72);
            cell.setOnClickListener(v -> openSettings.run());
        } else {
            cell.addView(switchView, LayoutHelper.createFrame(37, 20, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));
            setTextEndMargin(cell, 48);
            cell.setOnClickListener(v -> toggle.run());
            cell.setOnLongClickListener(v -> {
                openSettings.run();
                return true;
            });
        }
        return cell;
    }

    private static ActionBarMenuSubItem createCell(Context context, Theme.ResourcesProvider resourcesProvider,
                                                   int textColor, int iconColor, CharSequence text, int icon) {
        final ActionBarMenuSubItem cell = new ActionBarMenuSubItem(context, false, false, resourcesProvider);
        cell.setPadding(dp(18), 0, dp(18), 0);
        cell.setTextAndIcon(text, icon);
        cell.setColors(textColor, iconColor);
        cell.setSelectorColor(Theme.multAlpha(textColor, .12f));
        return cell;
    }

    private static Switch createSwitch(Context context, Theme.ResourcesProvider resourcesProvider, boolean checked) {
        final Switch switchView = new Switch(context, resourcesProvider);
        switchView.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        switchView.setChecked(checked, false);
        switchView.setClickable(false);
        switchView.setFocusable(false);
        return switchView;
    }

    private static void setTextEndMargin(ActionBarMenuSubItem cell, int marginDp) {
        final FrameLayout.LayoutParams textLp = (FrameLayout.LayoutParams) cell.getTextView().getLayoutParams();
        if (LocaleController.isRTL) {
            textLp.leftMargin = dp(marginDp);
        } else {
            textLp.rightMargin = dp(marginDp);
        }
        cell.getTextView().setLayoutParams(textLp);
    }
}
