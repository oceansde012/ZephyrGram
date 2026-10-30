package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zephyr.plugins.PluginIndex;
import org.telegram.messenger.zephyr.plugins.PluginManager;
import org.telegram.messenger.zephyr.plugins.PluginManifest;
import org.telegram.messenger.zephyr.plugins.PluginRepository;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Locale;

/** Store and installed plugins. File installation always requires explicit consent. */
public class ZephyrPluginsActivity extends UniversalFragment implements PluginManager.Listener, PluginRepository.Listener {
    private static final int REFRESH=1, AUTO=2, UPDATE_ALL=3, SEARCH=4, DEVELOPER=5, IMPORT=6, PICK_FILE=0x5A10;
    private String query = "";
    private final PluginManager manager = PluginManager.INSTANCE;
    private final PluginRepository repository = PluginRepository.INSTANCE;

    static String s(int id) { return LocaleController.getString(id); }
    static void error(BaseFragment fragment, String message) {
        if (message == null || fragment.getParentActivity() == null) return;
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(s(R.string.ZephyrPlugins)).setMessage(message)
                .setPositiveButton(s(R.string.OK), null).create());
    }
    static String permissions(java.util.List<String> permissions) {
        if (permissions.isEmpty()) return s(R.string.ZephyrPluginNoPermissions);
        ArrayList<String> labels = new ArrayList<>();
        for (String permission : permissions) {
            int id = R.string.ZephyrPluginPermUnknown;
            if ("network.requests".equals(permission)) id = R.string.ZephyrPluginPermNetworkRequests;
            else if ("updates.read".equals(permission)) id = R.string.ZephyrPluginPermUpdatesRead;
            else if ("messages.send".equals(permission)) id = R.string.ZephyrPluginPermMessagesSend;
            else if ("ui.menu".equals(permission)) id = R.string.ZephyrPluginPermUiMenu;
            else if ("internet".equals(permission)) id = R.string.ZephyrPluginPermInternet;
            else if ("java.raw".equals(permission)) id = R.string.ZephyrPluginPermJavaRaw;
            else if ("ai.complete".equals(permission)) id = R.string.ZephyrPluginPermAiComplete;
            else if ("chat.read".equals(permission)) id = R.string.ZephyrPluginPermChatRead;
            labels.add(s(id));
        }
        String text = android.text.TextUtils.join("\n", labels);
        if (permissions.contains("java.raw")) text += "\n\n" + s(R.string.ZephyrPluginRawJavaWarning);
        return text;
    }
    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        manager.addListener(this); repository.addListener(this); manager.initialize();
        return true;
    }
    @Override public void onFragmentDestroy() {
        manager.removeListener(this); repository.removeListener(this); super.onFragmentDestroy();
    }
    @Override public View createView(Context context) {
        View view = super.createView(context);
        if (repository.getIndex() == null && !repository.isLoading()) repository.refresh(null);
        return view;
    }
    @Override protected CharSequence getTitle() { return s(R.string.ZephyrPlugins); }
    private boolean matches(String text) { return query.isEmpty() || text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)); }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(REFRESH, R.drawable.msg_retry, s(R.string.ZephyrPluginsRefresh)).setEnabled(!repository.isLoading()));
        items.add(UItem.asButton(SEARCH, R.drawable.msg_search, s(R.string.ZephyrPluginsSearchHint), query));
        items.add(UItem.asCheck(AUTO, s(R.string.ZephyrPluginsAutoUpdate)).setChecked(manager.isAutoUpdateEnabled()));
        items.get(items.size() - 1).subtext = s(R.string.ZephyrUxPluginsAutoUpdateInfo);
        if (manager.isSafeMode()) items.add(UItem.asShadow(s(R.string.ZephyrPluginsSafeMode)));
        items.add(UItem.asHeader(s(R.string.ZephyrPluginsInstalled)));
        boolean found = false;
        boolean updates = false;
        for (PluginManager.InstalledPlugin plugin : manager.getInstalled()) {
            if (manager.getUpdate(plugin.id) != null) updates = true;
            if (!matches(plugin.name + " " + plugin.id)) continue;
            UItem item = UItem.asButton(0, plugin.name, plugin.enabled ? s(R.string.ZephyrPluginEnabled) : s(R.string.ZephyrPluginsDisabledUser));
            item.object = plugin.id; items.add(item); found = true;
        }
        if (!found) items.add(UItem.asShadow(s(query.isEmpty() ? R.string.ZephyrPluginsNoneInstalled : R.string.ZephyrPluginsNothingFound)));
        if (updates) items.add(UItem.asButton(UPDATE_ALL, s(R.string.ZephyrPluginsUpdateAll)).setEnabled(!manager.isBusy()));
        items.add(UItem.asHeader(s(R.string.ZephyrPluginsStore)));
        PluginIndex index = repository.getIndex();
        found = false;
        if (index != null) for (PluginIndex.IndexEntry plugin : index.visiblePlugins()) {
            if (!matches(plugin.name + " " + plugin.id + " " + plugin.description)) continue;
            UItem item = UItem.asButton(0, plugin.name, plugin.author != null && !plugin.author.isEmpty() ? String.format(s(R.string.ZephyrPluginsVersionBy), plugin.latest, plugin.author) : plugin.latest);
            item.object = plugin.id; items.add(item); found = true;
        }
        if (!found) items.add(UItem.asShadow(s(repository.isLoading() ? R.string.ZephyrPluginsLoading : index == null ? R.string.ZephyrPluginsOfflineEmpty : R.string.ZephyrPluginsNothingFound)));
        if (index != null && repository.isStale()) items.add(UItem.asShadow(s(R.string.ZephyrPluginsOffline)));
        if (repository.getLastError() != null) items.add(UItem.asShadow(repository.getLastError()));
        items.add(UItem.asCheck(DEVELOPER, s(R.string.ZephyrPluginsDeveloperMode)).setChecked(manager.isDeveloperModeEnabled()));
        items.get(items.size() - 1).subtext = s(R.string.ZephyrUxPluginsDevInfo);
        if (manager.isDeveloperModeEnabled()) items.add(UItem.asButton(IMPORT, s(R.string.ZephyrPluginsInstallFromFile)));
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof String) { presentFragment(new ZephyrPluginDetailsActivity((String)item.object)); return; }
        switch (item.id) {
            case REFRESH: repository.refresh((index, failure) -> error(this, failure)); break;
            case AUTO: manager.setAutoUpdateEnabled(!manager.isAutoUpdateEnabled()); break;
            case UPDATE_ALL: manager.updateAll(failure -> error(this, failure)); break;
            case DEVELOPER: manager.setDeveloperModeEnabled(!manager.isDeveloperModeEnabled()); break;
            case SEARCH:
                EditText input = new EditText(getParentActivity()); input.setText(query); input.setSingleLine(true);
                showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(s(R.string.ZephyrPluginsSearchHint))
                        .setView(input).setPositiveButton(s(R.string.OK), (d,w) -> { query=input.getText().toString().trim(); update(); })
                        .setNegativeButton(s(R.string.Cancel), null).create());
                break;
            case IMPORT:
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, PICK_FILE);
                } catch (Exception e) { error(this, e.getMessage()); }
                break;
        }
    }
    @Override public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != PICK_FILE || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File directory = new File(ApplicationLoader.applicationContext.getCacheDir(), "zephyr-imports");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException(s(R.string.ZephyrUxPluginErrCreateDir));
                File file = File.createTempFile("plugin-", ".plugin", directory);
                ByteArrayOutputStream source = new ByteArrayOutputStream();
                try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(file)) {
                    if (in == null) throw new java.io.IOException(s(R.string.ZephyrUxPluginErrRead));
                    byte[] buffer = new byte[8192]; int count, total=0;
                    while ((count=in.read(buffer)) != -1) {
                        total+=count;
                        if (total>PluginManifest.MAX_SOURCE_BYTES) throw new java.io.IOException(s(R.string.ZephyrUxPluginErrTooBig));
                        out.write(buffer,0,count);
                        source.write(buffer,0,count);
                    }
                }
                PluginManifest manifest = PluginManifest.parse(new String(source.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                AndroidUtilities.runOnUIThread(() -> {
                    if (getParentActivity()==null) return;
                    showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(s(R.string.ZephyrPluginsInstallFromFileTitle))
                            .setMessage(manifest.name+"\n\n"+s(R.string.ZephyrPluginsInstallFromFileDevHint)+"\n\n"+permissions(manifest.permissions)
                                    +"\n\n"+s(R.string.ZephyrPluginRawJavaWarning))
                            .setPositiveButton(s(R.string.ZephyrPluginsInstall), (d,w) -> manager.installFromFile(file,true,failure -> error(this,failure)))
                            .setNegativeButton(s(R.string.Cancel),null).create());
                });
            } catch (Exception e) { AndroidUtilities.runOnUIThread(() -> error(this,e.getMessage())); }
        });
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    private void update() { if (listView != null && listView.adapter != null) listView.adapter.update(true); }
    @Override public void onPluginsChanged() { update(); }
    @Override public void onRepositoryChanged() { update(); }
}
