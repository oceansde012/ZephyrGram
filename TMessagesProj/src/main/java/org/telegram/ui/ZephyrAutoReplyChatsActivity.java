package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zephyr.ZephyrAiShare;
import org.telegram.messenger.zephyr.ZephyrAutoReplyStore;
import org.telegram.messenger.zephyr.plugins.PluginChatApi;
import org.telegram.messenger.zephyr.plugins.PluginManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ViewPagerFixed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The "ai_autoreply" plugin screen, in two tabs.
 * "Chats": the people the bot answers (avatar, name, @username, short status). A tap opens the person's own
 * settings ({@link ZephyrAutoReplyChatSettingsActivity}); a long press starts selection, and the action bar then
 * changes one setting of all selected chats at once (on / pause, mode, gender, reply language, reactions, remove),
 * each in ONE store write. "General": the master switch, a few plain plugin switches and links to the full plugin
 * settings, adding a chat and the plugin page.
 * Data lives in the plugin's own preferences (see {@link ZephyrAutoReplyStore}); the running plugin is told to reload.
 * The list follows the plugin's own writes too (a chat added by "answer everyone", paused after errors, resumed):
 * a preference listener reloads it while the screen is open.
 */
public class ZephyrAutoReplyChatsActivity extends BaseFragment implements PluginManager.Listener {
    private static final int T_HEADER = 0, T_TEXT = 1, T_USER = 2, T_INFO = 3, T_SHADOW = 4;
    private static final int A_NONE = 0, A_MASTER = 1, A_ADD = 2, A_SETTINGS = 3, A_INSTALL = 4, A_CHAT = 5,
            A_REPLY_ALL = 6, A_SKIP_LINKS = 7, A_SHORT = 8, A_REVIVE = 9, A_VERSION = 10;
    private static final int MENU_SEARCH = 1, MENU_SELECT_ALL = 2, MENU_MORE = 3, MENU_ENABLE = 4, MENU_PAUSE = 5,
            MENU_MODE = 6, MENU_GENDER = 7, MENU_LANG = 8, MENU_REACT = 9, MENU_REMOVE = 10;
    private static final int PAGE_CHATS = 0, PAGE_GENERAL = 1;
    /** The search button is shown from this many chats on. */
    private static final int SEARCH_FROM = 6;

    private static final class Row {
        final int type, action;
        String text, sub, value;
        int icon, colorKey;
        boolean divider, check;
        ZephyrAutoReplyStore.Entry entry;
        TLObject object;

        Row(int type, int action) {
            this.type = type;
            this.action = action;
        }
    }

    private final ArrayList<Row> chatRows = new ArrayList<>();
    private final ArrayList<Row> generalRows = new ArrayList<>();
    private RecyclerListView chatsList, generalList;
    private ListAdapter chatsAdapter, generalAdapter;
    private ViewPagerFixed pager;
    private ViewPagerFixed.TabsView tabsView;
    private ActionBarMenuItem searchItem;
    private TextView selectedCountView;
    private int currentPage = PAGE_CHATS;

    /** The last loaded snapshot (null until the first load ends); objects[i] is the cached user/chat of entries[i]. */
    private List<ZephyrAutoReplyStore.Entry> entries;
    private List<TLObject> objects;
    private String query;
    /** Keys ("account:dialogId") of the chats selected for a bulk edit. */
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();

    private boolean loading;
    /** A reload was asked for while one was running: run it again with the newer data when that one ends. */
    private boolean reloadAgain;
    private final Runnable reloadRunnable = this::reload;
    /** Delayed, so a switch tapped here finishes its animation before the list is rebound. */
    private final Runnable generalRunnable = this::rebuildGeneral;
    /** Kept in a field: SharedPreferences holds its listeners weakly. Called on the main thread. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sp, key) -> {
        if (ZephyrAutoReplyStore.isListKey(key)) {
            reloadSoon();
        } else if (isGeneralKey(key)) {
            AndroidUtilities.cancelRunOnUIThread(generalRunnable);
            AndroidUtilities.runOnUIThread(generalRunnable, 300);
        }
    };

    private static String s(int id) {
        return LocaleController.getString(id);
    }

    private static boolean isGeneralKey(String key) {
        return "reply_all".equals(key) || "skip_links".equals(key) || "short_msgs".equals(key) || "revive".equals(key);
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
        AndroidUtilities.cancelRunOnUIThread(generalRunnable);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(s(R.string.ZephyrArTitle));
        actionBar.setCastShadows(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (!selected.isEmpty()) clearSelection();
                    else finishFragment();
                } else {
                    onMenuItem(id);
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search).setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                    }

                    @Override
                    public void onSearchCollapse() {
                        setQuery(null);
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        setQuery(editText.getText().toString());
                    }
                });
        searchItem.setSearchFieldHint(s(R.string.ZephyrAr2Search));
        searchItem.setVisibility(View.GONE);

        ActionBarMenu actionMode = actionBar.createActionMode();
        selectedCountView = new TextView(actionMode.getContext());
        selectedCountView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        selectedCountView.setTypeface(AndroidUtilities.bold());
        selectedCountView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        selectedCountView.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        selectedCountView.setSingleLine(true);
        selectedCountView.setEllipsize(TextUtils.TruncateAt.END);
        actionMode.addView(selectedCountView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0));
        selectedCountView.setOnTouchListener((v, event) -> true);
        actionMode.addItemWithWidth(MENU_SELECT_ALL, R.drawable.msg_select, AndroidUtilities.dp(54), s(R.string.ZephyrAr2SelectAll));
        actionMode.addItemWithWidth(MENU_REMOVE, R.drawable.msg_delete, AndroidUtilities.dp(54), s(R.string.ZephyrAr2BulkRemove));
        ActionBarMenuItem more = actionMode.addItemWithWidth(MENU_MORE, R.drawable.ic_ab_other, AndroidUtilities.dp(54), s(R.string.ZephyrAr2More));
        more.addSubItem(MENU_ENABLE, R.drawable.msg_round_play_m, s(R.string.ZephyrAr2BulkEnable));
        more.addSubItem(MENU_PAUSE, R.drawable.msg_round_pause_m, s(R.string.ZephyrAr2BulkPause));
        more.addSubItem(MENU_MODE, R.drawable.msg_discussion, s(R.string.ZephyrAr2BulkMode));
        more.addSubItem(MENU_GENDER, R.drawable.msg_contacts, s(R.string.ZephyrAr2BulkGender));
        more.addSubItem(MENU_LANG, R.drawable.msg_language, s(R.string.ZephyrAr2BulkLang));
        more.addSubItem(MENU_REACT, R.drawable.msg_reactions, s(R.string.ZephyrAr2BulkReact));

        FrameLayout frameLayout = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY));
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (tabsView != null && Theme.dividerPaint != null) {
                    float y = tabsView.getMeasuredHeight();
                    canvas.drawLine(0, y, getWidth(), y, Theme.dividerPaint);
                }
            }
        };
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        chatsList = createList(context, chatsAdapter = new ListAdapter(context, chatRows), true);
        generalList = createList(context, generalAdapter = new ListAdapter(context, generalRows), false);

        pager = new ViewPagerFixed(context) {
            @Override
            protected void onTabPageSelected(int position) {
                onPageShown(position);
            }
        };
        pager.setAdapter(new ViewPagerFixed.Adapter() {
            @Override
            public int getItemCount() {
                return 2;
            }

            @Override
            public View createView(int viewType) {
                return viewType == PAGE_GENERAL ? generalList : chatsList;
            }

            @Override
            public void bindView(View view, int position, int viewType) {
            }

            @Override
            public int getItemViewType(int position) {
                return position;
            }

            @Override
            public CharSequence getItemTitle(int position) {
                return s(position == PAGE_GENERAL ? R.string.ZephyrAr2TabGeneral : R.string.ZephyrAr2TabChats);
            }
        });
        tabsView = pager.createTabsView(true, 8);
        tabsView.setBackgroundColor(getThemedColor(Theme.key_actionBarDefault));
        frameLayout.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.FILL_HORIZONTAL));
        frameLayout.addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 0, 48, 0, 0));

        rebuildAll();
        return fragmentView;
    }

    private RecyclerListView createList(Context context, ListAdapter adapter, boolean chats) {
        RecyclerListView list = new RecyclerListView(context);
        list.setSections();
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setVerticalScrollBarEnabled(false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((view, position) -> onRowClicked(chats ? chatRows : generalRows, view, position));
        if (chats) {
            list.setOnItemLongClickListener((view, position) -> {
                if (position < 0 || position >= chatRows.size()) return false;
                Row row = chatRows.get(position);
                if (row.type != T_USER || row.entry == null) return false;
                toggleSelected(row.entry.key);
                return true;
            });
        }
        return list;
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

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!selected.isEmpty()) {
            if (invoked) clearSelection();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        if (!selected.isEmpty()) return false;
        if (event != null && event.getY() <= ActionBar.getCurrentActionBarHeight() + AndroidUtilities.dp(48)) return true;
        return pager == null || pager.getCurrentPosition() == 0;
    }

    private void onPageShown(int position) {
        if (position == currentPage) return;
        currentPage = position;
        if (position != PAGE_CHATS) {
            if (!selected.isEmpty()) clearSelection();
            if (searchItem != null && searchItem.isSearchFieldVisible()) actionBar.closeSearchField();
        }
        updateSearchVisibility();
    }

    private void updateSearchVisibility() {
        if (searchItem == null) return;
        if (searchItem.isSearchFieldVisible()) return;
        boolean show = currentPage == PAGE_CHATS && ZephyrAutoReplyStore.isInstalledAndEnabled()
                && entries != null && entries.size() >= SEARCH_FROM;
        searchItem.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setQuery(String text) {
        String q = text == null ? null : text.trim().toLowerCase();
        if (q != null && q.isEmpty()) q = null;
        if (TextUtils.equals(q, query)) return;
        query = q;
        rebuildChats();
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
            final List<ZephyrAutoReplyStore.Entry> loaded = ZephyrAutoReplyStore.load();
            final ArrayList<TLObject> resolved = new ArrayList<>();
            for (ZephyrAutoReplyStore.Entry e : loaded) {
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
                resolved.add(o);
            }
            AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                entries = loaded;
                objects = resolved;
                // chats removed meanwhile (here or by the plugin) leave the selection
                HashSet<String> present = new HashSet<>();
                for (ZephyrAutoReplyStore.Entry e : loaded) present.add(e.key);
                if (selected.retainAll(present)) updateSelectionUi(false);
                rebuildAll();
                if (reloadAgain) {
                    reloadAgain = false;
                    reload();
                }
            });
        });
    }

    private void rebuildAll() {
        rebuildChats();
        rebuildGeneral();
        updateSearchVisibility();
    }

    private static boolean pluginReady() {
        return ZephyrAutoReplyStore.isInstalledAndEnabled();
    }

    private void addInstallRows(ArrayList<Row> rows) {
        PluginManager.InstalledPlugin plugin = PluginManager.INSTANCE.getInstalled(ZephyrAutoReplyStore.PLUGIN_ID);
        Row install = new Row(T_TEXT, A_INSTALL);
        install.text = s(plugin == null ? R.string.ZephyrArInstall : R.string.ZephyrArEnablePlugin);
        install.icon = R.drawable.msg_download;
        install.colorKey = Theme.key_color_blue;
        rows.add(install);
        Row info = new Row(T_INFO, A_NONE);
        info.text = s(R.string.ZephyrArInstallInfo);
        rows.add(info);
    }

    private static Row textRow(int action, int title, int icon, int colorKey) {
        Row r = new Row(T_TEXT, action);
        r.text = s(title);
        r.icon = icon;
        r.colorKey = colorKey;
        r.divider = true;
        return r;
    }

    private static Row header(String text) {
        Row r = new Row(T_HEADER, A_NONE);
        r.text = text;
        return r;
    }

    private static Row info(String text) {
        Row r = new Row(T_INFO, A_NONE);
        r.text = text;
        return r;
    }

    private boolean matches(ZephyrAutoReplyStore.Entry e, TLObject o) {
        if (query == null) return true;
        if (e.title != null && e.title.toLowerCase().contains(query)) return true;
        if (o instanceof TLRPC.User) {
            TLRPC.User u = (TLRPC.User) o;
            String q = query.startsWith("@") ? query.substring(1) : query;
            String un = UserObject.getPublicUsername(u);
            if (un != null && !q.isEmpty() && un.toLowerCase().contains(q)) return true;
            String name = UserObject.getUserName(u);
            return name != null && name.toLowerCase().contains(query);
        }
        return false;
    }

    private void rebuildChats() {
        chatRows.clear();
        if (!pluginReady()) {
            addInstallRows(chatRows);
        } else {
            Row add = textRow(A_ADD, R.string.ZephyrArAddChat, R.drawable.msg_contact_add, Theme.key_color_blue);
            add.divider = false;
            chatRows.add(add);
            chatRows.add(new Row(T_SHADOW, A_NONE));
            if (entries != null && entries.isEmpty()) {
                chatRows.add(info(s(R.string.ZephyrArEmptyTitle) + "\n\n" + s(R.string.ZephyrArEmptyInfo)));
            } else if (entries != null) {
                ArrayList<Integer> shown = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) {
                    TLObject o = objects != null && i < objects.size() ? objects.get(i) : null;
                    if (matches(entries.get(i), o)) shown.add(i);
                }
                // Chats are grouped per account (the store returns them sorted by account).
                int accounts = 0;
                for (int i = 0; i < entries.size(); i++) {
                    if (i == 0 || entries.get(i).account != entries.get(i - 1).account) accounts++;
                }
                if (shown.isEmpty()) {
                    chatRows.add(info(s(R.string.ZephyrAr2NothingFound)));
                } else {
                    if (accounts <= 1) chatRows.add(header(LocaleController.formatString(R.string.ZephyrAr2People, shown.size())));
                    for (int n = 0; n < shown.size(); n++) {
                        int i = shown.get(n);
                        ZephyrAutoReplyStore.Entry e = entries.get(i);
                        boolean first = n == 0 || e.account != entries.get(shown.get(n - 1)).account;
                        boolean last = n == shown.size() - 1 || e.account != entries.get(shown.get(n + 1)).account;
                        if (first && accounts > 1) {
                            int count = 0, active = 0;
                            for (ZephyrAutoReplyStore.Entry x : entries) {
                                if (x.account == e.account) {
                                    count++;
                                    if (x.enabled) active++;
                                }
                            }
                            if (n > 0) chatRows.add(new Row(T_SHADOW, A_NONE));
                            chatRows.add(header(LocaleController.formatString(R.string.ZephyrArAccountHeader, accountName(e.account), active, count)));
                        }
                        Row r = new Row(T_USER, A_CHAT);
                        r.entry = e;
                        r.object = objects != null && i < objects.size() ? objects.get(i) : null;
                        r.divider = !last;
                        chatRows.add(r);
                    }
                    chatRows.add(info(s(R.string.ZephyrAr2Hint)));
                }
            }
        }
        if (chatsAdapter != null) chatsAdapter.notifyDataSetChanged();
    }

    private void rebuildGeneral() {
        generalRows.clear();
        if (!pluginReady()) {
            addInstallRows(generalRows);
        } else {
            generalRows.add(header(s(R.string.ZephyrAr2SectionBot)));
            Row master = textRow(A_MASTER, R.string.ZephyrArMaster, R.drawable.msg_bot, Theme.key_color_green);
            master.check = true;
            master.divider = false;
            if (entries != null) {
                int active = 0;
                for (ZephyrAutoReplyStore.Entry e : entries) if (e.enabled) active++;
                master.sub = LocaleController.formatString(R.string.ZephyrAr2MasterSub, active, entries.size());
            }
            generalRows.add(master);
            generalRows.add(info(s(R.string.ZephyrArMasterInfo)));

            generalRows.add(header(s(R.string.ZephyrAr2SectionBehavior)));
            Row replyAll = textRow(A_REPLY_ALL, R.string.ZephyrAr2ReplyAll, R.drawable.msg_groups, Theme.key_color_blue);
            replyAll.check = true;
            replyAll.sub = s(R.string.ZephyrAr2ReplyAllSub);
            generalRows.add(replyAll);
            Row links = textRow(A_SKIP_LINKS, R.string.ZephyrAr2SkipLinks, R.drawable.msg_link, Theme.key_color_orange);
            links.check = true;
            generalRows.add(links);
            Row shortMsgs = textRow(A_SHORT, R.string.ZephyrAr2ShortMsgs, R.drawable.msg_discussion, Theme.key_color_lightblue);
            shortMsgs.check = true;
            shortMsgs.sub = s(R.string.ZephyrAr2ShortMsgsSub);
            generalRows.add(shortMsgs);
            Row revive = textRow(A_REVIVE, R.string.ZephyrAr2Revive, R.drawable.msg_retry, Theme.key_color_purple);
            revive.check = true;
            revive.sub = s(R.string.ZephyrAr2ReviveSub);
            revive.divider = false;
            generalRows.add(revive);
            generalRows.add(info(s(R.string.ZephyrAr2BehaviorInfo)));

            generalRows.add(header(s(R.string.ZephyrAr2SectionMore)));
            generalRows.add(textRow(A_ADD, R.string.ZephyrArAddChat, R.drawable.msg_contact_add, Theme.key_color_blue));
            generalRows.add(textRow(A_SETTINGS, R.string.ZephyrAr2AllSettings, R.drawable.msg_settings, Theme.key_color_cyan));
            Row version = textRow(A_VERSION, R.string.ZephyrAr2Version, R.drawable.msg_download, Theme.key_color_green);
            PluginManager.InstalledPlugin plugin = PluginManager.INSTANCE.getInstalled(ZephyrAutoReplyStore.PLUGIN_ID);
            version.value = plugin != null && plugin.version != null ? plugin.version : "";
            version.divider = false;
            generalRows.add(version);
            generalRows.add(new Row(T_SHADOW, A_NONE));
        }
        if (generalAdapter != null) generalAdapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- selection

    private void toggleSelected(String key) {
        if (!selected.remove(key)) selected.add(key);
        updateSelectionUi(true);
    }

    private void clearSelection() {
        selected.clear();
        updateSelectionUi(true);
    }

    private void selectAllShown() {
        for (Row r : chatRows) if (r.type == T_USER && r.entry != null) selected.add(r.entry.key);
        updateSelectionUi(true);
    }

    private void updateSelectionUi(boolean animated) {
        if (actionBar == null) return;
        int n = selected.size();
        if (n > 0) {
            if (selectedCountView != null) selectedCountView.setText(LocaleController.formatString(R.string.ZephyrAr2Selected, n));
            if (!actionBar.isActionModeShowed()) actionBar.showActionMode();
        } else if (actionBar.isActionModeShowed()) {
            actionBar.hideActionMode();
        }
        if (chatsList != null) {
            for (int i = 0; i < chatsList.getChildCount(); i++) {
                View v = chatsList.getChildAt(i);
                if (v instanceof UserCell && v.getTag() instanceof String) {
                    ((UserCell) v).setChecked(selected.contains((String) v.getTag()), animated);
                }
            }
        }
    }

    private void onMenuItem(int id) {
        if (selected.isEmpty() || getParentActivity() == null) return;
        final ArrayList<String> keys = new ArrayList<>(selected);
        switch (id) {
            case MENU_SELECT_ALL:
                selectAllShown();
                break;
            case MENU_ENABLE:
                ZephyrAutoReplyStore.setEnabled(keys, true);
                bulkDone(keys.size());
                break;
            case MENU_PAUSE:
                ZephyrAutoReplyStore.setEnabled(keys, false);
                bulkDone(keys.size());
                break;
            case MENU_MODE:
                chooseForSelected(s(R.string.ZephyrAr2Mode), ZephyrAutoReplyStore.K_MODE, keys);
                break;
            case MENU_GENDER:
                chooseForSelected(s(R.string.ZephyrAr2Gender), ZephyrAutoReplyStore.K_GENDER, keys);
                break;
            case MENU_LANG:
                chooseForSelected(s(R.string.ZephyrAr2Lang), ZephyrAutoReplyStore.K_RLANG, keys);
                break;
            case MENU_REACT:
                chooseForSelected(s(R.string.ZephyrAr2React), ZephyrAutoReplyStore.K_REACT, keys);
                break;
            case MENU_REMOVE:
                confirmRemoveSelected(keys);
                break;
            default:
                break;
        }
    }

    private void chooseForSelected(String title, String name, ArrayList<String> keys) {
        if (getParentActivity() == null) return;
        showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(), options(name), title, -1, (d, which) -> {
            ZephyrAutoReplyStore.setForChats(keys, name, which);
            bulkDone(keys.size());
        }));
    }

    private void confirmRemoveSelected(ArrayList<String> keys) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(s(R.string.ZephyrAr2BulkRemove));
        builder.setMessage(LocaleController.formatString(R.string.ZephyrAr2BulkRemoveConfirm, keys.size()));
        builder.setPositiveButton(s(R.string.Delete), (d, w) -> {
            ZephyrAutoReplyStore.remove(keys);
            bulkDone(keys.size());
        });
        builder.setNegativeButton(s(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) ((TextView) button).setTextColor(getThemedColor(Theme.key_text_RedBold));
    }

    private void bulkDone(int count) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.ZephyrAr2Done, count)).show();
        clearSelection();
        reload();
    }

    // ---------------------------------------------------------------- shared option names (also the chat screen)

    /** Choices of a per-chat setting, index = stored value. */
    static String[] options(String name) {
        if (ZephyrAutoReplyStore.K_MODE.equals(name)) {
            return new String[]{s(R.string.ZephyrArModeAuto), s(R.string.ZephyrArModeDraft), s(R.string.ZephyrArModeFriend)};
        } else if (ZephyrAutoReplyStore.K_GENDER.equals(name)) {
            return new String[]{s(R.string.ZephyrAr2GenderAuto), s(R.string.ZephyrAr2GenderMan), s(R.string.ZephyrAr2GenderWoman)};
        } else if (ZephyrAutoReplyStore.K_RLANG.equals(name)) {
            return new String[]{s(R.string.ZephyrAr2AsGeneral), s(R.string.ZephyrAr2LangAuto), s(R.string.ZephyrAr2LangUk),
                    s(R.string.ZephyrAr2LangRu), s(R.string.ZephyrAr2LangTheirs)};
        } else {
            return new String[]{s(R.string.ZephyrAr2AsGeneral), s(R.string.ZephyrAr2ReactOff), s(R.string.ZephyrAr2ReactOn)};
        }
    }

    static String optionName(String name, int value) {
        String[] o = options(name);
        return value >= 0 && value < o.length ? o[value] : o[0];
    }

    /** "@username · Auto · on · ♀": the short status of a chat (username only when object is a user that has one). */
    static String statusLine(ZephyrAutoReplyStore.Entry e, TLObject object) {
        StringBuilder sb = new StringBuilder();
        if (object instanceof TLRPC.User) {
            String un = UserObject.getPublicUsername((TLRPC.User) object);
            if (!TextUtils.isEmpty(un)) sb.append('@').append(un).append(" · ");
        }
        sb.append(optionName(ZephyrAutoReplyStore.K_MODE, e.mode)).append(" · ");
        sb.append(s(e.autoOff ? R.string.ZephyrAr2StatusErrors : e.enabled ? R.string.ZephyrAr2StatusOn : R.string.ZephyrAr2StatusPaused));
        if (e.gender == 1) sb.append(" · ♂");
        else if (e.gender == 2) sb.append(" · ♀");
        return sb.toString();
    }

    // ---------------------------------------------------------------- clicks

    private boolean isChecked(int action) {
        switch (action) {
            case A_MASTER:
                return ZephyrAutoReplyStore.isMaster();
            case A_REPLY_ALL:
                return ZephyrAutoReplyStore.getBool("reply_all", false);
            case A_SKIP_LINKS:
                return ZephyrAutoReplyStore.getBool("skip_links", true);
            case A_SHORT:
                return ZephyrAutoReplyStore.getBool("short_msgs", true);
            case A_REVIVE:
                return ZephyrAutoReplyStore.getBool("revive", false);
            default:
                return false;
        }
    }

    private static String boolKey(int action) {
        switch (action) {
            case A_REPLY_ALL:
                return "reply_all";
            case A_SKIP_LINKS:
                return "skip_links";
            case A_SHORT:
                return "short_msgs";
            default:
                return "revive";
        }
    }

    private void onRowClicked(ArrayList<Row> rows, View view, int position) {
        if (position < 0 || position >= rows.size()) return;
        Row row = rows.get(position);
        switch (row.action) {
            case A_MASTER: {
                boolean value = !ZephyrAutoReplyStore.isMaster();
                ZephyrAutoReplyStore.setMaster(value);
                if (view instanceof TextCell) ((TextCell) view).setChecked(value);
                break;
            }
            case A_REPLY_ALL:
            case A_SKIP_LINKS:
            case A_SHORT:
            case A_REVIVE: {
                boolean value = !isChecked(row.action);
                ZephyrAutoReplyStore.setBool(boolKey(row.action), value);
                if (view instanceof TextCell) ((TextCell) view).setChecked(value);
                break;
            }
            case A_ADD:
                showAddDialog();
                break;
            case A_SETTINGS:
                presentFragment(new ZephyrPluginSettingsActivity(ZephyrAutoReplyStore.PLUGIN_ID));
                break;
            case A_INSTALL:
            case A_VERSION:
                presentFragment(new ZephyrPluginDetailsActivity(ZephyrAutoReplyStore.PLUGIN_ID));
                break;
            case A_CHAT:
                if (row.entry == null) break;
                if (!selected.isEmpty()) toggleSelected(row.entry.key);
                else presentFragment(new ZephyrAutoReplyChatSettingsActivity(row.entry.key));
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
        private final ArrayList<Row> rows;

        ListAdapter(Context context, ArrayList<Row> rows) {
            this.context = context;
            this.rows = rows;
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
            return t == T_TEXT || t == T_USER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case T_HEADER:
                    view = new HeaderCell(context);
                    break;
                case T_TEXT:
                    view = new TextCell(context);
                    break;
                case T_USER:
                    view = new UserCell(context, 6, 1, false);
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
                case T_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    int color = getThemedColor(row.colorKey);
                    if (row.check) {
                        cell.setTextAndCheckAndColorfulIcon(row.text, isChecked(row.action), row.icon, color, row.divider);
                    } else {
                        cell.setTextAndValueAndColorfulIcon(row.text, row.value == null ? "" : row.value, false, row.icon, color, color, row.divider);
                    }
                    cell.setSubtitle(row.sub);
                    cell.heightDp = TextUtils.isEmpty(row.sub) ? 50 : 60;
                    cell.requestLayout();
                    break;
                }
                case T_USER: {
                    ZephyrAutoReplyStore.Entry e = row.entry;
                    UserCell uc = (UserCell) holder.itemView;
                    int statusKey = e.autoOff ? Theme.key_text_RedRegular
                            : e.enabled ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText;
                    int statusColor = getThemedColor(statusKey);
                    uc.setStatusColors(statusColor, statusColor);
                    // A chat stored without a real title (only its id) shows the cached user/chat name.
                    boolean noTitle = e.title == null || e.title.isEmpty() || e.title.equals(String.valueOf(e.dialogId)) || e.title.equals(e.key);
                    uc.setData(row.object, noTitle && row.object != null ? null : e.title, statusLine(e, row.object), 0, row.divider);
                    uc.setTag(e.key);
                    uc.setChecked(selected.contains(e.key), false);
                    break;
                }
                case T_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
                    break;
                default:
                    break;
            }
        }
    }
}
