package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.plugins.PluginChatApi;
import org.json.JSONObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.zephyr.ZephyrAutoReplyStore;
import org.telegram.messenger.zephyr.plugins.PluginManager;
import org.telegram.messenger.zephyr.plugins.PluginSettingsModel;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.ZephyrWrapCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import java.util.ArrayList;

public class ZephyrPluginSettingsActivity extends UniversalFragment implements PluginManager.Listener {
    private final String pluginId;
    private PluginSettingsModel model;
    private boolean loading;
    /** A reload was asked for while one was running: the running one may have read older values, so load again. */
    private boolean loadAgain;
    private final Runnable loadRunnable=this::load;
    /**
     * Follows writes from elsewhere while the screen is open: the plugin itself (a chat added or paused after
     * errors), the app's Auto-reply tab. Kept in a field: SharedPreferences holds listeners weakly. Main thread.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener=(sp,key) -> {
        if (key==null || shows(key) || (ZephyrAutoReplyStore.PLUGIN_ID.equals(pluginId) && ZephyrAutoReplyStore.isListKey(key))) reloadSoon();
    };
    public ZephyrPluginSettingsActivity(String id) { pluginId=id; }
    @Override protected CharSequence getTitle() { return ZephyrPluginsActivity.s(R.string.ZephyrPluginSettings); }
    @Override public boolean onFragmentCreate() {
        PluginManager.INSTANCE.addListener(this);
        try { prefs().registerOnSharedPreferenceChangeListener(prefsListener); } catch (Throwable ignore) { }
        return super.onFragmentCreate();
    }
    @Override public void onFragmentDestroy() {
        PluginManager.INSTANCE.removeListener(this);
        try { prefs().unregisterOnSharedPreferenceChangeListener(prefsListener); } catch (Throwable ignore) { }
        AndroidUtilities.cancelRunOnUIThread(loadRunnable);
        super.onFragmentDestroy();
    }
    @Override public void onPluginsChanged() { reloadSoon(); }
    @Override public void onResume() { super.onResume(); load(); }
    private SharedPreferences prefs() { return ApplicationLoader.applicationContext.getSharedPreferences("zephyr_plugin_"+pluginId, Context.MODE_PRIVATE); }
    /** The changed key is a setting shown on this screen. */
    private boolean shows(String key) {
        PluginSettingsModel m=model;
        if (m==null) return false;
        for (PluginSettingsModel.Item it : m.items) if (key.equals(it.key)) return true;
        return false;
    }
    private void load() {
        if (loading) { loadAgain=true; return; }
        PluginManager.InstalledPlugin plugin=PluginManager.INSTANCE.getInstalled(pluginId);
        if (plugin==null || !plugin.enabled) { model=null; update(); return; }
        loading=true;
        Utilities.globalQueue.postRunnable(() -> {
            PluginSettingsModel result=PluginSettingsModel.load(pluginId);
            AndroidUtilities.runOnUIThread(() -> {
                loading=false; model=result; update();
                if (loadAgain) { loadAgain=false; load(); }
            });
        });
    }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PluginManager.InstalledPlugin plugin=PluginManager.INSTANCE.getInstalled(pluginId);
        if (plugin==null || !plugin.enabled) { items.add(UItem.asShadow(ZephyrPluginsActivity.s(R.string.ZephyrPluginSettingsNotLoaded))); return; }
        if (model==null || model.items.isEmpty()) { items.add(UItem.asShadow(ZephyrPluginsActivity.s(loading ? R.string.Loading : R.string.ZephyrPluginSettingsEmpty))); return; }
        if (getParentActivity()==null) return;
        int index=0;
        for (PluginSettingsModel.Item setting : model.items) {
            UItem item;
            if (setting.type==PluginSettingsModel.TYPE_HEADER) item=UItem.asHeader(setting.text);
            else if (setting.type==PluginSettingsModel.TYPE_DIVIDER) item=UItem.asShadow(setting.text);
            else {
                // Own wrapping row: the stock one-line cells cut long titles and values with "...".
                boolean isSwitch=setting.type==PluginSettingsModel.TYPE_SWITCH;
                String value="";
                if (setting.type==PluginSettingsModel.TYPE_INPUT) value=shorten(model.getString(setting));
                else if (setting.type==PluginSettingsModel.TYPE_SELECTOR) {
                    int selected=model.getInt(setting);
                    value=setting.options!=null && selected>=0 && selected<setting.options.length ? setting.options[selected] : "";
                }
                ZephyrWrapCell cell=new ZephyrWrapCell(getParentActivity());
                cell.setData(setting.text,value,isSwitch,isSwitch && model.getBoolean(setting),true);
                final UItem row=UItem.asCustom(cell,LayoutHelper.WRAP_CONTENT);
                row.id=++index;
                cell.setOnClickListener(v -> onClick(row,v,-1,0,0));
                item=row;
            }
            item.object=setting; items.add(item);
            if (setting.subtext!=null && !setting.subtext.isEmpty()) items.add(UItem.asShadow(setting.subtext));
        }
    }
    /** A long saved value (a prompt, a lore) would blow the row up: show the beginning, the dialog has the rest. */
    private static String shorten(String text) {
        if (text==null) return "";
        String t=text.trim().replaceAll("\\s*\\n\\s*"," / ");
        return t.length()>140 ? t.substring(0,140)+"\u2026" : t;
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (model==null || !(item.object instanceof PluginSettingsModel.Item)) return;
        PluginSettingsModel.Item setting=(PluginSettingsModel.Item)item.object;
        switch (setting.type) {
            case PluginSettingsModel.TYPE_SWITCH: model.setValue(setting,!model.getBoolean(setting)); update(); reloadSoon(); break;
            case PluginSettingsModel.TYPE_INPUT:
                EditText input=new EditText(getParentActivity()); input.setText(model.getString(setting)); input.setSingleLine(!setting.multiline);
                if (setting.hint!=null) input.setHint(setting.hint);
                showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(setting.text).setView(input)
                        .setPositiveButton(ZephyrPluginsActivity.s(R.string.OK),(d,w) -> { if (model!=null) { model.setValue(setting,input.getText().toString()); update(); reloadSoon(); } })
                        .setNegativeButton(ZephyrPluginsActivity.s(R.string.Cancel),null).create()); break;
            case PluginSettingsModel.TYPE_SELECTOR:
                if (setting.options==null) return;
                showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(setting.text).setItems(setting.options,
                        (d,which) -> { if (model!=null) { model.setValue(setting,which); update(); reloadSoon(); } }).create()); break;
            case PluginSettingsModel.TYPE_TEXT: model.click(setting); reloadSoon(); break;
            case PluginSettingsModel.TYPE_DIALOG_PICKER: openPicker(setting); break;
            case PluginSettingsModel.TYPE_USERNAME_INPUT: askUsername(setting); break;
        }
    }
    /**
     * Plugin callbacks and changed values may change what create_settings() returns (a removed row, a chat's
     * "mode · on/off" line, the "on: X of Y" counter, preset hints, the language); rebuild the list. Coalesced.
     */
    private void reloadSoon() { AndroidUtilities.cancelRunOnUIThread(loadRunnable); AndroidUtilities.runOnUIThread(loadRunnable, 150); }
    private void openPicker(PluginSettingsModel.Item setting) {
        Bundle args=new Bundle();
        args.putBoolean("onlySelect",true);
        args.putBoolean("checkCanWrite",false);
        args.putBoolean("allowGlobalSearch",true);
        args.putInt("dialogsType",DialogsActivity.DIALOGS_TYPE_DEFAULT);
        DialogsActivity picker=new DialogsActivity(args);
        picker.setDelegate((fragment,dids,message,param,notify,scheduleDate,scheduleRepeatPeriod,topicsFragment) -> {
            if (dids==null || dids.isEmpty()) return false;
            final long dialogId=dids.get(0).dialogId;
            final int account=currentAccount;
            picker.finishFragment();
            Utilities.globalQueue.postRunnable(() -> {
                String title=String.valueOf(dialogId);
                try { title=PluginChatApi.dialogInfo(account,dialogId).optString("title",title); } catch (Throwable ignore) { }
                final String finalTitle=title;
                AndroidUtilities.runOnUIThread(() -> { if (model!=null) { model.picked(setting,dialogId,finalTitle); reloadSoon(); } });
            });
            return true;
        });
        presentFragment(picker);
    }
    private void askUsername(PluginSettingsModel.Item setting) {
        if (getParentActivity()==null) return;
        EditText input=new EditText(getParentActivity()); input.setSingleLine(true);
        input.setHint(setting.hint!=null ? setting.hint : ZephyrPluginsActivity.s(R.string.ZephyrUxUsernameHint));
        showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(setting.text).setView(input)
                .setPositiveButton(ZephyrPluginsActivity.s(R.string.OK),(d,w) -> resolve(setting,input.getText().toString()))
                .setNegativeButton(ZephyrPluginsActivity.s(R.string.Cancel),null).create());
    }
    private void resolve(PluginSettingsModel.Item setting, String text) {
        if (getParentActivity()==null) return;
        final int account=currentAccount;
        final AlertDialog progress=new AlertDialog(getParentActivity(),AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject found=null; String error=null;
            try { found=PluginChatApi.resolveUsernameBlocking(account,text); if (found==null) error=ZephyrPluginsActivity.s(R.string.ZephyrPluginUsernameNotFound); }
            catch (Throwable t) { error=ZephyrPluginsActivity.s(R.string.ZephyrPluginUsernameError)+" "+t.getMessage(); }
            final JSONObject result=found; final String message=error;
            AndroidUtilities.runOnUIThread(() -> {
                try { progress.dismiss(); } catch (Throwable ignore) { }
                if (result!=null && model!=null) { model.picked(setting,result.optLong("id"),result.optString("title")); reloadSoon(); }
                else ZephyrPluginsActivity.error(this,message);
            });
        });
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    private void update() { if (listView!=null && listView.adapter!=null) listView.adapter.update(true); }
}
