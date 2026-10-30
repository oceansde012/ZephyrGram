package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zephyr.ZephyrSavedMessages;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Lists the recorded versions of an edited message (newest first) with the changed part highlighted,
 * and shows when a server-deleted message was captured. Data comes from ZephyrSavedMessages.
 */
public class ZephyrEditHistoryActivity extends BaseFragment {

    private final int account;
    private final long dialogId;
    private final int messageId;
    private final String currentText;
    private final int messageDate;
    private final int editDate;
    private final boolean deleted;

    private LinearLayout content;
    private Context context;

    public ZephyrEditHistoryActivity(int account, long dialogId, int messageId, String currentText, int messageDate, int editDate, boolean deleted) {
        super(new Bundle());
        this.account = account;
        this.dialogId = dialogId;
        this.messageId = messageId;
        this.currentText = currentText == null ? "" : currentText;
        this.messageDate = messageDate;
        this.editDate = editDate;
        this.deleted = deleted;
    }

    @Override
    public View createView(Context context) {
        this.context = context;
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ZephyrEditHistory));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        addNote(LocaleController.getString(R.string.ZephyrHistoryLoading));
        Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<ZephyrSavedMessages.EditRevision> edits = ZephyrSavedMessages.getInstance().getEdits(account, dialogId, messageId);
            final int deletedAt = ZephyrSavedMessages.getInstance().getDeletedAt(account, dialogId, messageId);
            AndroidUtilities.runOnUIThread(() -> {
                if (content != null) {
                    populate(edits, deletedAt);
                }
            });
        });
        return fragmentView;
    }

    private void populate(ArrayList<ZephyrSavedMessages.EditRevision> edits, int deletedAt) {
        content.removeAllViews();
        if (deleted || deletedAt != 0) {
            String when = deletedAt != 0
                    ? LocaleController.formatDateChat(deletedAt, true) + ", " + LocaleController.getInstance().getFormatterDay().format(deletedAt * 1000L)
                    : "-";
            addNote(LocaleController.formatString(R.string.ZephyrEditHistoryDeleted, when));
        }
        if (edits.isEmpty()) {
            if (!deleted && deletedAt == 0) {
                addNote(LocaleController.getString(R.string.ZephyrEditHistoryEmpty));
            }
            if (!TextUtils.isEmpty(currentText)) {
                addCard(LocaleController.getString(R.string.ZephyrEditHistoryCurrent), messageDate, currentText, null);
            }
            return;
        }
        // newest first: current text, then revisions from the latest to the first one
        String newer = currentText;
        int newerDate = editDate != 0 ? editDate : messageDate;
        addCard(LocaleController.getString(R.string.ZephyrEditHistoryCurrent), newerDate, newer, edits.get(edits.size() - 1).text);
        for (int a = edits.size() - 1; a >= 0; a--) {
            ZephyrSavedMessages.EditRevision revision = edits.get(a);
            String older = a > 0 ? edits.get(a - 1).text : null;
            String title = a == 0
                    ? LocaleController.getString(R.string.ZephyrEditHistoryOriginal)
                    : LocaleController.formatString(R.string.ZephyrEditHistoryVersion, a + 1);
            addCard(title, revision.editDate, revision.text == null ? "" : revision.text, older);
        }
    }

    private void addNote(CharSequence text) {
        TextView note = new TextView(context);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        note.setText(text);
        note.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        content.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    /** olderText: the previous version, used to highlight what this version changed (null = no highlight). */
    private void addCard(String title, int date, String text, String olderText) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(8));

        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        String time = date != 0
                ? LocaleController.formatDateChat(date, true) + ", " + LocaleController.getInstance().getFormatterDay().format(date * 1000L)
                : "";
        header.setText(TextUtils.isEmpty(time) ? title : title + "  -  " + time);
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView body = new TextView(context);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        body.setTextIsSelectable(true);
        if (text.isEmpty()) {
            body.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
            body.setText(LocaleController.getString(R.string.ZephyrEditHistoryEmptyText));
        } else {
            body.setText(highlight(text, olderText));
        }
        body.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        card.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView copy = new TextView(context);
        copy.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        copy.setTypeface(AndroidUtilities.bold());
        copy.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        copy.setText(LocaleController.getString(R.string.ZephyrEditHistoryCopy));
        copy.setPadding(0, AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(6));
        copy.setOnClickListener(v -> {
            if (AndroidUtilities.addToClipboard(text)) {
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
            }
        });
        card.addView(copy, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        content.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
    }

    /** Highlights the middle part of text that differs from older (common prefix and suffix are kept plain). */
    private CharSequence highlight(String text, String older) {
        if (older == null || older.equals(text)) {
            return text;
        }
        int prefix = 0;
        int max = Math.min(text.length(), older.length());
        while (prefix < max && text.charAt(prefix) == older.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix && text.charAt(text.length() - 1 - suffix) == older.charAt(older.length() - 1 - suffix)) {
            suffix++;
        }
        int end = text.length() - suffix;
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        if (end > prefix) {
            int color = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_color_green), 70);
            builder.setSpan(new BackgroundColorSpan(color), prefix, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        content = null;
    }
}
