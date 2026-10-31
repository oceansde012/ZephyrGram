package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.zephyr.ZephyrAiShare;
import org.telegram.messenger.zephyr.ZephyrAutoReplyStore;
import org.telegram.messenger.zephyr.plugins.PluginChatApi;
import org.telegram.messenger.zephyr.plugins.PluginManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Overview of the chats where the "ai_autoreply" plugin is active: add / remove a chat, mode, on/off, master switch.
 * Data lives in the plugin's own preferences (see {@link ZephyrAutoReplyStore}); the running plugin is told to reload.
 * The list follows the plugin's own writes too (a chat added by "answer everyone", paused after errors, resumed):
 * a preference listener reloads it while the screen is open.
 */
public class ZephyrAutoReplyChatsActivity extends BaseFragment implements PluginManager.Listener {
    private static final int T_HEADER = 0, T_CHECK = 1, T_LINK = 2, T_USER = 3, T_INFO = 4, T_SHADOW = 5;
    private static final int A_MASTER = 1, A_ADD = 2, A_SETTINGS = 3, A_INSTALL = 4, A_CHAT = 5;

    private static final class Row {
        final int type, action;
        String text, value;
        int icon;
        boolean divider;
        ZephyrAutoReplyStore.Entry entry;
        TLObject object;

        Row(int type, int action) {
            this.type = type;
            this.action = action;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;
    private boolean loading;
    /** A reload was asked for while one was running: run it again with the newer data when that one ends. */
    private boolean reloadAgain;
    private final Runnable reloadRunnable = this::reload;
    /** Kept in a field: SharedPreferences holds its listeners weakly. Called on the main thread. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sp, key) -> {
        if (ZephyrAutoReplyStore.isListKey(key)) reloadSoon();
    };

    private static String s(int id) {
        return LocaleController.getString(id);
    }

    @Override
    public boolean onFragmentCreate() {
        PluginManager.INSTANCE.addListener(this);
        try {
            ZephyrAutoReplyStore.prefs().registerOnSharedPreferenceChangeListener(prefsListener);
        } catch (Throwable ignore) {
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        PluginManager.INSTANCE.removeListener(this);
        try {
            ZephyrAutoReplyStore.prefs().unregisterOnSharedPreferenceChangeListener(prefsListener);
        } catch (Throwable ignore) {
        }
        AndroidUtilities.cancelRunOnUIThread(reloadRunnable);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(s(R.string.ZephyrArTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((RecyclerListView.OnItemClickListenerExtended) (view, position, x, y) -> onRowClicked(view, position, x));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        rebuild(null);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onPluginsChanged() {
        reloadSoon();
    }

    /** Several writes in a row (the plugin's commit, keep_, mode_) give one reload. */
    private void reloadSoon() {
        AndroidUtilities.cancelRunOnUIThread(reloadRunnable);
        AndroidUtilities.runOnUIThread(reloadRunnable, 250);
    }

    /** Reads the plugin's storage off the UI thread and resolves avatars from the local cache. */
    private void reload() {
        if (loading) {
            reloadAgain = true;     // the running load may have read the store before the latest write
            return;
        }
        loading = true;
        Utilities.globalQueue.postRunnable(() -> {
            final List<ZephyrAutoReplyStore.Entry> entries = ZephyrAutoReplyStore.load();
            final ArrayList<TLObject> objects = new ArrayList<>();
            for (ZephyrAutoReplyStore.Entry e : entries) {
                TLObject o = null;
                try {
                    MessagesController mc = MessagesController.getInstance(e.account);
                    if (e.dialogId > 0) {
                        TLRPC.User u = mc.getUser(e.dialogId);
                        if (u == null) {
                            u = org.telegram.messenger.MessagesStorage.getInstance(e.account).getUserSync(e.dialogId);
                            if (u != null) mc.putUser(u, true);
                        }
                        o = u;
                    } else if (e.dialogId < 0) {
                        TLRPC.Chat c = mc.getChat(-e.dialogId);
                        if (c == null) {
                            c = org.telegram.messenger.MessagesStorage.getInstance(e.account).getChatSync(-e.dialogId);
                            if (c != null) mc.putChat(c, true);
                        }
                        o = c;
                    }
                } catch (Throwable ignore) {
                }
                objects.add(o);
            }
            AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                rebuild(entries, objects);
                if (reloadAgain) {
                    reloadAgain = false;
                    reload();
                }
            });
        });
    }

    private void rebuild(List<ZephyrAutoReplyStore.Entry> entries) {
        rebuild(entries, null);
    }

    private void rebuild(List<ZephyrAutoReplyStore.Entry> entries, List<TLObject> objects) {
        rows.clear();
        PluginManager.InstalledPlugin plugin = PluginManager.INSTANCE.getInstalled(ZephyrAutoReplyStore.PLUGIN_ID);
        if (plugin == null || !plugin.enabled) {
            Row info = new Row(T_INFO, 0);
            info.text = s(R.string.ZephyrArInstallInfo);
            rows.add(info);
            Row install = new Row(T_LINK, A_INSTALL);
            install.text = s(plugin == null ? R.string.ZephyrArInstall : R.string.ZephyrArEnablePlugin);
            install.icon = R.drawable.msg_download;
            rows.add(install);
        } else {
            Row master = new Row(T_CHECK, A_MASTER);
            master.text = s(R.string.ZephyrArMaster);
            master.value = s(R.string.ZephyrArMasterInfo);
            rows.add(master);
            Row masterShadow = new Row(T_SHADOW, 0);
            rows.add(masterShadow);
            Row add = new Row(T_LINK, A_ADD);
            add.text = s(R.string.ZephyrArAddChat);
            add.icon = R.drawable.msg_contact_add;
            add.divider = false;
            rows.add(add);
            Row sh = new Row(T_SHADOW, 0);
            rows.add(sh);
            Row header = new Row(T_HEADER, 0);
            header.text = s(R.string.ZephyrArChats);
            rows.add(header);
            if (entries == null || entries.isEmpty()) {
                if (entries != null) {
                    Row info = new Row(T_INFO, 0);
                    info.text = s(R.string.ZephyrArEmptyTitle) + "\n\n" + s(R.string.ZephyrArEmptyInfo);
                    rows.add(info);
                }
            } else {
                // Chats are grouped per account (the store returns them sorted by account).
                int accounts = 0;
                for (int i = 0; i < entries.size(); i++) {
                    if (i == 0 || entries.get(i).account != entries.get(i - 1).account) accounts++;
                }
                for (int i = 0; i < entries.size(); i++) {
                    ZephyrAutoReplyStore.Entry e = entries.get(i);
                    boolean first = i == 0 || e.account != entries.get(i - 1).account;
                    boolean last = i == entries.size() - 1 || e.account != entries.get(i + 1).account;
                    if (first && accounts > 1) {
                        int count = 0, active = 0;
                        for (ZephyrAutoReplyStore.Entry x : entries) {
                            if (x.account == e.account) {
                                count++;
                                if (x.enabled) active++;
                            }
                        }
                        if (i > 0) rows.add(new Row(T_SHADOW, 0));
                        Row h = new Row(T_HEADER, 0);
                        h.text = LocaleController.formatString(R.string.ZephyrArAccountHeader, accountName(e.account), active, count);
                        rows.add(h);
                    }
                    Row r = new Row(T_USER, A_CHAT);
                    r.entry = e;
                    r.object = objects != null && i < objects.size() ? objects.get(i) : null;
                    r.divider = !last;
                    rows.add(r);
                }
                rows.add(new Row(T_SHADOW, 0));
            }
            Row settings = new Row(T_LINK, A_SETTINGS);
            settings.text = s(R.string.ZephyrArPluginSettings);
            settings.icon = R.drawable.msg_settings;
            rows.add(settings);
            rows.add(new Row(T_SHADOW, 0));
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- clicks

    private void onRowClicked(View view, int position, float x) {
        if (position < 0 || position >= rows.size()) return;
        Row row = rows.get(position);
        switch (row.action) {
            case A_MASTER:
                ZephyrAutoReplyStore.setMaster(!ZephyrAutoReplyStore.isMaster());
                adapter.notifyItemChanged(position);
                break;
            case A_ADD:
                showAddDialog();
                break;
            case A_SETTINGS:
                presentFragment(new ZephyrPluginSettingsActivity(ZephyrAutoReplyStore.PLUGIN_ID));
                break;
            case A_INSTALL:
                presentFragment(new ZephyrPluginDetailsActivity(ZephyrAutoReplyStore.PLUGIN_ID));
                break;
            case A_CHAT:
                boolean rtl = LocaleController.isRTL;
                float edge = AndroidUtilities.dp(72);
                boolean onSwitch = rtl ? x < edge : x > view.getWidth() - edge;
                if (onSwitch) {
                    ZephyrAutoReplyStore.setEnabled(row.entry.key, !row.entry.enabled);
                    row.entry.enabled = !row.entry.enabled;
                    adapter.notifyItemChanged(position);
                } else {
                    showChatOptions(row.entry);
                }
                break;
            default:
                break;
        }
    }

    private static String accountName(int account) {
        try {
            TLRPC.User u = UserConfig.getInstance(account).getCurrentUser();
            if (u != null) {
                String name = UserObject.getUserName(u);
                String un = UserObject.getPublicUsername(u);
                return un != null && !un.isEmpty() ? name + " (@" + un + ")" : name;
            }
        } catch (Throwable ignore) {
        }
        return "#" + (account + 1);
    }

    private static String modeName(int mode) {
        return s(mode == ZephyrAutoReplyStore.MODE_DRAFT ? R.string.ZephyrArModeDraft
                : mode == ZephyrAutoReplyStore.MODE_FRIEND ? R.string.ZephyrArModeFriend : R.string.ZephyrArModeAuto);
    }

    private void showChatOptions(final ZephyrAutoReplyStore.Entry e) {
        if (getParentActivity() == null) return;
        final CharSequence[] items = new CharSequence[]{
                (e.mode == ZephyrAutoReplyStore.MODE_AUTO ? "✓ " : "") + LocaleController.formatString(R.string.ZephyrArOptionMode, modeName(ZephyrAutoReplyStore.MODE_AUTO)),
                (e.mode == ZephyrAutoReplyStore.MODE_DRAFT ? "✓ " : "") + LocaleController.formatString(R.string.ZephyrArOptionMode, modeName(ZephyrAutoReplyStore.MODE_DRAFT)),
                (e.mode == ZephyrAutoReplyStore.MODE_FRIEND ? "✓ " : "") + LocaleController.formatString(R.string.ZephyrArOptionMode, modeName(ZephyrAutoReplyStore.MODE_FRIEND)),
                s(e.enabled ? R.string.ZephyrArDisable : R.string.ZephyrArEnable),
                s(R.string.ZephyrArRemove)
        };
        showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(e.title).setItems(items, (d, which) -> {
            if (which <= 2) {
                ZephyrAutoReplyStore.setMode(e.key, which);
            } else if (which == 3) {
                ZephyrAutoReplyStore.setEnabled(e.key, !e.enabled);
            } else {
                ZephyrAutoReplyStore.remove(e.key);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.ZephyrArRemoved, e.title)).show();
            }
            reload();
        }).create());
    }

    private void showAddDialog() {
        if (getParentActivity() == null) return;
        CharSequence[] items = new CharSequence[]{s(R.string.ZephyrArAddFromChats), s(R.string.ZephyrArAddByUsername),
                LocaleController.formatString(R.string.ZephyrArAddAll, accountName(currentAccount))};
        showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(s(R.string.ZephyrArAddChat)).setItems(items, (d, which) -> {
            if (which == 0) openPicker();
            else if (which == 1) askUsername();
            else confirmAddAll();
        }).create());
    }

    private void confirmAddAll() {
        if (getParentActivity() == null) return;
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(s(R.string.ZephyrArAddChat))
                .setMessage(LocaleController.formatString(R.string.ZephyrArAddAllConfirm, accountName(currentAccount)))
                .setPositiveButton(s(R.string.OK), (d, w) -> addAllPrivateChats())
                .setNegativeButton(s(R.string.Cancel), null).create());
    }

    /** Adds every private chat of the current account from the loaded dialog list, skipping bots, deleted users, Saved Messages and Telegram service chats. */
    private void addAllPrivateChats() {
        final int account = currentAccount;
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<Long> ids = new ArrayList<>();
            ArrayList<String> titles = new ArrayList<>();
            try {
                MessagesController mc = MessagesController.getInstance(account);
                long self = UserConfig.getInstance(account).getClientUserId();
                for (TLRPC.Dialog d : new ArrayList<>(mc.getAllDialogs())) {
                    if (d == null || d.id <= 0 || DialogObject.isEncryptedDialog(d.id) || d.id == self || d.id == 777000) continue;
                    TLRPC.User u = mc.getUser(d.id);
                    if (u == null || u.bot || u.self || UserObject.isDeleted(u) || UserObject.isReplyUser(u)) continue;
                    ids.add(d.id);
                    titles.add(UserObject.getUserName(u));
                }
            } catch (Throwable ignore) {
            }
            long[] idArr = new long[ids.size()];
            for (int i = 0; i < idArr.length; i++) idArr[i] = ids.get(i);
            final int added = ZephyrAutoReplyStore.addMissing(account, idArr, titles.toArray(new String[0]));
            AndroidUtilities.runOnUIThread(() -> {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.ZephyrArAddedMany, added)).show();
                reload();
            });
        });
    }

    private void openPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        args.putBoolean("allowGlobalSearch", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) return false;
            final long dialogId = dids.get(0).dialogId;
            picker.finishFragment();
            addPrivateChat(dialogId);
            return true;
        });
        presentFragment(picker);
    }

    private void askUsername() {
        if (getParentActivity() == null) return;
        final EditText input = new EditText(getParentActivity());
        input.setSingleLine(true);
        input.setHint(s(R.string.ZephyrUxUsernameHint));
        showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(s(R.string.ZephyrArAddByUsername)).setView(input)
                .setPositiveButton(s(R.string.OK), (d, w) -> resolve(input.getText().toString()))
                .setNegativeButton(s(R.string.Cancel), null).create());
    }

    private void resolve(String text) {
        if (getParentActivity() == null) return;
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        ZephyrAiShare.resolveUsername(currentAccount, text, new ZephyrAiShare.ResolveListener() {
            @Override
            public void onResolved(long userId, TLRPC.User user) {
                progress.dismiss();
                addPrivateChat(userId);
            }

            @Override
            public void onFailed() {
                onFailed(ZephyrAiShare.FAIL_NOT_FOUND);
            }

            @Override
            public void onFailed(int reason) {
                progress.dismiss();
                int res = reason == ZephyrAiShare.FAIL_INVALID ? R.string.ZephyrShareUserInvalid
                        : reason == ZephyrAiShare.FAIL_NOT_USER ? R.string.ZephyrShareUserNotUser
                        : reason == ZephyrAiShare.FAIL_NETWORK ? R.string.ZephyrShareResolveNetwork
                        : R.string.ZephyrShareUserNotFound;
                BulletinFactory.of(ZephyrAutoReplyChatsActivity.this).createSimpleBulletin(R.raw.error, s(res)).show();
            }
        });
    }

    private void addPrivateChat(final long dialogId) {
        if (dialogId <= 0 || DialogObject.isEncryptedDialog(dialogId)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, s(R.string.ZephyrArOnlyPrivate)).show();
            return;
        }
        final int account = currentAccount;
        Utilities.globalQueue.postRunnable(() -> {
            String title = String.valueOf(dialogId);
            boolean bot = false;
            try {
                JSONObject info = PluginChatApi.dialogInfo(account, dialogId);
                title = info.optString("title", title);
                if (title.isEmpty()) title = String.valueOf(dialogId);
                bot = info.optBoolean("is_bot");
            } catch (Throwable ignore) {
            }
            final String finalTitle = title;
            if (!bot) ZephyrAutoReplyStore.upsert(account, dialogId, finalTitle, "user", true);
            final boolean isBot = bot;
            AndroidUtilities.runOnUIThread(() -> {
                if (isBot) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, s(R.string.ZephyrArNoBots)).show();
                } else {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.ZephyrArAdded, finalTitle)).show();
                }
                reload();
            });
        });
    }

    // ---------------------------------------------------------------- adapter

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int t = holder.getItemViewType();
            return t == T_CHECK || t == T_LINK || t == T_USER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case T_HEADER:
                    view = new HeaderCell(context);
                    break;
                case T_CHECK:
                    view = new TextCheckCell(context);
                    break;
                case T_LINK:
                    TextDetailSettingsCell cell = new TextDetailSettingsCell(context);
                    cell.setMultilineDetail(true);
                    view = cell;
                    break;
                case T_USER:
                    view = new UserCell(context, 8, 2, false);
                    break;
                case T_SHADOW:
                    view = new ShadowSectionCell(context);
                    break;
                default:
                    view = new TextInfoPrivacyCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            switch (row.type) {
                case T_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.text);
                    break;
                case T_CHECK:
                    ((TextCheckCell) holder.itemView).setTextAndValueAndCheck(row.text, row.value, ZephyrAutoReplyStore.isMaster(), true, false);
                    break;
                case T_LINK:
                    ((TextDetailSettingsCell) holder.itemView).setTextAndValueAndIcon(row.text, row.value == null ? "" : row.value, row.icon, row.divider);
                    break;
                case T_USER:
                    ZephyrAutoReplyStore.Entry e = row.entry;
                    String status = modeName(e.mode);
                    if (e.autoOff) status = LocaleController.formatString(R.string.ZephyrArAutoOff, status);
                    else if (!e.enabled) status = LocaleController.formatString(R.string.ZephyrArPaused, status);
                    UserCell uc = (UserCell) holder.itemView;
                    // A chat stored without a real title (only its id) shows the cached user/chat name.
                    boolean noTitle = e.title == null || e.title.isEmpty() || e.title.equals(String.valueOf(e.dialogId)) || e.title.equals(e.key);
                    uc.setData(row.object, noTitle && row.object != null ? null : e.title, status, 0, row.divider);
                    uc.setChecked(e.enabled, false);
                    break;
                case T_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
                    break;
                default:
                    break;
            }
        }
    }
}
