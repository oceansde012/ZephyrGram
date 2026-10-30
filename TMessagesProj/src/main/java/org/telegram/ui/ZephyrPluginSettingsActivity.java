package org.telegram.ui;

import android.view.View;
import android.widget.EditText;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zephyr.plugins.PluginManager;
import org.telegram.messenger.zephyr.plugins.PluginSettingsModel;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import java.util.ArrayList;

public class ZephyrPluginSettingsActivity extends UniversalFragment {
    private final String pluginId;
    private PluginSettingsModel model;
    private boolean loading;
    public ZephyrPluginSettingsActivity(String id) { pluginId=id; }
    @Override protected CharSequence getTitle() { return ZephyrPluginsActivity.s(R.string.ZephyrPluginSettings); }
    @Override public void onResume() { super.onResume(); load(); }
    private void load() {
        if (loading) return;
        PluginManager.InstalledPlugin plugin=PluginManager.INSTANCE.getInstalled(pluginId);
        if (plugin==null || !plugin.enabled) { model=null; update(); return; }
        loading=true;
        Utilities.globalQueue.postRunnable(() -> {
            PluginSettingsModel result=PluginSettingsModel.load(pluginId);
            AndroidUtilities.runOnUIThread(() -> { loading=false; model=result; update(); });
        });
    }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PluginManager.InstalledPlugin plugin=PluginManager.INSTANCE.getInstalled(pluginId);
        if (plugin==null || !plugin.enabled) { items.add(UItem.asShadow(ZephyrPluginsActivity.s(R.string.ZephyrPluginSettingsNotLoaded))); return; }
        if (model==null || model.items.isEmpty()) { items.add(UItem.asShadow(ZephyrPluginsActivity.s(loading ? R.string.Loading : R.string.ZephyrPluginSettingsEmpty))); return; }
        int index=0;
        for (PluginSettingsModel.Item setting : model.items) {
            UItem item;
            switch (setting.type) {
                case PluginSettingsModel.TYPE_HEADER: item=UItem.asHeader(setting.text); break;
                case PluginSettingsModel.TYPE_DIVIDER: item=UItem.asShadow(setting.text); break;
                case PluginSettingsModel.TYPE_SWITCH: item=UItem.asCheck(++index,setting.text).setChecked(model.getBoolean(setting)); break;
                case PluginSettingsModel.TYPE_INPUT: item=UItem.asButton(++index,setting.text,model.getString(setting)); break;
                case PluginSettingsModel.TYPE_SELECTOR:
                    int value=model.getInt(setting);
                    item=UItem.asButton(++index,setting.text,setting.options!=null && value>=0 && value<setting.options.length ? setting.options[value] : ""); break;
                default: item=UItem.asButton(++index,setting.text);
            }
            item.object=setting; items.add(item);
            if (setting.subtext!=null && !setting.subtext.isEmpty()) items.add(UItem.asShadow(setting.subtext));
        }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (model==null || !(item.object instanceof PluginSettingsModel.Item)) return;
        PluginSettingsModel.Item setting=(PluginSettingsModel.Item)item.object;
        switch (setting.type) {
            case PluginSettingsModel.TYPE_SWITCH: model.setValue(setting,!model.getBoolean(setting)); update(); break;
            case PluginSettingsModel.TYPE_INPUT:
                EditText input=new EditText(getParentActivity()); input.setText(model.getString(setting)); input.setSingleLine(!setting.multiline);
                if (setting.hint!=null) input.setHint(setting.hint);
                showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(setting.text).setView(input)
                        .setPositiveButton(ZephyrPluginsActivity.s(R.string.OK),(d,w) -> { model.setValue(setting,input.getText().toString()); update(); })
                        .setNegativeButton(ZephyrPluginsActivity.s(R.string.Cancel),null).create()); break;
            case PluginSettingsModel.TYPE_SELECTOR:
                if (setting.options==null) return;
                showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(setting.text).setItems(setting.options,
                        (d,which) -> { model.setValue(setting,which); update(); }).create()); break;
            case PluginSettingsModel.TYPE_TEXT: model.click(setting); break;
        }
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    private void update() { if (listView!=null && listView.adapter!=null) listView.adapter.update(true); }
}
