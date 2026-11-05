package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.zephyr.ZephyrAutoReplyStore;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Settings of ONE person in the "ai_autoreply" plugin: on/off, mode, the other person's gender, reply language,
 * reactions, what the owner knows about them (lore), the bot's own notes (learned), an own prompt and the three
 * consents (memory, photos/stickers, voice). Every value is a per-chat key of the plugin's store, written through
 * {@link ZephyrAutoReplyStore}; the running plugin is told to reload after each write. Writes from elsewhere (the
 * plugin, the list screen) are followed with a preference listener while the screen is open.
 */
public class ZephyrAutoReplyChatSettingsActivity extends BaseFragment {
    private static final int T_PROFILE = 0, T_HEADER = 1, T_TEXT = 2, T_INFO = 3, T_SHADOW = 4, T_DANGER = 5;
    private static final int A_NONE = 0, A_ENABLED = 1, A_MODE = 2, A_GENDER = 3, A_LANG = 4, A_REACT = 5,
            A_LORE = 6, A_LEARNED = 7, A_PROMPT = 8, A_MEMORY = 9, A_MEDIA = 10, A_VOICE = 11, A_REMOVE = 12;

    private static final class Row {
        final int type, action;
        String text;
        boolean divider;

        Row(int type, int action, String text) {
            this.type = type;
            this.action = action;
            this.text = text;
        }
    }

    private final String chatKey;
    private ZephyrAutoReplyStore.Entry entry;
    private TLObject object;
    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;
    private final Runnable reloadRunnable = this::reload;
    /** Kept in a field: SharedPreferences holds its listeners weakly. Called on the main thread. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    public ZephyrAutoReplyChatSettingsActivity(String chatKey) {
        this.chatKey = chatKey;
        // "_" + sk(chatKey): every per-chat key of this chat ends with it
        final String suffix = ZephyrAutoReplyStore.chatPrefKey("", chatKey);
        this.prefsListener = (sp, key) -> {
            if (key == null || "chats".equals(key) || key.endsWith(suffix)) reloadSoon();
        };
    }

    private static String s(int id) {
        return LocaleController.getString(id);
    }

    @Override
    public boolean onFragmentCreate() {
        try {
            ZephyrAutoReplyStore.prefs().registerOnSharedPreferenceChangeListener(prefsListener);
        } catch (Throwable ignore) {
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
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
        actionBar.setTitle(s(R.string.ZephyrAr2ChatTitle));
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
        listView.setOnItemClickListener((view, position) -> onRowClicked(view, position));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        reload();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reloadSoon() {
        AndroidUtilities.cancelRunOnUIThread(reloadRunnable);
        AndroidUtilities.runOnUIThread(reloadRunnable, 150);
    }

    /** Reads this chat from the store and the cached user/chat for the header. Cheap: in-memory preferences. */
    private void reload() {
        entry = ZephyrAutoReplyStore.find(chatKey);
        object = null;
        if (entry != null) {
            try {
                MessagesController mc = MessagesController.getInstance(entry.account);
                if (entry.dialogId > 0) object = mc.getUser(entry.dialogId);
                else if (entry.dialogId < 0) object = mc.getChat(-entry.dialogId);
            } catch (Throwable ignore) {
            }
        }
        rebuild();
    }

    private void rebuild() {
        rows.clear();
        if (entry == null) {
            rows.add(new Row(T_INFO, A_NONE, s(R.string.ZephyrAr2Gone)));
        } else {
            rows.add(new Row(T_PROFILE, A_NONE, null));

            rows.add(new Row(T_HEADER, A_NONE, s(R.string.ZephyrAr2SectionMain)));
            addText(A_ENABLED, R.string.ZephyrAr2Enabled, true);
            addText(A_MODE, R.string.ZephyrAr2Mode, true);
            addText(A_GENDER, R.string.ZephyrAr2Gender, true);
            addText(A_LANG, R.string.ZephyrAr2Lang, true);
            addText(A_REACT, R.string.ZephyrAr2React, false);
            rows.add(new Row(T_SHADOW, A_NONE, null));

            rows.add(new Row(T_HEADER, A_NONE, s(R.string.ZephyrAr2SectionKnow)));
            addText(A_LORE, R.string.ZephyrAr2Lore, true);
            addText(A_LEARNED, R.string.ZephyrAr2Learned, true);
            addText(A_PROMPT, R.string.ZephyrAr2Prompt, false);
            rows.add(new Row(T_INFO, A_NONE, s(R.string.ZephyrAr2KnowInfo)));

            rows.add(new Row(T_HEADER, A_NONE, s(R.string.ZephyrAr2SectionConsent)));
            addText(A_MEMORY, R.string.ZephyrAr2Memory, true);
            addText(A_MEDIA, R.string.ZephyrAr2Media, true);
            addText(A_VOICE, R.string.ZephyrAr2Voice, false);
            rows.add(new Row(T_INFO, A_NONE, s(R.string.ZephyrAr2ConsentInfo)));

            rows.add(new Row(T_DANGER, A_REMOVE, s(R.string.ZephyrArRemove)));
            rows.add(new Row(T_SHADOW, A_NONE, null));
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void addText(int action, int title, boolean divider) {
        Row r = new Row(T_TEXT, action, s(title));
        r.divider = divider;
        rows.add(r);
    }

    private String title() {
        if (entry == null) return "";
        boolean noTitle = entry.title == null || entry.title.isEmpty() || entry.title.equals(String.valueOf(entry.dialogId)) || entry.title.equals(entry.key);
        if (noTitle && object instanceof TLRPC.User) return UserObject.getUserName((TLRPC.User) object);
        if (noTitle && object instanceof TLRPC.Chat) return ((TLRPC.Chat) object).title;
        return entry.title == null ? "" : entry.title;
    }

    private static boolean isSwitch(int action) {
        return action == A_ENABLED || action == A_MEMORY || action == A_MEDIA || action == A_VOICE;
    }

    private static String switchKey(int action) {
        return action == A_MEMORY ? ZephyrAutoReplyStore.K_MEMORY
                : action == A_MEDIA ? ZephyrAutoReplyStore.K_SEE_MEDIA : ZephyrAutoReplyStore.K_VOICE;
    }

    private static String textKey(int action) {
        return action == A_LORE ? ZephyrAutoReplyStore.K_LORE
                : action == A_LEARNED ? ZephyrAutoReplyStore.K_LEARNED : ZephyrAutoReplyStore.K_PROMPT;
    }

    private boolean isChecked(int action) {
        if (entry == null) return false;
        if (action == A_ENABLED) return entry.enabled;
        return ZephyrAutoReplyStore.getChatBool(chatKey, switchKey(action), false);
    }

    private static String preview(String text) {
        if (text == null) return "";
        String t = text.trim().replaceAll("\\s*\\n\\s*", " / ");
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }

    // ---------------------------------------------------------------- clicks

    private void onRowClicked(View view, int position) {
        if (position < 0 || position >= rows.size() || entry == null) return;
        Row row = rows.get(position);
        switch (row.action) {
            case A_ENABLED: {
                boolean value = !entry.enabled;
                ZephyrAutoReplyStore.setEnabled(chatKey, value);
                if (view instanceof TextCell) ((TextCell) view).setChecked(value);
                reloadSoon();
                break;
            }
            case A_MEMORY:
            case A_MEDIA:
            case A_VOICE: {
                boolean value = !isChecked(row.action);
                ZephyrAutoReplyStore.setChatValue(chatKey, switchKey(row.action), value);
                if (view instanceof TextCell) ((TextCell) view).setChecked(value);
                break;
            }
            case A_MODE:
                choose(row.text, ZephyrAutoReplyChatsActivity.options(ZephyrAutoReplyStore.K_MODE), ZephyrAutoReplyStore.K_MODE, entry.mode);
                break;
            case A_GENDER:
                choose(row.text, ZephyrAutoReplyChatsActivity.options(ZephyrAutoReplyStore.K_GENDER), ZephyrAutoReplyStore.K_GENDER, entry.gender);
                break;
            case A_LANG:
                choose(row.text, ZephyrAutoReplyChatsActivity.options(ZephyrAutoReplyStore.K_RLANG), ZephyrAutoReplyStore.K_RLANG, entry.rlang);
                break;
            case A_REACT:
                choose(row.text, ZephyrAutoReplyChatsActivity.options(ZephyrAutoReplyStore.K_REACT), ZephyrAutoReplyStore.K_REACT, entry.react);
                break;
            case A_LORE:
                editText(row.text, s(R.string.ZephyrAr2LoreHint), ZephyrAutoReplyStore.K_LORE);
                break;
            case A_LEARNED:
                editText(row.text, s(R.string.ZephyrAr2LearnedHint), ZephyrAutoReplyStore.K_LEARNED);
                break;
            case A_PROMPT:
                editText(row.text, s(R.string.ZephyrAr2PromptHint), ZephyrAutoReplyStore.K_PROMPT);
                break;
            case A_REMOVE:
                confirmRemove();
                break;
            default:
                break;
        }
    }

    private void choose(String title, String[] options, String name, int current) {
        if (getParentActivity() == null) return;
        showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(), options, title, current, (d, which) -> {
            ZephyrAutoReplyStore.setChatValue(chatKey, name, which);
            reload();
        }));
    }

    private void editText(String title, String hint, String name) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        final String current = ZephyrAutoReplyStore.getChatString(chatKey, name);
        final EditText edit = new EditText(activity);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        edit.setHint(hint);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setMinLines(3);
        edit.setMaxLines(10);
        edit.setGravity(Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        edit.setText(current);
        edit.setSelection(edit.length());
        FrameLayout frame = new FrameLayout(activity);
        frame.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 8, 24, 0));
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title);
        builder.setView(frame);
        builder.setPositiveButton(s(R.string.Save), (d, w) -> {
            ZephyrAutoReplyStore.setChatValue(chatKey, name, edit.getText().toString().trim());
            reload();
        });
        builder.setNegativeButton(s(R.string.Cancel), null);
        if (!current.isEmpty()) {
            builder.setNeutralButton(s(R.string.ZephyrAr2Clear), (d, w) -> {
                ZephyrAutoReplyStore.setChatValue(chatKey, name, "");
                reload();
            });
        }
        showDialog(builder.create());
    }

    private void confirmRemove() {
        if (getParentActivity() == null || entry == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(s(R.string.ZephyrArRemove));
        builder.setMessage(LocaleController.formatString(R.string.ZephyrAr2RemoveConfirm, title()));
        builder.setPositiveButton(s(R.string.Delete), (d, w) -> {
            ZephyrAutoReplyStore.remove(chatKey);
            finishFragment();
        });
        builder.setNegativeButton(s(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) ((TextView) button).setTextColor(getThemedColor(Theme.key_text_RedBold));
    }

    // ---------------------------------------------------------------- header

    /** Large avatar, name, @username and a status chip, on the gray background above the cards. */
    private class ProfileView extends LinearLayout {
        private final BackupImageView avatarView;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView nameView, subView, chipView;

        ProfileView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
            setTag(RecyclerListView.TAG_NOT_SECTION);

            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(AndroidUtilities.dp(45));
            avatarDrawable.setTextSize(AndroidUtilities.dp(32));
            addView(avatarView, LayoutHelper.createLinear(90, 90, Gravity.CENTER_HORIZONTAL));

            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setGravity(Gravity.CENTER);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

            subView = new TextView(context);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subView.setGravity(Gravity.CENTER);
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            addView(subView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));

            chipView = new TextView(context);
            chipView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            chipView.setTypeface(AndroidUtilities.bold());
            chipView.setGravity(Gravity.CENTER);
            chipView.setSingleLine(true);
            chipView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(4), AndroidUtilities.dp(12), AndroidUtilities.dp(5));
            addView(chipView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));
        }

        void bind() {
            if (entry == null) return;
            String name = title();
            if (object != null) {
                avatarDrawable.setInfo(entry.account, object);
                avatarView.setForUserOrChat(object, avatarDrawable);
            } else {
                avatarDrawable.setInfo(entry.dialogId, name, null);
                avatarView.setImageDrawable(avatarDrawable);
            }
            nameView.setText(name);
            String username = object instanceof TLRPC.User ? UserObject.getPublicUsername((TLRPC.User) object) : null;
            if (!TextUtils.isEmpty(username)) {
                subView.setText("@" + username);
                subView.setVisibility(VISIBLE);
            } else {
                subView.setVisibility(GONE);
            }
            int colorKey = entry.autoOff ? Theme.key_text_RedRegular
                    : entry.enabled ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText;
            int color = getThemedColor(colorKey);
            chipView.setTextColor(color);
            chipView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(14), Theme.multAlpha(color, 0.12f)));
            chipView.setText(ZephyrAutoReplyChatsActivity.statusLine(entry, null));
        }
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
            return t == T_TEXT || t == T_DANGER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case T_PROFILE:
                    view = new ProfileView(context);
                    break;
                case T_HEADER:
                    view = new HeaderCell(context);
                    break;
                case T_TEXT:
                    view = new TextCell(context);
                    break;
                case T_DANGER: {
                    TextCell cell = new TextCell(context);
                    cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
                    view = cell;
                    break;
                }
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
                case T_PROFILE:
                    ((ProfileView) holder.itemView).bind();
                    break;
                case T_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.text);
                    break;
                case T_TEXT:
                    bindText((TextCell) holder.itemView, row);
                    break;
                case T_DANGER: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setTextAndIcon(row.text, R.drawable.msg_delete, false);
                    break;
                }
                case T_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
                    break;
                default:
                    break;
            }
        }

        private void bindText(TextCell cell, Row row) {
            if (entry == null) return;
            String sub = null;
            if (isSwitch(row.action)) {
                int icon, colorKey;
                if (row.action == A_ENABLED) {
                    icon = R.drawable.msg_bot;
                    colorKey = Theme.key_color_green;
                    if (entry.autoOff) sub = s(R.string.ZephyrAr2StatusErrors);
                } else if (row.action == A_MEMORY) {
                    icon = R.drawable.msg_saved;
                    colorKey = Theme.key_color_blue;
                } else if (row.action == A_MEDIA) {
                    icon = R.drawable.msg_photos;
                    colorKey = Theme.key_color_lightgreen;
                } else {
                    icon = R.drawable.msg_voicechat;
                    colorKey = Theme.key_color_orange;
                }
                cell.setTextAndCheckAndColorfulIcon(row.text, isChecked(row.action), icon, getThemedColor(colorKey), row.divider);
            } else {
                int icon, colorKey;
                String value = "";
                switch (row.action) {
                    case A_MODE:
                        icon = R.drawable.msg_discussion;
                        colorKey = Theme.key_color_purple;
                        value = ZephyrAutoReplyChatsActivity.optionName(ZephyrAutoReplyStore.K_MODE, entry.mode);
                        break;
                    case A_GENDER:
                        icon = R.drawable.msg_contacts;
                        colorKey = Theme.key_color_orange;
                        value = ZephyrAutoReplyChatsActivity.optionName(ZephyrAutoReplyStore.K_GENDER, entry.gender);
                        break;
                    case A_LANG:
                        icon = R.drawable.msg_language;
                        colorKey = Theme.key_color_lightblue;
                        value = ZephyrAutoReplyChatsActivity.optionName(ZephyrAutoReplyStore.K_RLANG, entry.rlang);
                        break;
                    case A_REACT:
                        icon = R.drawable.msg_reactions;
                        colorKey = Theme.key_color_red;
                        value = ZephyrAutoReplyChatsActivity.optionName(ZephyrAutoReplyStore.K_REACT, entry.react);
                        break;
                    case A_LORE:
                        icon = R.drawable.msg_openprofile;
                        colorKey = Theme.key_color_blue;
                        break;
                    case A_LEARNED:
                        icon = R.drawable.msg_stories_caption;
                        colorKey = Theme.key_color_cyan;
                        break;
                    default:
                        icon = R.drawable.msg_edit;
                        colorKey = Theme.key_color_yellow;
                        break;
                }
                if (row.action == A_LORE || row.action == A_LEARNED || row.action == A_PROMPT) {
                    String text = preview(ZephyrAutoReplyStore.getChatString(chatKey, textKey(row.action)));
                    sub = text.isEmpty() ? s(R.string.ZephyrAr2NotSet) : text;
                }
                int color = getThemedColor(colorKey);
                cell.setTextAndValueAndColorfulIcon(row.text, value, false, icon, color, color, row.divider);
            }
            cell.setSubtitle(sub);
            cell.heightDp = TextUtils.isEmpty(sub) ? 50 : 60;
            cell.requestLayout();
        }
    }
}
