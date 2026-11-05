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
    /** Switches whose nested rows are shown (0.2.8 "N/M" rows). UI state only, per screen. */
    private final HashSet<String> expanded=new HashSet<>();
    /** One entry of the screen before it is turned into list rows: a header, a card row or a note under a card. */
    private static final class Entry {
        static final int HEADER=0, ROW=1, NOTE=2;
        final int kind; final PluginSettingsModel.Item setting; final String text;
        int position=ZephyrWrapCell.POSITION_SINGLE; int indent; String counter; boolean expandable;
        Entry(int kind, PluginSettingsModel.Item setting, String text) { this.kind=kind; this.setting=setting; this.text=text; }
    }
    /**
     * Screen = cards. A header starts a new card (small grey text above it); a divider row ends one and its text
     * becomes a note under it. Row subtexts are the second line of the row. Rows with item.parent == a switch key
     * sit under that switch and show only while it is expanded.
     */
    private ArrayList<Entry> buildEntries() {
        ArrayList<Entry> entries=new ArrayList<>();
        HashMap<String,ArrayList<PluginSettingsModel.Item>> children=new HashMap<>();
        HashSet<String> keys=new HashSet<>();
        for (PluginSettingsModel.Item it : model.items) if (it.key!=null) keys.add(it.key);
        ArrayList<PluginSettingsModel.Item> top=new ArrayList<>();
        for (PluginSettingsModel.Item it : model.items) {
            if (it.parent!=null && !it.parent.equals(it.key) && keys.contains(it.parent) && isSwitchKey(it.parent)) {
                ArrayList<PluginSettingsModel.Item> list=children.get(it.parent);
                if (list==null) { list=new ArrayList<>(); children.put(it.parent,list); }
                list.add(it);
            } else top.add(it);
        }
        for (PluginSettingsModel.Item setting : top) {
            if (setting.type==PluginSettingsModel.TYPE_HEADER) { entries.add(new Entry(Entry.HEADER,setting,setting.text)); continue; }
            if (setting.type==PluginSettingsModel.TYPE_DIVIDER) { entries.add(new Entry(Entry.NOTE,setting,setting.text)); continue; }
            Entry e=new Entry(Entry.ROW,setting,null);
            ArrayList<PluginSettingsModel.Item> kids=setting.type==PluginSettingsModel.TYPE_SWITCH ? children.get(setting.key) : null;
            if (kids!=null) {
                int total=0, on=0;
                for (PluginSettingsModel.Item k : kids) if (k.type==PluginSettingsModel.TYPE_SWITCH) { total++; if (model.getBoolean(k)) on++; }
                if (total==0) total=kids.size();
                e.expandable=true; e.counter=total==0 ? null : on+"/"+total;
            }
            entries.add(e);
            if (kids!=null && expanded.contains(setting.key)) {
                for (PluginSettingsModel.Item k : kids) { Entry c=new Entry(Entry.ROW,k,null); c.indent=1; entries.add(c); }
            }
        }
        // Card positions: a run of rows between headers/notes.
        int i=0;
        while (i<entries.size()) {
            if (entries.get(i).kind!=Entry.ROW) { i++; continue; }
            int j=i; while (j+1<entries.size() && entries.get(j+1).kind==Entry.ROW) j++;
            for (int k=i; k<=j; k++) entries.get(k).position=i==j ? ZephyrWrapCell.POSITION_SINGLE : k==i ? ZephyrWrapCell.POSITION_FIRST : k==j ? ZephyrWrapCell.POSITION_LAST : ZephyrWrapCell.POSITION_MIDDLE;
            i=j+1;
        }
        return entries;
    }
    private boolean hasChildren(PluginSettingsModel.Item setting) {
        if (model==null || setting.key==null) return false;
        for (PluginSettingsModel.Item it : model.items) if (setting.key.equals(it.parent) && !setting.key.equals(it.key)) return true;
        return false;
    }
    private boolean isSwitchKey(String key) {
        for (PluginSettingsModel.Item it : model.items) if (key.equals(it.key)) return it.type==PluginSettingsModel.TYPE_SWITCH;
        return false;
    }
    private static boolean hasSwitch(ArrayList<PluginSettingsModel.Item> list) {
        for (PluginSettingsModel.Item it : list) if (it.type==PluginSettingsModel.TYPE_SWITCH) return true;
        return false;
    }
    private View blockFor(String cacheKey, int mode, CharSequence text) {
        View v=blocks.get(cacheKey);
        ZephyrWrapCell.TextBlock block=v instanceof ZephyrWrapCell.TextBlock ? (ZephyrWrapCell.TextBlock)v : new ZephyrWrapCell.TextBlock(getParentActivity());
        blocks.put(cacheKey,block);
        block.bind(mode,text);
        return block;
    }
    private final HashMap<String,View> blocks=new HashMap<>();
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PluginManager.InstalledPlugin plugin=PluginManager.INSTANCE.getInstalled(pluginId);
        if (plugin==null || !plugin.enabled) { items.add(UItem.asShadow(ZephyrPluginsActivity.s(R.string.ZephyrPluginSettingsNotLoaded))); return; }
        if (model==null || model.items.isEmpty()) { items.add(UItem.asShadow(ZephyrPluginsActivity.s(loading ? R.string.Loading : R.string.ZephyrPluginSettingsEmpty))); return; }
        if (getParentActivity()==null) return;
        HashSet<String> used=new HashSet<>();
        HashSet<String> usedBlocks=new HashSet<>();
        usedBlocks.add("top");
        items.add(blockItem("top".hashCode(),blockFor("top",ZephyrWrapCell.TextBlock.SPACE,null)));
        ArrayList<Entry> entries=buildEntries();
        for (int n=0; n<entries.size(); n++) {
            Entry e=entries.get(n);
            PluginSettingsModel.Item setting=e.setting;
            if (e.kind!=Entry.ROW) {
                String cacheKey=(e.kind==Entry.HEADER ? "h:" : "n:")+setting.key;
                usedBlocks.add(cacheKey);
                UItem block=blockItem(cacheKey.hashCode(),blockFor(cacheKey,e.kind==Entry.HEADER ? ZephyrWrapCell.TextBlock.HEADER : ZephyrWrapCell.TextBlock.NOTE,e.text));
                block.object=setting; items.add(block);
                continue;
            }
            boolean isSwitch=setting.type==PluginSettingsModel.TYPE_SWITCH;
            boolean isPage=setting.type==PluginSettingsModel.TYPE_PAGE;
            String value="", sub=setting.subtext==null ? "" : setting.subtext;
            if (setting.type==PluginSettingsModel.TYPE_INPUT) value=shorten(model.getString(setting));
            else if (setting.type==PluginSettingsModel.TYPE_SELECTOR) {
                int selected=model.getInt(setting);
                value=setting.options!=null && selected>=0 && selected<setting.options.length ? setting.options[selected] : "";
            } else if (setting.type==PluginSettingsModel.TYPE_TEXT && !sub.isEmpty() && sub.length()<=24 && sub.indexOf('\n')<0) {
                value=sub; sub="";   // a short note of a plain row reads as its value on the right
            }
            CharSequence title=setting.text;
            if (e.expandable) {
                boolean open=expanded.contains(setting.key);
                title=(setting.text==null ? "" : setting.text)+(e.counter==null ? "" : "  "+e.counter)+(open ? " \u25B4" : " \u25BE");
            }
            ZephyrWrapCell cell=cells.get(setting.key);
            if (cell==null) { cell=new ZephyrWrapCell(getParentActivity()); cells.put(setting.key,cell); }
            used.add(setting.key);
            cell.bind(title,sub,value,isPage ? pageIcon(setting.icon) : 0,isSwitch,isSwitch && model.getBoolean(setting),e.expandable,isPage,e.indent,e.position);
            final UItem row=UItem.asCustom(cell,LayoutHelper.WRAP_CONTENT);
            row.id=rowId(setting);
            final ZephyrWrapCell clicked=cell;
            cell.setOnClickListener(v -> onClick(row,clicked,-1,0,0));
            row.object=setting; items.add(row);
        }
        // Closing space under the last card.
        usedBlocks.add("end");
        items.add(blockItem("end".hashCode(),blockFor("end",ZephyrWrapCell.TextBlock.SPACE,null)));
        cells.keySet().retainAll(used);
        blocks.keySet().retainAll(usedBlocks);
    }
    private static UItem blockItem(int id, View view) {
        UItem item=UItem.asCustom(view,LayoutHelper.WRAP_CONTENT);
        item.id=id;
        return item;
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
            case PluginSettingsModel.TYPE_SWITCH:
                if (hasChildren(setting) && !(view instanceof ZephyrWrapCell && ((ZephyrWrapCell)view).wasSwitchTouched())) {
                    if (!expanded.remove(setting.key)) expanded.add(setting.key);
                    update();
                    break;
                }
                model.setValue(setting,!model.getBoolean(setting)); update(); reloadSoon(); break;
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
        if (setting==null) return;
        // A cancelled choice still reaches the plugin as an empty list, so it can close its own state.
        if (resultCode!=Activity.RESULT_OK || data==null) { if (model!=null) model.filesPicked(setting,new ArrayList<String>()); return; }
        final ArrayList<Uri> uris=new ArrayList<>();
        if (data.getClipData()!=null) {
            for (int i=0; i<data.getClipData().getItemCount() && uris.size()<MAX_PICKED_FILES; i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData()!=null) uris.add(data.getData());
        if (uris.isEmpty()) { if (model!=null) model.filesPicked(setting,new ArrayList<String>()); return; }
        Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<String> paths=new ArrayList<>(); String error=null;
            PluginSettingsModel.prunePicked(pluginId);
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
                if (message!=null) ZephyrPluginsActivity.error(this,message);
                if (model!=null) { model.filesPicked(setting,paths); reloadSoon(); }
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

