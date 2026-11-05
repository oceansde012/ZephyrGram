package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import org.telegram.messenger.zephyr.plugins.PluginRuntime;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.plugins.PluginChatApi;
import org.telegram.messenger.zephyr.plugins.PluginPickerContext;
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
import java.util.HashMap;
import java.util.HashSet;

public class ZephyrPluginSettingsActivity extends UniversalFragment implements PluginManager.Listener {
    private final String pluginId;
    /** Key of the opened ui.Page row (null = the main screen) and its title. */
    private final String page;
    private final String pageTitle;
    private PluginSettingsModel model;
    private boolean loading;
    /** A reload was asked for while one was running: the running one may have read older values, so load again. */
    private boolean loadAgain;
    private final Runnable loadRunnable=this::load;
    /**
     * Rows by setting key, reused on every rebuild. A new view per rebuild made every row look new to the list,
     * so each reload (coming back from a sub-screen, a changed value) redrew the whole screen.
     */
    private final HashMap<String, ZephyrWrapCell> cells=new HashMap<>();
    /**
     * Follows writes from elsewhere while the screen is open: the plugin itself (a chat added or paused after
     * errors), the app's Auto-reply tab. Kept in a field: SharedPreferences holds listeners weakly. Main thread.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    public ZephyrPluginSettingsActivity(String id) { this(id,null,null); }
    public ZephyrPluginSettingsActivity(String id, String page, String pageTitle) {
        pluginId=id;
        this.page=page;
        this.pageTitle=pageTitle;
        // Built here, after pluginId is assigned (a field initializer cannot read a final field of this class).
        prefsListener=(sp,key) -> {
            if (key==null || shows(key) || (ZephyrAutoReplyStore.PLUGIN_ID.equals(this.pluginId) && ZephyrAutoReplyStore.isListKey(key))) reloadSoon();
        };
    }
    @Override protected CharSequence getTitle() { return pageTitle!=null && !pageTitle.isEmpty() ? pageTitle : ZephyrPluginsActivity.s(R.string.ZephyrPluginSettings); }
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
            PluginSettingsModel result=PluginSettingsModel.load(pluginId,page);
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
        HashSet<String> used=new HashSet<>();
        for (PluginSettingsModel.Item setting : model.items) {
            UItem item;
            if (setting.type==PluginSettingsModel.TYPE_HEADER) item=UItem.asHeader(setting.text);
            else if (setting.type==PluginSettingsModel.TYPE_DIVIDER) item=UItem.asShadow(setting.text);
            else if (setting.type==PluginSettingsModel.TYPE_PAGE) {
                // Navigation row (like the category list of exteraGram): icon + title, opens a sub-screen.
                item=UItem.asButton(rowId(setting),pageIcon(setting.icon),setting.text);
            } else {
                // Own wrapping row: the stock one-line cells cut long titles and values with "...".
                boolean isSwitch=setting.type==PluginSettingsModel.TYPE_SWITCH;
                String value="";
                if (setting.type==PluginSettingsModel.TYPE_INPUT) value=shorten(model.getString(setting));
                else if (setting.type==PluginSettingsModel.TYPE_SELECTOR) {
                    int selected=model.getInt(setting);
                    value=setting.options!=null && selected>=0 && selected<setting.options.length ? setting.options[selected] : "";
                }
                ZephyrWrapCell cell=cells.get(setting.key);
                if (cell==null) { cell=new ZephyrWrapCell(getParentActivity()); cells.put(setting.key,cell); }
                used.add(setting.key);
                cell.setData(setting.text,value,isSwitch,isSwitch && model.getBoolean(setting),true);
                final UItem row=UItem.asCustom(cell,LayoutHelper.WRAP_CONTENT);
                row.id=rowId(setting);
                cell.setOnClickListener(v -> onClick(row,v,-1,0,0));
                item=row;
            }
            item.object=setting; items.add(item);
            if (setting.subtext!=null && !setting.subtext.isEmpty()) items.add(UItem.asShadow(setting.subtext));
        }
        cells.keySet().retainAll(used);
    }
    /** Stable per key (keys are unique on a screen), so a rebuilt row is the same row. */
    private static int rowId(PluginSettingsModel.Item setting) { return setting.key==null ? 0 : setting.key.hashCode(); }
    /** Icon names a plugin may use on a ui.Page row. */
    private static int pageIcon(String name) {
        if (name==null) return R.drawable.msg_info;
        switch (name) {
            case "general": return R.drawable.msg_settings;
            case "style": return R.drawable.msg_theme;
            case "chats": return R.drawable.msg_discussion;
            case "timing": return R.drawable.msg_recent;
            case "limits": return R.drawable.msg_stats;
            case "commands": return R.drawable.msg_bot;
            case "memory": return R.drawable.msg_archive;
            case "prompts": return R.drawable.msg_edit;
            case "test": return R.drawable.msg_msgbubble3;
            case "log": return R.drawable.msg_log;
            default: return R.drawable.msg_info;
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
            case PluginSettingsModel.TYPE_PAGE:
                ZephyrPluginSettingsActivity child = new ZephyrPluginSettingsActivity(pluginId,setting.key,setting.text);
                child.setCurrentAccount(currentAccount);
                presentFragment(child);
                break;
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
            case PluginSettingsModel.TYPE_FILE_PICKER: openFilePicker(setting); break;
        }
    }
    /**
     * Plugin callbacks and changed values may change what create_settings() returns (a removed row, a chat's
     * "mode · on/off" line, the "on: X of Y" counter, preset hints, the language); rebuild the list. Coalesced.
     */
    private void reloadSoon() { AndroidUtilities.cancelRunOnUIThread(loadRunnable); AndroidUtilities.runOnUIThread(loadRunnable, 150); }
    private void openPicker(PluginSettingsModel.Item setting) {
        final PluginPickerContext context=PluginPickerContext.capture(currentAccount);
        if (context==null) { pickerAccountChanged(); return; }
        Bundle args=new Bundle();
        args.putBoolean("onlySelect",true);
        args.putBoolean("checkCanWrite",false);
        args.putBoolean("allowGlobalSearch",true);
        args.putInt("dialogsType",DialogsActivity.DIALOGS_TYPE_DEFAULT);
        DialogsActivity picker=new DialogsActivity(args);
        picker.setCurrentAccount(context.account);
        picker.setDelegate((fragment,dids,message,param,notify,scheduleDate,scheduleRepeatPeriod,topicsFragment) -> {
            if (dids==null || dids.isEmpty()) return false;
            if (!context.isCurrent() || picker.getCurrentAccount()!=context.account) {
                picker.finishFragment(); pickerAccountChanged(); return false;
            }
            final long dialogId=dids.get(0).dialogId;
            final int account=context.account;
            picker.finishFragment();
            Utilities.globalQueue.postRunnable(() -> {
                if (!context.isCurrent()) { AndroidUtilities.runOnUIThread(this::pickerAccountChanged); return; }
                String title=String.valueOf(dialogId);
                try { title=PluginChatApi.dialogInfo(account,dialogId).optString("title",title); } catch (Throwable ignore) { }
                final String finalTitle=title;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!context.isCurrent()) { pickerAccountChanged(); return; }
                    if (model!=null) {
                        if (!model.picked(setting,dialogId,finalTitle,context)) pickerAccountChanged();
                        reloadSoon();
                    }
                });
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
        final PluginPickerContext context=PluginPickerContext.capture(currentAccount);
        if (context==null) { pickerAccountChanged(); return; }
        final int account=context.account;
        final AlertDialog progress=new AlertDialog(getParentActivity(),AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        Utilities.globalQueue.postRunnable(() -> {
            if (!context.isCurrent()) {
                AndroidUtilities.runOnUIThread(() -> { try { progress.dismiss(); } catch (Throwable ignore) { } pickerAccountChanged(); });
                return;
            }
            JSONObject found=null; String error=null;
            try { found=PluginChatApi.resolveUsernameBlocking(account,text); if (found==null) error=ZephyrPluginsActivity.s(R.string.ZephyrPluginUsernameNotFound); }
            catch (Throwable t) { error=ZephyrPluginsActivity.s(R.string.ZephyrPluginUsernameError)+" "+t.getMessage(); }
            final JSONObject result=found; final String message=error;
            AndroidUtilities.runOnUIThread(() -> {
                try { progress.dismiss(); } catch (Throwable ignore) { }
                if (!context.isCurrent()) { pickerAccountChanged(); return; }
                if (result!=null && model!=null) {
                    if (!model.picked(setting,result.optLong("id"),result.optString("title"),context)) pickerAccountChanged();
                    reloadSoon();
                }
                else ZephyrPluginsActivity.error(this,message);
            });
        });
    }
    private static final int PICK_PLUGIN_FILES=0x5A11;
    private static final int MAX_PICKED_FILES=20;
    private static final long MAX_PICKED_BYTES=50L*1024*1024;
    private PluginSettingsModel.Item pendingFilePicker;
    private void openFilePicker(PluginSettingsModel.Item setting) {
        try {
            String type="*/*";
            if ("image".equals(setting.kind)) type="image/*"; else if ("video".equals(setting.kind)) type="video/*"; else if ("audio".equals(setting.kind)) type="audio/*";
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE);
            if (setting.multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
            pendingFilePicker=setting;
            startActivityForResult(intent,PICK_PLUGIN_FILES);
        } catch (Throwable t) { pendingFilePicker=null; ZephyrPluginsActivity.error(this,t.getMessage()); }
    }
    @Override public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode,resultCode,data);
        if (requestCode!=PICK_PLUGIN_FILES) return;
        final PluginSettingsModel.Item setting=pendingFilePicker; pendingFilePicker=null;
        if (setting==null || resultCode!=Activity.RESULT_OK || data==null) return;
        final ArrayList<Uri> uris=new ArrayList<>();
        if (data.getClipData()!=null) {
            for (int i=0; i<data.getClipData().getItemCount() && uris.size()<MAX_PICKED_FILES; i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData()!=null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<String> paths=new ArrayList<>(); String error=null;
            try {
                File dir=new File(PluginRuntime.getDataDirectory(pluginId),"picked");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException(ZephyrPluginsActivity.s(R.string.ZephyrUxPluginErrCreateDir));
                for (Uri uri : uris) {
                    try { paths.add("picked/"+copyPicked(uri,dir)); }
                    catch (Throwable t) { error=t.getMessage(); }
                }
            } catch (Throwable t) { error=t.getMessage(); }
            final String message=error;
            AndroidUtilities.runOnUIThread(() -> {
                if (message!=null && paths.isEmpty()) { ZephyrPluginsActivity.error(this,message); return; }
                if (message!=null) ZephyrPluginsActivity.error(this,message);
                if (model!=null && !paths.isEmpty()) { model.filesPicked(setting,paths); reloadSoon(); }
            });
        });
    }
    /** Copies one chosen document into dir under a safe, unique name and returns that name. */
    private static String copyPicked(Uri uri, File dir) throws java.io.IOException {
        String name=null;
        try (android.database.Cursor c=ApplicationLoader.applicationContext.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if (c!=null && c.moveToFirst()) name=c.getString(0);
        } catch (Throwable ignore) { }
        if (name==null || name.isEmpty()) name="file";
        name=name.replaceAll("[^\\p{L}\\p{N}._-]+","_");
        if (name.length()>80) name=name.substring(name.length()-80);
        if (name.startsWith(".")) name="_"+name;
        File file=new File(dir,name);
        int n=1;
        while (file.exists()) {
            int dot=name.lastIndexOf('.');
            String candidate=dot>0 ? name.substring(0,dot)+"_"+(n++)+name.substring(dot) : name+"_"+(n++);
            file=new File(dir,candidate);
        }
        long total=0;
        try (java.io.InputStream in=ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
             java.io.FileOutputStream out=new java.io.FileOutputStream(file)) {
            if (in==null) throw new java.io.IOException(ZephyrPluginsActivity.s(R.string.ZephyrUxPluginErrRead));
            byte[] buffer=new byte[16384]; int count;
            while ((count=in.read(buffer))!=-1) {
                total+=count;
                if (total>MAX_PICKED_BYTES) throw new java.io.IOException(ZephyrPluginsActivity.s(R.string.ZephyrPluginPickedTooBig));
                out.write(buffer,0,count);
            }
        } catch (java.io.IOException e) { file.delete(); throw e; }
        return file.getName();
    }
    private void pickerAccountChanged() {
        ZephyrPluginsActivity.error(this,ZephyrPluginsActivity.s(R.string.ZephyrPluginPickerAccountChanged));
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    /**
     * Rebinds in place without the remove/insert animation: rows keep their views, so only changed texts and
     * switches redraw. The animated diff saw every row as new and redrew the whole screen on each reload.
     */
    private void update() { if (listView!=null && listView.adapter!=null) listView.adapter.update(false); }
}

