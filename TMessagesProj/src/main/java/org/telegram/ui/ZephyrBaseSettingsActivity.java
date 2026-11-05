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

    /** A switch whose stored value is the opposite of what the screen shows ("Dividers" shows !disableDividers). */
    protected static final class Inverted {
        final String key;
        Inverted(String key) { this.key = key; }
    }

    /** An expandable group "N/M": a switch for all items plus nested check rows, each a ZephyrConfig boolean key. */
    protected static final class Group {
        final String id;
        final int titleRes;
        final String[] keys;
        final int[] titleRes2;
        Group(String id, int titleRes, String[] keys, int[] titleRes2) {
            this.id = id; this.titleRes = titleRes; this.keys = keys; this.titleRes2 = titleRes2;
        }
        int on() {
            int n = 0;
            for (String key : keys) if (ZephyrConfig.getBool(key)) n++;
            return n;
        }
    }

    /** One nested row of a Group. */
    protected static final class GroupChild {
        final Group group;
        final int index;
        GroupChild(Group group, int index) { this.group = group; this.index = index; }
    }

    private final java.util.HashSet<String> expandedGroups = new java.util.HashSet<>();
    private final java.util.HashMap<String, org.telegram.ui.Cells.ZephyrWrapCell> groupCells = new java.util.HashMap<>();

    private org.telegram.ui.Cells.ZephyrWrapCell groupCell(String key) {
        org.telegram.ui.Cells.ZephyrWrapCell cell = groupCells.get(key);
        if (cell == null) {
            cell = new org.telegram.ui.Cells.ZephyrWrapCell(getParentActivity(), false);
            groupCells.put(key, cell);
        }
        return cell;
    }

    /** Group row with "N/M" and a chevron; when expanded, one check row per key follows. Keys keep their names. */
    protected void addGroup(ArrayList<UItem> items, Group group) {
        boolean open = expandedGroups.contains(group.id);
        int on = group.on();
        org.telegram.ui.Cells.ZephyrWrapCell cell = groupCell("g:" + group.id);
        cell.bind(LocaleController.getString(group.titleRes) + "  " + on + "/" + group.keys.length + (open ? " \u25B4" : " \u25BE"),
                null, null, 0, true, on > 0, true, false, 0, open ? org.telegram.ui.Cells.ZephyrWrapCell.POSITION_FIRST : org.telegram.ui.Cells.ZephyrWrapCell.POSITION_SINGLE);
        final org.telegram.ui.Cells.ZephyrWrapCell parentCell = cell;
        UItem row = UItem.asCustom(cell, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT);
        row.id = ("g:" + group.id).hashCode();
        row.object = group;
        cell.setOnClickListener(v -> onClick(row, parentCell, -1, 0, 0));
        items.add(row);
        if (!open) return;
        for (int i = 0; i < group.keys.length; i++) {
            org.telegram.ui.Cells.ZephyrWrapCell child = groupCell("c:" + group.id + ":" + i);
            child.bind(LocaleController.getString(group.titleRes2[i]), null, null, 0, false, false, false, false, 1,
                    i == group.keys.length - 1 ? org.telegram.ui.Cells.ZephyrWrapCell.POSITION_LAST : org.telegram.ui.Cells.ZephyrWrapCell.POSITION_MIDDLE);
            child.setCheckMode(true, ZephyrConfig.getBool(group.keys[i]));
            UItem childRow = UItem.asCustom(child, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT);
            childRow.id = ("c:" + group.id + ":" + i).hashCode();
            childRow.object = new GroupChild(group, i);
            final org.telegram.ui.Cells.ZephyrWrapCell childCell = child;
            child.setOnClickListener(v -> onClick(childRow, childCell, -1, 0, 0));
            items.add(childRow);
        }
    }

    /** Switch that shows the opposite of a stored key (no negations on screen, keys stay as they are). */
    protected void addInvertedSwitch(ArrayList<UItem> items, String key, int titleRes, int infoRes) {
        UItem item = UItem.asCheck(("inv:" + key).hashCode(), LocaleController.getString(titleRes)).setChecked(!ZephyrConfig.getBool(key));
        if (infoRes != 0) {
            item.subtext = LocaleController.getString(infoRes);
        }
        item.object = new Inverted(key);
        items.add(item);
    }

    @Override
    public View createView(android.content.Context context) {
        ZephyrConfig.load();
        View view = super.createView(context);
        ZephyrCards.enable(listView, false);
        return view;
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
        } else if (item.object instanceof Inverted) {
            String key = ((Inverted) item.object).key;
            boolean stored = !ZephyrConfig.getBool(key);       // the shown state is !stored: toggling the shown state flips the stored one
            ZephyrConfig.setBool(key, stored);
            boolean shown = !stored;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(shown);
            }
            item.checked = shown;
        } else if (item.object instanceof Group) {
            Group group = (Group) item.object;
            if (view instanceof org.telegram.ui.Cells.ZephyrWrapCell && ((org.telegram.ui.Cells.ZephyrWrapCell) view).wasSwitchTouched()) {
                boolean enable = group.on() == 0;     // any on -> all off; none on -> all on
                for (String key : group.keys) ZephyrConfig.setBool(key, enable);
            } else if (!expandedGroups.remove(group.id)) {
                expandedGroups.add(group.id);
            }
            if (listView != null && listView.adapter != null) listView.adapter.update(false);
        } else if (item.object instanceof GroupChild) {
            GroupChild child = (GroupChild) item.object;
            String key = child.group.keys[child.index];
            ZephyrConfig.setBool(key, !ZephyrConfig.getBool(key));
            if (listView != null && listView.adapter != null) listView.adapter.update(false);
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
