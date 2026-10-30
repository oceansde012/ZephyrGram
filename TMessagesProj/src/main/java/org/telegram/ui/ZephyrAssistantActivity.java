package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.ZephyrAiClient;
import org.telegram.messenger.zephyr.ZephyrAiStore;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat-style screen for the ZephyrGram AI server. Simple custom views (no RecyclerView): messages are small
 * LinearLayout bubbles inside a ScrollView, the streaming reply is re-rendered in place (throttled).
 */
public class ZephyrAssistantActivity extends BaseFragment {

    private static final int MENU_NEW_CHAT = 1;
    private static final int MENU_SETTINGS = 2;

    private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`\\n]+)`");

    private final ZephyrAiStore store = ZephyrAiStore.get();
    private final ArrayList<LinearLayout> bubbles = new ArrayList<>();
    private final String prefill;

    private ScrollView scrollView;
    private LinearLayout container;
    private TextView emptyView;
    private LinearLayout errorBox;
    private TextView errorText;
    private TextView errorButton;
    private EditText input;
    private ImageView sendButton;

    private ZephyrAiClient.Call call;
    private boolean busy;
    private boolean destroyed;
    private Runnable pendingRender;
    private long lastRender;
    private Runnable errorAction;
    private int errorCode;

    public ZephyrAssistantActivity() {
        this(null);
    }

    /** Opens the assistant with the input prefilled (used by the message menu "Ask AI"). */
    public ZephyrAssistantActivity(String prefill) {
        this.prefill = prefill;
    }

    @Override
    public View createView(Context context) {
        ZephyrConfig.load();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ZephyrAi));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_NEW_CHAT) {
                    confirmNewChat();
                } else if (id == MENU_SETTINGS) {
                    presentFragment(new ZephyrAssistantSettingsActivity());
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_NEW_CHAT, R.drawable.msg_reset, LocaleController.getString(R.string.ZephyrAiNewChat));
        other.addSubItem(MENU_SETTINGS, R.drawable.msg_settings, LocaleController.getString(R.string.ZephyrAiOpenSettings));

        SizeNotifierFrameLayout root = new SizeNotifierFrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        FrameLayout scrollHolder = new FrameLayout(context);
        column.addView(scrollHolder, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        scrollView = new ScrollView(context);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(true);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        scrollView.addView(container, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollHolder.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.ZephyrAiEmpty));
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        scrollHolder.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        errorBox = new LinearLayout(context);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        errorBox.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        errorBox.setVisibility(View.GONE);
        errorText = new TextView(context);
        errorText.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        errorText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorBox.addView(errorText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        errorButton = new TextView(context);
        errorButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        errorButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorButton.setTypeface(AndroidUtilities.bold());
        errorButton.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(4));
        errorButton.setOnClickListener(v -> {
            if (errorAction != null) {
                errorAction.run();
            }
        });
        errorBox.addView(errorButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        column.addView(errorBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.BOTTOM);
        inputRow.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        inputRow.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        column.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        input = new EditText(context);
        input.setHint(LocaleController.getString(R.string.ZephyrAiInputHint));
        input.setHintTextColor(Theme.getColor(Theme.key_chat_messagePanelHint));
        input.setTextColor(Theme.getColor(Theme.key_chat_messagePanelText));
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setBackgroundDrawable(null);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(6);
        input.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSendButton();
            }
        });
        inputRow.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        sendButton = new ImageView(context);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setBackgroundDrawable(Theme.createSelectorDrawable(Theme.multAlpha(Theme.getColor(Theme.key_chat_messagePanelSend), 0.15f), Theme.RIPPLE_MASK_CIRCLE_20DP));
        sendButton.setOnClickListener(v -> {
            if (busy) {
                stopGeneration();
            } else {
                send();
            }
        });
        inputRow.addView(sendButton, LayoutHelper.createLinear(48, 48));

        for (ZephyrAiStore.Msg m : store.messages) {
            bubbles.add(addBubble(m));
        }
        if (prefill != null && !prefill.isEmpty()) {
            input.setText(prefill);
            input.setSelection(input.length());
        }
        updateSendButton();
        updateEmpty();
        scrollToBottom();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!ZephyrConfig.isAiConfigured() && !busy) {
            showError(ZephyrAiClient.ERR_NOT_CONFIGURED, null);
        } else if (errorCode == ZephyrAiClient.ERR_NOT_CONFIGURED && ZephyrConfig.isAiConfigured()) {
            errorBox.setVisibility(View.GONE);
            errorCode = 0;
        }
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (call != null) {
            call.cancel();
        }
        if (pendingRender != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingRender);
        }
        store.save();
        super.onFragmentDestroy();
    }

    private void updateSendButton() {
        if (sendButton == null) {
            return;
        }
        boolean hasText = input.getText().toString().trim().length() > 0;
        sendButton.setImageResource(busy ? R.drawable.msg_cancel : R.drawable.msg_send);
        sendButton.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelSend), android.graphics.PorterDuff.Mode.MULTIPLY));
        sendButton.setAlpha(busy || hasText ? 1f : 0.4f);
        sendButton.setContentDescription(LocaleController.getString(busy ? R.string.ZephyrAiStop : R.string.ZephyrAiSend));
    }

    private void updateEmpty() {
        emptyView.setVisibility(store.messages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void confirmNewChat() {
        if (getParentActivity() == null) {
            return;
        }
        if (store.messages.isEmpty() && !busy) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.ZephyrAiNewChat));
        builder.setMessage(LocaleController.getString(R.string.ZephyrAiClearConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> newChat());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void newChat() {
        if (call != null) {
            call.cancel();
            call = null;
        }
        busy = false;
        store.clear();
        bubbles.clear();
        container.removeAllViews();
        errorBox.setVisibility(View.GONE);
        updateSendButton();
        updateEmpty();
    }

    private void stopGeneration() {
        if (call != null) {
            call.cancel();
            call = null;
        }
        busy = false;
        dropEmptyAssistant();
        store.save();
        updateSendButton();
    }

    private void dropEmptyAssistant() {
        int last = store.messages.size() - 1;
        if (last >= 0) {
            ZephyrAiStore.Msg m = store.messages.get(last);
            if ("assistant".equals(m.role) && TextUtils.isEmpty(m.content)) {
                store.messages.remove(last);
                if (last < bubbles.size()) {
                    container.removeView(bubbles.remove(last));
                }
            }
        }
        updateEmpty();
    }

    private void send() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty() || busy) {
            return;
        }
        if (!ZephyrConfig.isAiConfigured()) {
            showError(ZephyrAiClient.ERR_NOT_CONFIGURED, null);
            return;
        }
        errorBox.setVisibility(View.GONE);
        input.setText("");
        store.messages.add(new ZephyrAiStore.Msg("user", text));
        final org.json.JSONArray request = store.buildRequest();
        final ZephyrAiStore.Msg reply = new ZephyrAiStore.Msg("assistant", "");
        store.messages.add(reply);
        bubbles.add(addBubble(store.messages.get(store.messages.size() - 2)));
        final LinearLayout replyBubble = addBubble(reply);
        bubbles.add(replyBubble);
        store.save();
        updateEmpty();
        scrollToBottom();
        busy = true;
        updateSendButton();

        call = ZephyrAiClient.chat(request, new ZephyrAiClient.StreamListener() {
            @Override
            public void onDelta(String delta) {
                if (destroyed) {
                    return;
                }
                reply.content += delta;
                scheduleRender(replyBubble, reply);
            }

            @Override
            public void onDone() {
                if (destroyed) {
                    return;
                }
                busy = false;
                call = null;
                renderNow(replyBubble, reply);
                store.save();
                updateSendButton();
            }

            @Override
            public void onError(int code, String message) {
                if (destroyed) {
                    return;
                }
                busy = false;
                call = null;
                if (TextUtils.isEmpty(reply.content)) {
                    dropEmptyAssistant();
                } else {
                    renderNow(replyBubble, reply);
                }
                store.save();
                updateSendButton();
                showError(code, message);
            }
        });
    }

    private void scheduleRender(LinearLayout bubble, ZephyrAiStore.Msg msg) {
        long now = System.currentTimeMillis();
        if (now - lastRender >= 80) {
            renderNow(bubble, msg);
            return;
        }
        if (pendingRender == null) {
            pendingRender = () -> {
                pendingRender = null;
                if (!destroyed) {
                    renderNow(bubble, msg);
                }
            };
            AndroidUtilities.runOnUIThread(pendingRender, 80);
        }
    }

    private void renderNow(LinearLayout bubble, ZephyrAiStore.Msg msg) {
        if (pendingRender != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingRender);
            pendingRender = null;
        }
        lastRender = System.currentTimeMillis();
        boolean atBottom = isNearBottom();
        fillBubble(bubble, msg);
        if (atBottom) {
            scrollToBottom();
        }
    }

    private boolean isNearBottom() {
        if (scrollView == null || container.getHeight() == 0) {
            return true;
        }
        int diff = container.getBottom() - (scrollView.getScrollY() + scrollView.getHeight());
        return diff < AndroidUtilities.dp(120);
    }

    private void scrollToBottom() {
        if (scrollView != null) {
            scrollView.post(() -> scrollView.scrollTo(0, Math.max(0, container.getBottom() - scrollView.getHeight())));
        }
    }

    // ---------------------------------------------------------------------------------------------------- errors

    private void showError(int code, String message) {
        if (errorBox == null) {
            return;
        }
        String text;
        String buttonText = null;
        Runnable action = null;
        switch (code) {
            case ZephyrAiClient.ERR_NOT_CONFIGURED:
                text = LocaleController.getString(R.string.ZephyrAiErrNotConfigured);
                buttonText = LocaleController.getString(R.string.ZephyrAiOpenSettings);
                action = () -> presentFragment(new ZephyrAssistantSettingsActivity());
                break;
            case ZephyrAiClient.ERR_UNAUTHORIZED:
                if (ZephyrConfig.getAiBotUsername().isEmpty()) {
                    // No access bot exists: send the user to the token paste dialog instead of a bot that is not there.
                    text = LocaleController.getString(R.string.ZephyrAiErrUnauthorizedNoBot);
                    buttonText = LocaleController.getString(R.string.ZephyrShareImport);
                    action = () -> presentFragment(ZephyrAssistantSettingsActivity.forImport());
                } else {
                    text = LocaleController.getString(R.string.ZephyrAiErrUnauthorized);
                    buttonText = LocaleController.getString(R.string.ZephyrAiRequestAccess);
                    action = () -> {
                        if (!ZephyrAssistantSettingsActivity.openAccessBot(this)) {
                            presentFragment(ZephyrAssistantSettingsActivity.forImport());
                        }
                    };
                }
                break;
            case ZephyrAiClient.ERR_SERVER_DOWN:
                text = LocaleController.formatString(R.string.ZephyrAiErrServerDown, message == null || message.isEmpty() ? "?" : message);
                break;
            case ZephyrAiClient.ERR_RATE_LIMIT:
                text = LocaleController.getString(R.string.ZephyrAiErrRate);
                break;
            case ZephyrAiClient.ERR_STARTING:
                text = LocaleController.getString(R.string.ZephyrAiErrStarting);
                break;
            case ZephyrAiClient.ERR_NETWORK:
                text = LocaleController.formatString(R.string.ZephyrAiErrNetwork, message == null ? "" : message);
                break;
            default:
                text = LocaleController.formatString(R.string.ZephyrAiErrServer, message == null ? "" : message);
                break;
        }
        errorCode = code;
        errorText.setText(text);
        errorAction = action;
        errorButton.setText(buttonText);
        errorButton.setVisibility(buttonText == null ? View.GONE : View.VISIBLE);
        errorBox.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------------------------------------- rendering

    private LinearLayout addBubble(ZephyrAiStore.Msg msg) {
        final Context context = container.getContext();
        final boolean user = "user".equals(msg.role);
        LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackgroundDrawable(Theme.createRoundRectDrawable(AndroidUtilities.dp(14),
                Theme.getColor(user ? Theme.key_chat_outBubble : Theme.key_chat_inBubble)));
        bubble.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        bubble.setTag(user ? Boolean.TRUE : Boolean.FALSE);
        LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                user ? Gravity.RIGHT : Gravity.LEFT, user ? 48 : 0, 4, user ? 0 : 48, 4);
        container.addView(bubble, lp);
        fillBubble(bubble, msg);
        return bubble;
    }

    private void fillBubble(LinearLayout bubble, ZephyrAiStore.Msg msg) {
        final Context context = bubble.getContext();
        final boolean user = Boolean.TRUE.equals(bubble.getTag());
        final int textColor = Theme.getColor(user ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn);
        bubble.removeAllViews();
        String content = msg.content == null ? "" : msg.content;
        if (content.isEmpty()) {
            content = LocaleController.getString(R.string.ZephyrAiThinking);
        }
        String[] parts = content.split("```", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i % 2 == 1) {
                // Code block: drop the optional language tag on the first line.
                int nl = part.indexOf('\n');
                if (nl >= 0 && part.substring(0, nl).trim().matches("[A-Za-z0-9_+#.-]*")) {
                    part = part.substring(nl + 1);
                }
                addCodeBlock(bubble, context, stripEdgeNewlines(part), textColor);
            } else {
                String text = stripEdgeNewlines(part);
                if (text.isEmpty()) {
                    continue;
                }
                TextView tv = new TextView(context);
                tv.setTextColor(textColor);
                tv.setLinkTextColor(textColor);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                tv.setText(formatText(text));
                tv.setTextIsSelectable(true);
                bubble.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            }
        }
    }

    private static String stripEdgeNewlines(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == '\n' || s.charAt(a) == '\r')) {
            a++;
        }
        while (b > a && (s.charAt(b - 1) == '\n' || s.charAt(b - 1) == '\r')) {
            b--;
        }
        return s.substring(a, b);
    }

    private void addCodeBlock(LinearLayout bubble, Context context, final String code, int textColor) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackgroundDrawable(Theme.createRoundRectDrawable(AndroidUtilities.dp(8), 0x26000000));
        block.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(8));

        TextView copy = new TextView(context);
        copy.setText(LocaleController.getString(R.string.ZephyrAiCopy));
        copy.setTextColor(textColor);
        copy.setAlpha(0.8f);
        copy.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        copy.setTypeface(AndroidUtilities.bold());
        copy.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        copy.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(code);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.copy, LocaleController.getString(R.string.ZephyrAiCopied)).show();
        });
        block.addView(copy, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT));

        TextView tv = new TextView(context);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setText(code);
        tv.setTextIsSelectable(true);
        block.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        bubble.addView(block, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 4));
    }

    /** Minimal markdown: headings (#), bullets (-, *), **bold**, `inline code`. */
    private static CharSequence formatText(String text) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            boolean heading = false;
            int h = 0;
            while (h < line.length() && h < 6 && line.charAt(h) == '#') {
                h++;
            }
            if (h > 0 && h < line.length() && line.charAt(h) == ' ') {
                heading = true;
                line = line.substring(h + 1);
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                line = "• " + line.substring(2);
            }
            int start = out.length();
            appendInline(out, line);
            if (heading) {
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (li < lines.length - 1) {
                out.append('\n');
            }
        }
        return out;
    }

    private static void appendInline(SpannableStringBuilder out, String line) {
        Matcher m = INLINE.matcher(line);
        int pos = 0;
        while (m.find()) {
            out.append(line, pos, m.start());
            int s = out.length();
            if (m.group(1) != null) {
                out.append(m.group(1));
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                out.append(m.group(2));
                out.setSpan(new TypefaceSpan("monospace"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(0x26000000), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            pos = m.end();
        }
        out.append(line, pos, line.length());
    }
}
