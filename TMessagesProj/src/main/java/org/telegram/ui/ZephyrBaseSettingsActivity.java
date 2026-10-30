/*
 * Settings screens ported from exteraGram (exteraSquad), GPLv2.
 * Modified for ZephyrGram. Original: github.com/exteraSquad/exteraGram
 */
package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Base for ZephyrGram settings sub-screens: rows are declared in fillItems(), settings are stored by key. */
public abstract class ZephyrBaseSettingsActivity extends UniversalFragment {

    /** Single-choice setting stored as an int in ZephyrConfig. */
    protected static class Choice {
        final String key;
        final int titleRes;
        final CharSequence[] options;

        Choice(String key, int titleRes, CharSequence... options) {
            this.key = key;
            this.titleRes = titleRes;
            this.options = options;
        }
    }

    @Override
    public View createView(android.content.Context context) {
        ZephyrConfig.load();
        return super.createView(context);
    }

    protected void addSwitch(ArrayList<UItem> items, String key, int titleRes) {
        addSwitch(items, key, titleRes, 0);
    }

    /** Switch with a short explanation under the title (infoRes == 0 means no subtitle). */
    protected void addSwitch(ArrayList<UItem> items, String key, int titleRes, int infoRes) {
        UItem item = UItem.asCheck(key.hashCode(), LocaleController.getString(titleRes)).setChecked(ZephyrConfig.getBool(key));
        if (infoRes != 0) {
            item.subtext = LocaleController.getString(infoRes);
        }
        item.object = key;
        items.add(item);
    }

    protected static void addHeader(ArrayList<UItem> items, int titleRes) {
        items.add(UItem.asHeader(LocaleController.getString(titleRes)));
    }

    /** Percent-style slider stored as an int in ZephyrConfig. */
    protected void addSlider(ArrayList<UItem> items, String key, int titleRes, int min, int max) {
        items.add(UItem.asHeader(LocaleController.getString(titleRes)));
        items.add(UItem.asIntSlideView(1, min, Math.max(min, Math.min(max, ZephyrConfig.getInt(key))), max,
                val -> val + "%",
                val -> ZephyrConfig.setInt(key, val)));
    }

    protected void addChoice(ArrayList<UItem> items, Choice choice) {
        int value = Math.max(0, Math.min(choice.options.length - 1, ZephyrConfig.getInt(choice.key)));
        UItem item = UItem.asButton(choice.key.hashCode(), LocaleController.getString(choice.titleRes), choice.options[value]);
        item.object = choice;
        items.add(item);
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof String) {
            String key = (String) item.object;
            boolean value = !ZephyrConfig.getBool(key);
            ZephyrConfig.setBool(key, value);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            item.checked = value;
        } else if (item.object instanceof Choice) {
            final Choice choice = (Choice) item.object;
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString(choice.titleRes));
            builder.setItems(choice.options, (dialog, which) -> {
                ZephyrConfig.setInt(choice.key, which);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
            showDialog(builder.create());
        } else {
            onCustomClick(item, view);
        }
    }

    protected void onCustomClick(UItem item, View view) {
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /** Confirms and resets the exteraGram-derived settings. */
    public static void confirmReset(org.telegram.ui.ActionBar.BaseFragment fragment, Runnable afterReset) {
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.ZephyrResetSettings));
        builder.setMessage(LocaleController.getString(R.string.ZephyrResetSettingsInfo));
        builder.setPositiveButton(LocaleController.getString(R.string.ZephyrReset), (d, w) -> {
            ZephyrConfig.resetUiSettings();
            if (afterReset != null) {
                afterReset.run();
            }
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.ZephyrResetSettingsDone)).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    public static void showCredits(org.telegram.ui.ActionBar.BaseFragment fragment) {
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.ZephyrCredits));
        builder.setMessage(LocaleController.getString(R.string.ZephyrCreditsText));
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        fragment.showDialog(builder.create());
    }
}
