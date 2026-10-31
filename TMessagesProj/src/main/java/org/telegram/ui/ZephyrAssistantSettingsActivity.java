package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.zephyr.ZephyrAiShare;
import org.telegram.messenger.zephyr.ZephyrAiSetupParser;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.zephyr.ZephyrAiClient;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;

/** Settings for the AI assistant: server URL, token (masked), model, prompt, temperature, access bot. */
public class ZephyrAssistantSettingsActivity extends ZephyrBaseSettingsActivity {

    private static final int ID_URL = 101;
    private static final int ID_TOKEN = 102;
    private static final int ID_MODEL = 103;
    private static final int ID_PROMPT = 104;
    private static final int ID_BOT = 105;
    private static final int ID_REQUEST = 106;
    private static final int ID_TEST = 107;
    private static final int ID_TEMPERATURE = 108;
    private static final int ID_ADMIN_SECRET = 110;
    private static final int ID_ADD_PERSON = 111;
    private static final int ID_IMPORT = 112;
    private static final int ID_REFRESH = 113;
    private static final int ID_PERSON_BASE = 1000;

    private List<ZephyrAiClient.TokenInfo> people;
    private boolean peopleLoading;
    private String peopleError;

    private ZephyrAiClient.Call modelsCall;
    private boolean openImportOnStart;

    /** Settings screen that immediately opens the "Import access" dialog (used from the 401 error of the chat). */
    public static ZephyrAssistantSettingsActivity forImport() {
        ZephyrAssistantSettingsActivity a = new ZephyrAssistantSettingsActivity();
        a.openImportOnStart = true;
        return a;
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrAi);
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        loadPeople();
        if (openImportOnStart) {
            openImportOnStart = false;
            AndroidUtilities.runOnUIThread(this::importAccess, 350);
        }
        return v;
    }

    @Override
    public void onFragmentDestroy() {
        if (modelsCall != null) {
            modelsCall.cancel();
        }
        super.onFragmentDestroy();
    }

    private String notSet() {
        return LocaleController.getString(R.string.ZephyrAiNotSet);
    }

    private static String mask(String token) {
        if (token.isEmpty()) {
            return "";
        }
        String tail = token.length() > 4 ? token.substring(token.length() - 4) : "";
        return "••••••••" + tail;
    }

    private static String oneLine(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final String bot = ZephyrConfig.getAiBotUsername();
        // Onboarding: 1 server, 2 token (or import), 3 optional share access.
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrAiOnbHeader)));
        String url = ZephyrConfig.getAiServerUrl();
        items.add(UItem.asButton(ID_URL, LocaleController.getString(R.string.ZephyrAiStep1), url.isEmpty() ? notSet() : url));
        String token = ZephyrConfig.getAiToken();
        items.add(UItem.asButton(ID_TOKEN, LocaleController.getString(R.string.ZephyrAiStep2), token.isEmpty() ? notSet() : mask(token)));
        items.add(UItem.asButton(ID_IMPORT, R.drawable.msg_copy, LocaleController.getString(R.string.ZephyrShareImport)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrAiOnbInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrAiOptionsHeader)));
        String model = ZephyrConfig.getAiModel();
        items.add(UItem.asButton(ID_MODEL, LocaleController.getString(R.string.ZephyrAiModel), model.isEmpty() ? LocaleController.getString(R.string.ZephyrAiModelDefault) : model));
        String prompt = ZephyrConfig.getAiSystemPrompt();
        items.add(UItem.asButton(ID_PROMPT, LocaleController.getString(R.string.ZephyrAiSystemPrompt), prompt.isEmpty() ? notSet() : oneLine(prompt, 40)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrAiTemperature)));
        items.add(UItem.asIntSlideView(1, 0, ZephyrConfig.getAiTemperaturePct(), 150,
                val -> val + "%",
                val -> ZephyrConfig.setAiTemperaturePct(val)));
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(ID_TEST, R.drawable.msg_retry, LocaleController.getString(R.string.ZephyrAiTestConnection)));
        items.add(UItem.asButton(ID_BOT, LocaleController.getString(R.string.ZephyrAiBotOptional), bot.isEmpty() ? notSet() : "@" + bot));
        if (!bot.isEmpty()) {
            items.add(UItem.asButton(ID_REQUEST, R.drawable.msg_bot, LocaleController.getString(R.string.ZephyrAiRequestAccess)));
        }
        // The bot hint is shown only when a bot is actually configured.
        items.add(UItem.asShadow(LocaleController.getString(bot.isEmpty() ? R.string.ZephyrAiSettingsInfoNoBot : R.string.ZephyrAiSettingsInfo)));

        // Share access (owner only): admin secret + invite flow + people list.
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrAiStep3Header)));
        String secret = ZephyrConfig.getAiAdminSecret();
        items.add(UItem.asButton(ID_ADMIN_SECRET, LocaleController.getString(R.string.ZephyrShareAdminSecret), secret.isEmpty() ? notSet() : mask(secret)));
        items.add(UItem.asButton(ID_ADD_PERSON, R.drawable.msg_addcontact, LocaleController.getString(R.string.ZephyrShareAddPerson)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrShareInfo)));
        if (!secret.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrSharePeopleHeader)));
            if (peopleLoading) {
                items.add(UItem.asButton(114, LocaleController.getString(R.string.ZephyrShareLoading), ""));
            } else if (peopleError != null) {
                items.add(UItem.asButton(ID_REFRESH, LocaleController.getString(R.string.ZephyrShareRefresh), peopleError));
            } else {
                if (people != null) {
                    for (int i = 0; i < people.size(); i++) {
                        ZephyrAiClient.TokenInfo t = people.get(i);
                        String last = t.lastUsedAt <= 0 ? LocaleController.getString(R.string.ZephyrShareNeverUsed)
                                : DateUtils.getRelativeTimeSpanString(t.lastUsedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
                        String info = t.revoked ? LocaleController.getString(R.string.ZephyrShareRevokedTag)
                                : LocaleController.formatString(R.string.ZephyrSharePersonInfo, last, t.requestCount);
                        items.add(UItem.asButton(ID_PERSON_BASE + i, t.label.isEmpty() ? t.id : t.label, info));
                    }
                    if (people.isEmpty()) {
                        items.add(UItem.asButton(114, LocaleController.getString(R.string.ZephyrShareNoPeople), ""));
                    }
                }
                items.add(UItem.asButton(ID_REFRESH, R.drawable.msg_retry, LocaleController.getString(R.string.ZephyrShareRefresh)));
            }
            items.add(UItem.asShadow(null));
        }
    }

    // ------------------------------------------------------------------ share access

    private void loadPeople() {
        if (ZephyrConfig.getAiAdminSecret().isEmpty() || ZephyrConfig.getAiServerUrl().isEmpty()) {
            people = null;
            return;
        }
        peopleLoading = true;
        peopleError = null;
        refresh();
        ZephyrAiClient.adminListTokens(new ZephyrAiClient.AdminListener<List<ZephyrAiClient.TokenInfo>>() {
            @Override
            public void onResult(List<ZephyrAiClient.TokenInfo> result) {
                peopleLoading = false;
                people = result;
                refresh();
            }

            @Override
            public void onError(int code, String message) {
                peopleLoading = false;
                peopleError = adminErrorText(code, message);
                refresh();
            }
        });
    }

    private String adminErrorText(int code, String message) {
        switch (code) {
            case ZephyrAiClient.ERR_UNAUTHORIZED:
                return LocaleController.getString(R.string.ZephyrShareErrSecret);
            case ZephyrAiClient.ERR_ADMIN_UNAVAILABLE:
                return LocaleController.getString(R.string.ZephyrShareErrAdminMissing);
            case ZephyrAiClient.ERR_NOT_CONFIGURED:
                return LocaleController.getString(ZephyrAiSetupParser.normalizeServerUrl(ZephyrConfig.getAiServerUrl()).isEmpty()
                        ? R.string.ZephyrShareNeedUrl : R.string.ZephyrShareNeedAdmin);
            case ZephyrAiClient.ERR_RATE_LIMIT:
                return LocaleController.getString(R.string.ZephyrShareErrLockout);
            case ZephyrAiClient.ERR_STARTING:
                return LocaleController.getString(R.string.ZephyrAiErrStarting);
            case ZephyrAiClient.ERR_SERVER_DOWN:
                return LocaleController.formatString(R.string.ZephyrAiErrServerDown, message == null || message.isEmpty() ? "?" : message);
            case ZephyrAiClient.ERR_NETWORK:
                return LocaleController.formatString(R.string.ZephyrAiErrNetwork, message == null ? "" : message);
            default:
                return LocaleController.formatString(R.string.ZephyrAiErrServer, message == null ? "" : message);
        }
    }

    private void showAdminError(int code, String message) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, adminErrorText(code, message), 3).show();
    }

    private void startAddPerson() {
        if (getParentActivity() == null) {
            return;
        }
        if (ZephyrAiSetupParser.normalizeServerUrl(ZephyrConfig.getAiServerUrl()).isEmpty()) {
            askToFill(R.string.ZephyrShareNeedUrl, R.string.ZephyrAiStep1, ID_URL);
            return;
        }
        if (ZephyrAiSetupParser.sanitizeSecret(ZephyrConfig.getAiAdminSecret()).isEmpty()) {
            askToFill(R.string.ZephyrShareNeedAdmin, R.string.ZephyrShareEnterSecret, ID_ADMIN_SECRET);
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.ZephyrShareAddPerson));
        builder.setItems(new CharSequence[]{
                LocaleController.getString(R.string.ZephyrShareAddFromChats),
                LocaleController.getString(R.string.ZephyrShareAddFromContacts),
                LocaleController.getString(R.string.ZephyrShareAddByUsername)}, (d, which) -> {
            if (which == 0) {
                pickFromChats();
            } else if (which == 1) {
                pickFromContacts();
            } else {
                editText(R.string.ZephyrShareAddByUsername, "", false, false, v -> resolveAndInvite(v));
            }
        });
        showDialog(builder.create());
    }

    /** Explains what is missing and offers to jump straight to the field. */
    private void askToFill(int messageRes, int buttonRes, int itemId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.ZephyrShareAddPerson));
        builder.setMessage(LocaleController.getString(messageRes));
        builder.setPositiveButton(LocaleController.getString(buttonRes), (d, w) -> onCustomClick(UItem.asButton(itemId, "", ""), null));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void pickFromContacts() {
        Bundle args = new Bundle();
        args.putBoolean("onlyUsers", true);
        args.putBoolean("allowBots", false);
        args.putBoolean("allowSelf", false);
        args.putBoolean("needFinishFragment", false);
        args.putBoolean("allowUsernameSearch", true);
        ContactsActivity picker = new ContactsActivity(args);
        picker.setDelegate((user, param, activity) -> {
            if (user == null) {
                return;
            }
            activity.finishFragment();
            invite(user.id, user);
        });
        presentFragment(picker);
    }

    private void pickFromChats() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_USERS_ONLY);
        args.putBoolean("allowGlobalSearch", true);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty() || dids.get(0).dialogId <= 0) {
                return false;
            }
            final long did = dids.get(0).dialogId;
            picker.finishFragment();
            invite(did, MessagesController.getInstance(currentAccount).getUser(did));
            return true;
        });
        presentFragment(picker);
    }

    private void resolveAndInvite(String input) {
        if (input.trim().isEmpty() || getParentActivity() == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        ZephyrAiShare.resolveUsername(currentAccount, input, new ZephyrAiShare.ResolveListener() {
            @Override
            public void onResolved(long userId, TLRPC.User user) {
                progress.dismiss();
                invite(userId, user);
            }

            @Override
            public void onFailed() {
                onFailed(ZephyrAiShare.FAIL_NOT_FOUND);
            }

            @Override
            public void onFailed(int reason) {
                progress.dismiss();
                int res;
                switch (reason) {
                    case ZephyrAiShare.FAIL_INVALID:
                        res = R.string.ZephyrShareUserInvalid;
                        break;
                    case ZephyrAiShare.FAIL_NOT_USER:
                        res = R.string.ZephyrShareUserNotUser;
                        break;
                    case ZephyrAiShare.FAIL_NETWORK:
                        res = R.string.ZephyrShareResolveNetwork;
                        break;
                    default:
                        res = R.string.ZephyrShareUserNotFound;
                        break;
                }
                BulletinFactory.of(ZephyrAssistantSettingsActivity.this).createSimpleBulletin(R.raw.error, LocaleController.getString(res)).show();
            }
        });
    }

    /** Mints a token labeled with the person's name, then asks the owner to confirm before anything is sent. */
    private void invite(long userId, TLRPC.User user) {
        if (getParentActivity() == null) {
            return;
        }
        if (userId == getUserConfig().getClientUserId()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrShareUserSelf)).show();
            return;
        }
        if (user != null && user.bot) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrShareUserBot)).show();
            return;
        }
        String shown = user == null ? "" : UserObject.getUserName(user);
        final String name = shown == null || shown.trim().isEmpty() ? ("id" + userId) : shown.trim();
        final String note = user != null && user.username != null && !user.username.isEmpty() ? "@" + user.username + " (tg id " + userId + ")" : "tg id " + userId;
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        ZephyrAiClient.adminCreateToken(name, note, 0, 0, new ZephyrAiClient.AdminListener<ZephyrAiClient.CreatedToken>() {
            @Override
            public void onResult(ZephyrAiClient.CreatedToken created) {
                progress.dismiss();
                loadPeople();
                confirmSend(userId, name, created);
            }

            @Override
            public void onError(int code, String message) {
                progress.dismiss();
                showAdminError(code, message);
            }
        });
    }

    private void confirmSend(long userId, String name, ZephyrAiClient.CreatedToken created) {
        if (getParentActivity() == null) {
            // Screen gone: the token was never shown to anyone, do not leave it active.
            ZephyrAiClient.adminRevokeToken(created.id, new SilentListener());
            return;
        }
        final String text = ZephyrAiShare.buildMessage(created.token);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.formatString(R.string.ZephyrShareConfirmTitle, name));
        builder.setMessage(LocaleController.formatString(R.string.ZephyrShareConfirmInfo, text));
        builder.setPositiveButton(LocaleController.getString(R.string.ZephyrAiSend), (d, w) -> {
            ZephyrAiShare.send(currentAccount, userId, text, () -> showSendFailed(text));
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.ZephyrShareSentTo, name)).show();
        });
        builder.setNeutralButton(LocaleController.getString(R.string.ZephyrAiCopy), (d, w) -> copyText(text));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
            ZephyrAiClient.adminRevokeToken(created.id, new SilentListener());
            loadPeople();
        });
        builder.setOnCancelListener(d -> ZephyrAiClient.adminRevokeToken(created.id, new SilentListener()));
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        showDialog(dialog);
    }

    private void showSendFailed(String text) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.ZephyrShareSendFailed));
        builder.setMessage(LocaleController.formatString(R.string.ZephyrShareSendFailedInfo, text));
        builder.setPositiveButton(LocaleController.getString(R.string.ZephyrAiCopy), (d, w) -> copyText(text));
        builder.setNegativeButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
    }

    private void copyText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ZephyrGram", text));
            BulletinFactory.of(this).createSimpleBulletin(R.raw.copy, LocaleController.getString(R.string.ZephyrAiCopied)).show();
        } catch (Throwable ignore) {
        }
    }

    private static class SilentListener implements ZephyrAiClient.AdminListener<Boolean> {
        @Override
        public void onResult(Boolean result) {
        }

        @Override
        public void onError(int code, String message) {
        }
    }

    private void personActions(final ZephyrAiClient.TokenInfo t) {
        if (getParentActivity() == null || t.revoked) {
            return;
        }
        final String label = t.label.isEmpty() ? t.id : t.label;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(label);
        builder.setItems(new CharSequence[]{
                LocaleController.getString(R.string.ZephyrShareEditLimits),
                LocaleController.getString(R.string.ZephyrShareRevoke)}, (d, which) -> {
            if (which == 0) {
                editLimits(t);
            } else {
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle(LocaleController.getString(R.string.ZephyrShareRevoke));
                b.setMessage(LocaleController.formatString(R.string.ZephyrShareRevokeConfirm, label));
                b.setPositiveButton(LocaleController.getString(R.string.ZephyrShareRevoke), (dd, ww) ->
                        ZephyrAiClient.adminRevokeToken(t.id, new ZephyrAiClient.AdminListener<Boolean>() {
                            @Override
                            public void onResult(Boolean result) {
                                BulletinFactory.of(ZephyrAssistantSettingsActivity.this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.ZephyrShareRevoked)).show();
                                loadPeople();
                            }

                            @Override
                            public void onError(int code, String message) {
                                showAdminError(code, message);
                            }
                        }));
                b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                showDialog(b.create());
            }
        });
        showDialog(builder.create());
    }

    private void editLimits(final ZephyrAiClient.TokenInfo t) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final android.widget.LinearLayout box = new android.widget.LinearLayout(context);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        final EditText rate = new EditText(context);
        final EditText daily = new EditText(context);
        for (EditText e : new EditText[]{rate, daily}) {
            e.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            e.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            box.addView(e, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 0));
        }
        rate.setHint(LocaleController.getString(R.string.ZephyrShareRatePerMin));
        daily.setHint(LocaleController.getString(R.string.ZephyrShareDailyLimit));
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.ZephyrShareEditLimits));
        builder.setMessage(LocaleController.getString(R.string.ZephyrShareLimitsInfo));
        builder.setView(box);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            int r = parseInt(rate.getText().toString());
            int dl = parseInt(daily.getText().toString());
            if (r <= 0 && dl <= 0) {
                return;
            }
            ZephyrAiClient.adminUpdateLimits(t.id, r, dl, new ZephyrAiClient.AdminListener<Boolean>() {
                @Override
                public void onResult(Boolean result) {
                    BulletinFactory.of(ZephyrAssistantSettingsActivity.this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.ZephyrShareLimitsSaved)).show();
                }

                @Override
                public void onError(int code, String message) {
                    showAdminError(code, message);
                }
            });
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Lets a friend paste the received message; fills server URL + token from its zephyrai://setup line. */
    private void importAccess() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        String prefill = "";
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
                ZephyrAiSetupParser.Result pr = ZephyrAiShare.parseLoose(clip);
                if (pr.complete() || !pr.token.isEmpty()) {
                    prefill = clip.toString();
                }
            }
        } catch (Throwable ignore) {
        }
        final EditText edit = new EditText(context);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        edit.setHint(LocaleController.getString(R.string.ZephyrShareImportHint));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setMaxLines(6);
        edit.setText(prefill);
        FrameLayout frame = new FrameLayout(context);
        frame.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.TOP, 24, 8, 24, 0));
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.ZephyrShareImport));
        builder.setMessage(LocaleController.getString(R.string.ZephyrShareImportInfo));
        builder.setView(frame);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            ZephyrAiSetupParser.Result pr = ZephyrAiShare.parseLoose(edit.getText());
            boolean haveUrl = !ZephyrAiSetupParser.normalizeServerUrl(ZephyrConfig.getAiServerUrl()).isEmpty();
            if (pr.complete()) {
                ZephyrConfig.setAiString("serverUrl", pr.url);
                ZephyrConfig.setAiString("token", pr.token);
                refresh();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.ZephyrShareImportDone)).show();
            } else if (!pr.token.isEmpty() && haveUrl) {
                ZephyrConfig.setAiString("token", pr.token);
                refresh();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.ZephyrShareImportTokenOnly)).show();
            } else if (!pr.url.isEmpty()) {
                ZephyrConfig.setAiString("serverUrl", pr.url);
                refresh();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.ZephyrShareImportUrlOnly)).show();
            } else {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrShareImportBad)).show();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void refresh() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void onCustomClick(UItem item, View view) {
        switch (item.id) {
            case ID_URL:
                editText(R.string.ZephyrAiServerUrl, ZephyrConfig.getAiServerUrl(), false, false, v -> {
                    if (v.trim().isEmpty()) {
                        ZephyrConfig.setAiString("serverUrl", "");
                        return;
                    }
                    String norm = ZephyrAiSetupParser.normalizeServerUrl(v);
                    if (norm.isEmpty()) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrAiUrlBad), 3).show();
                        return;
                    }
                    ZephyrConfig.setAiString("serverUrl", norm);
                    loadPeople();
                });
                break;
            case ID_TOKEN:
                // Never prefill the stored token.
                editText(R.string.ZephyrAiToken, "", false, true, v -> {
                    String clean = ZephyrAiSetupParser.sanitizeSecret(v);
                    if (!clean.isEmpty()) {
                        ZephyrConfig.setAiString("token", clean);
                        savedSecretBulletin(clean);
                    }
                });
                break;
            case ID_PROMPT:
                editText(R.string.ZephyrAiSystemPrompt, ZephyrConfig.getAiSystemPrompt(), true, false,
                        v -> ZephyrConfig.setAiString("systemPrompt", v));
                break;
            case ID_BOT:
                editText(R.string.ZephyrAiBotUsername, ZephyrConfig.getAiBotUsername(), false, false,
                        v -> ZephyrConfig.setAiString("botUsername", v));
                break;
            case ID_MODEL:
                pickModel();
                break;
            case ID_ADMIN_SECRET:
                // Never prefill the stored secret.
                editText(R.string.ZephyrShareAdminSecret, "", false, true, v -> {
                    String clean = ZephyrAiSetupParser.sanitizeSecret(v);
                    if (clean.startsWith("zgai_")) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrShareSecretLooksToken), 4).show();
                        return;
                    }
                    if (!clean.isEmpty()) {
                        if (clean.length() < 32) {
                            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrShareSecretShort), 4).show();
                        }
                        ZephyrConfig.setAiString("adminSecret", clean);
                        loadPeople();
                        savedSecretBulletin(clean);
                    }
                });
                break;
            case ID_ADD_PERSON:
                startAddPerson();
                break;
            case ID_IMPORT:
                importAccess();
                break;
            case ID_REFRESH:
                loadPeople();
                break;
            case ID_REQUEST:
                if (!openAccessBot(this)) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrAiBotNotSet)).show();
                }
                break;
            case ID_TEST:
                testConnection();
                break;
            default:
                if (item.id >= ID_PERSON_BASE && people != null && item.id - ID_PERSON_BASE < people.size()) {
                    personActions(people.get(item.id - ID_PERSON_BASE));
                }
                break;
        }
    }

    /** Opens the access bot chat via a t.me link. Returns false when no bot username is configured. */
    public static boolean openAccessBot(BaseFragment fragment) {
        String bot = ZephyrConfig.getAiBotUsername();
        if (bot.isEmpty() || fragment == null || fragment.getParentActivity() == null) {
            return false;
        }
        Browser.openUrl(fragment.getParentActivity(), "https://t.me/" + bot);
        return true;
    }

    /** Confirms a saved secret without revealing it: length and last 4 characters let the user spot a bad paste. */
    private void savedSecretBulletin(String clean) {
        String tail = clean.length() > 4 ? clean.substring(clean.length() - 4) : "";
        BulletinFactory.of(this).createSimpleBulletin(R.raw.done,
                LocaleController.formatString(R.string.ZephyrAiTokenSaved, clean.length(), tail)).show();
    }

    private interface Setter {
        void set(String value);
    }

    private void editText(int titleRes, String current, boolean multiline, boolean secret, Setter setter) {
        if (getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        final EditText edit = new EditText(context);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        edit.setSingleLine(!multiline);
        int type = InputType.TYPE_CLASS_TEXT;
        if (multiline) {
            type |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            edit.setMaxLines(8);
        } else {
            type |= InputType.TYPE_TEXT_VARIATION_URI;
        }
        if (secret) {
            // Masked while typing; pasted secrets may carry a newline/space: they are stripped on save.
            type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            edit.setSingleLine(true);
        }
        edit.setInputType(type);
        edit.setText(current);
        edit.setSelection(edit.length());
        FrameLayout frame = new FrameLayout(context);
        frame.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.TOP, 24, 8, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(titleRes));
        builder.setView(frame);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            setter.set(edit.getText().toString());
            refresh();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
        edit.requestFocus();
        AndroidUtilities.showKeyboard(edit);
    }

    private void pickModel() {
        if (getParentActivity() == null) {
            return;
        }
        if (!ZephyrConfig.isAiConfigured()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrAiErrNotConfigured)).show();
            return;
        }
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        progress.setOnCancelListener(d -> {
            if (modelsCall != null) {
                modelsCall.cancel();
            }
        });
        progress.showDelayed(300);
        modelsCall = ZephyrAiClient.listModels(new ZephyrAiClient.ModelsListener() {
            @Override
            public void onModels(List<String> models) {
                progress.dismiss();
                if (getParentActivity() == null) {
                    return;
                }
                if (models.isEmpty()) {
                    BulletinFactory.of(ZephyrAssistantSettingsActivity.this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrAiModelNone)).show();
                    return;
                }
                final CharSequence[] names = new CharSequence[models.size() + 1];
                names[0] = LocaleController.getString(R.string.ZephyrAiModelDefault);
                for (int i = 0; i < models.size(); i++) {
                    names[i + 1] = models.get(i);
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle(LocaleController.getString(R.string.ZephyrAiModel));
                builder.setItems(names, (d, which) -> {
                    ZephyrConfig.setAiString("model", which == 0 ? "" : models.get(which - 1));
                    refresh();
                });
                showDialog(builder.create());
            }

            @Override
            public void onError(int code, String message) {
                progress.dismiss();
                showApiError(code, message);
            }
        });
    }

    private void testConnection() {
        if (!ZephyrConfig.isAiConfigured()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrAiErrNotConfigured)).show();
            return;
        }
        if (modelsCall != null) {
            modelsCall.cancel();
        }
        modelsCall = ZephyrAiClient.listModels(new ZephyrAiClient.ModelsListener() {
            @Override
            public void onModels(List<String> models) {
                BulletinFactory.of(ZephyrAssistantSettingsActivity.this).createSimpleBulletin(R.raw.done,
                        LocaleController.formatString(R.string.ZephyrAiTestOk, models.size())).show();
            }

            @Override
            public void onError(int code, String message) {
                showApiError(code, message);
            }
        });
    }

    private void showApiError(int code, String message) {
        String text;
        switch (code) {
            case ZephyrAiClient.ERR_NOT_CONFIGURED:
                text = LocaleController.getString(R.string.ZephyrAiErrNotConfigured);
                break;
            case ZephyrAiClient.ERR_UNAUTHORIZED:
                text = LocaleController.getString(ZephyrConfig.getAiBotUsername().isEmpty()
                        ? R.string.ZephyrAiErrUnauthorizedNoBot : R.string.ZephyrAiErrUnauthorized);
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
        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, text, 3).show();
    }
}
