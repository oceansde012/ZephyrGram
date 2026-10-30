package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.ZephyrMessageFilters;
import org.telegram.messenger.zephyr.ZephyrMessageFilters.Filter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Management screen for local message filters (list, add, edit, delete, enable, test). */
public class ZephyrFiltersActivity extends UniversalFragment {

    private static final int ID_ENABLE = 1;
    private static final int ID_ADD = 2;
    private static final int ID_TEST = 3;
    private static final int ID_FILTER_BASE = 1000;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrMessageFilters);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ID_ENABLE, LocaleController.getString(R.string.ZephyrFiltersEnable)).setChecked(ZephyrMessageFilters.isEnabled()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrFiltersInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrFiltersHeader)));
        int index = 0;
        for (Filter f : ZephyrMessageFilters.getAll()) {
            UItem item = UItem.asButton(ID_FILTER_BASE + index++, title(f), summary(f));
            item.object = f;
            items.add(item);
        }
        if (index == 0) {
            items.add(UItem.asButton(ID_ADD + 100, LocaleController.getString(R.string.ZephyrFiltersEmpty)));
        }
        items.add(UItem.asButton(ID_ADD, LocaleController.getString(R.string.ZephyrFiltersAdd)));
        items.add(UItem.asButton(ID_TEST, LocaleController.getString(R.string.ZephyrFiltersTest)));
        items.add(UItem.asShadow(null));
    }

    private static String title(Filter f) {
        if (f.name != null && !f.name.isEmpty()) {
            return f.name;
        }
        if (!f.pattern.isEmpty()) {
            return f.pattern;
        }
        return "#" + (f.senderId != 0 ? f.senderId : f.dialogId);
    }

    private static String summary(Filter f) {
        StringBuilder sb = new StringBuilder();
        sb.append(LocaleController.getString(f.enabled ? R.string.ZephyrFilterOn : R.string.ZephyrFilterOff));
        if (!f.pattern.isEmpty()) {
            sb.append(" · ").append(LocaleController.getString(f.regex ? R.string.ZephyrUxFilterKindRegex : R.string.ZephyrUxFilterKindText));
        }
        if (f.senderId != 0) {
            sb.append(" · ").append(LocaleController.formatString(R.string.ZephyrUxFilterSender, String.valueOf(f.senderId)));
        }
        if (f.dialogId != 0) {
            sb.append(" · ").append(LocaleController.formatString(R.string.ZephyrUxFilterChat, String.valueOf(f.dialogId)));
        }
        return sb.toString();
    }

    private void refresh() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ENABLE) {
            boolean value = !ZephyrMessageFilters.isEnabled();
            ZephyrMessageFilters.setEnabled(value);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            item.checked = value;
        } else if (item.id == ID_ADD) {
            showEditor(null);
        } else if (item.id == ID_TEST) {
            showTest();
        } else if (item.object instanceof Filter) {
            showActions((Filter) item.object);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof Filter) {
            showActions((Filter) item.object);
            return true;
        }
        return false;
    }

    private void showActions(Filter f) {
        if (getParentActivity() == null) {
            return;
        }
        CharSequence[] options = new CharSequence[]{
                LocaleController.getString(f.enabled ? R.string.Disable : R.string.Enable),
                LocaleController.getString(R.string.Edit),
                LocaleController.getString(R.string.Delete)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title(f));
        builder.setItems(options, (d, which) -> {
            if (which == 0) {
                Filter copy = f.copy();
                copy.enabled = !f.enabled;
                ZephyrMessageFilters.put(copy);
                refresh();
            } else if (which == 1) {
                showEditor(f);
            } else {
                ZephyrMessageFilters.remove(f.id);
                refresh();
            }
        });
        showDialog(builder.create());
    }

    private EditText addField(LinearLayout root, Context context, String hint, String value, boolean numeric) {
        EditText edit = new EditText(context);
        edit.setHint(hint);
        edit.setText(value);
        edit.setSingleLine(true);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        edit.setInputType(numeric ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED) : InputType.TYPE_CLASS_TEXT);
        root.addView(edit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        return edit;
    }

    private CheckBox addCheck(LinearLayout root, Context context, String text, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(text);
        box.setChecked(checked);
        box.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        root.addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        return box;
    }

    private static long parseLong(CharSequence s) {
        try {
            return Long.parseLong(s.toString().trim());
        } catch (Throwable e) {
            return 0;
        }
    }

    private void showEditor(Filter existing) {
        if (getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        final EditText name = addField(root, context, LocaleController.getString(R.string.ZephyrFilterName), existing == null ? "" : existing.name, false);
        final EditText pattern = addField(root, context, LocaleController.getString(R.string.ZephyrFilterPattern), existing == null ? "" : existing.pattern, false);
        final CheckBox regex = addCheck(root, context, LocaleController.getString(R.string.ZephyrFilterRegex), existing != null && existing.regex);
        final CheckBox matchCase = addCheck(root, context, LocaleController.getString(R.string.ZephyrFilterMatchCase), existing != null && existing.matchCase);
        final EditText sender = addField(root, context, LocaleController.getString(R.string.ZephyrFilterSender), existing == null || existing.senderId == 0 ? "" : String.valueOf(existing.senderId), true);
        final EditText chat = addField(root, context, LocaleController.getString(R.string.ZephyrFilterChat), existing == null || existing.dialogId == 0 ? "" : String.valueOf(existing.dialogId), true);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(existing == null ? R.string.ZephyrFilterNew : R.string.Edit));
        builder.setView(root);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            Filter f = existing == null ? new Filter() : existing.copy();
            f.name = name.getText().toString().trim();
            f.pattern = pattern.getText().toString();
            f.regex = regex.isChecked();
            f.matchCase = matchCase.isChecked();
            f.senderId = parseLong(sender.getText());
            f.dialogId = parseLong(chat.getText());
            if (existing == null) {
                f.enabled = true;
            }
            if (!f.isValid()) {
                org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrFilterInvalid)).show();
                return;
            }
            ZephyrMessageFilters.put(f);
            refresh();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showTest() {
        if (getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        final EditText input = addField(root, context, LocaleController.getString(R.string.ZephyrFilterTestPrompt), "", false);
        input.setSingleLine(false);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.ZephyrFiltersTest));
        builder.setView(root);
        builder.setPositiveButton(LocaleController.getString(R.string.ZephyrFiltersTest), (d, w) -> {
            Filter hit = ZephyrMessageFilters.testText(input.getText().toString());
            String msg = hit != null
                    ? LocaleController.formatString(R.string.ZephyrFilterTestMatch, title(hit))
                    : LocaleController.getString(R.string.ZephyrFilterTestNoMatch);
            AlertDialog.Builder result = new AlertDialog.Builder(context);
            result.setTitle(LocaleController.getString(R.string.ZephyrFiltersTest));
            result.setMessage(msg);
            result.setPositiveButton(LocaleController.getString(R.string.OK), null);
            showDialog(result.create());
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }
}
