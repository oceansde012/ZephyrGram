package org.telegram.ui;

import android.view.View;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.plugins.PluginIndex;
import org.telegram.messenger.zephyr.plugins.PluginManager;
import org.telegram.messenger.zephyr.plugins.PluginRepository;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import java.util.ArrayList;
import java.util.List;

public class ZephyrPluginDetailsActivity extends UniversalFragment implements PluginManager.Listener, PluginRepository.Listener {
    private final String pluginId;
    private final PluginManager manager = PluginManager.INSTANCE;
    private final PluginRepository repository = PluginRepository.INSTANCE;
    public ZephyrPluginDetailsActivity(String id) { pluginId=id; }
    private String s(int id) { return ZephyrPluginsActivity.s(id); }
    private PluginIndex.IndexEntry entry() { PluginIndex index=repository.getIndex(); return index == null ? null : index.find(pluginId); }
    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        manager.addListener(this); repository.addListener(this); return true;
    }
    @Override public void onFragmentDestroy() { manager.removeListener(this); repository.removeListener(this); super.onFragmentDestroy(); }
    @Override protected CharSequence getTitle() {
        PluginManager.InstalledPlugin local=manager.getInstalled(pluginId); PluginIndex.IndexEntry entry=entry();
        return entry!=null ? entry.name : local!=null ? local.name : s(R.string.ZephyrPluginDetails);
    }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PluginIndex.IndexEntry entry=entry(); PluginManager.InstalledPlugin local=manager.getInstalled(pluginId);
        if (entry==null && local==null) { items.add(UItem.asShadow(s(R.string.ZephyrPluginNotFound))); return; }
        items.add(UItem.asHeader(s(R.string.ZephyrPluginAbout)));
        items.add(UItem.asShadow(entry!=null ? entry.description : local.manifest.description));
        items.add(UItem.asButton(-1, s(R.string.ZephyrPluginAuthor), entry!=null ? entry.author : local.manifest.author));
        if (entry!=null) items.add(UItem.asButton(-2, s(R.string.ZephyrPluginVersion), entry.latest));
        if (local!=null) {
            items.add(UItem.asButton(-3, s(R.string.ZephyrPluginInstalledVersion), local.version));
            items.add(UItem.asCheck(1, s(R.string.ZephyrPluginEnabled)).setChecked(local.enabled).setEnabled(!manager.isBusy()));
            items.add(UItem.asButton(2, s(R.string.ZephyrPluginSettings)).setEnabled(local.enabled));
            if (local.error!=null) items.add(UItem.asShadow(local.error));
        }
        PluginIndex.VersionEntry version=entry==null ? null : entry.bestCompatible(BuildConfig.ZEPHYR_VERSION,PluginManager.API_VERSION);
        if (entry!=null && version==null) items.add(UItem.asShadow(s(R.string.ZephyrPluginIncompatible)));
        if (version!=null && (local==null || manager.getUpdate(pluginId)!=null))
            items.add(UItem.asButton(3,s(local==null ? R.string.ZephyrPluginsInstall : R.string.ZephyrPluginsUpdate))
                    .setEnabled(!manager.isBusy() && !repository.isStale()));
        items.add(UItem.asHeader(s(R.string.ZephyrPluginPermissions)));
        List<String> permissions=entry!=null ? entry.permissions : local.permissions;
        items.add(UItem.asShadow(ZephyrPluginsActivity.permissions(permissions)));
        if (version!=null && version.changelog!=null && !version.changelog.isEmpty()) {
            items.add(UItem.asHeader(s(R.string.ZephyrPluginChangelog))); items.add(UItem.asShadow(version.changelog));
        }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        PluginManager.InstalledPlugin local=manager.getInstalled(pluginId);
        if (item.id==1 && local!=null) manager.setEnabled(pluginId,!local.enabled,failure -> ZephyrPluginsActivity.error(this,failure));
        else if (item.id==2 && local!=null && local.enabled) presentFragment(new ZephyrPluginSettingsActivity(pluginId));
        else if (item.id==3) {
            PluginIndex.IndexEntry entry=entry();
            if (entry==null || getParentActivity()==null) return;
            showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(entry.name)
                    .setMessage(ZephyrPluginsActivity.permissions(entry.permissions)+"\n\n"+s(R.string.ZephyrPluginRawJavaWarning))
                    .setPositiveButton(s(local==null ? R.string.ZephyrPluginsInstall : R.string.ZephyrPluginApprove),
                            (d,w) -> manager.install(pluginId,true,failure -> ZephyrPluginsActivity.error(this,failure)))
                    .setNegativeButton(s(R.string.Cancel),null).create());
        }
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    private void update() { if (listView!=null && listView.adapter!=null) listView.adapter.update(true); }
    @Override public void onPluginsChanged() { update(); }
    @Override public void onRepositoryChanged() { update(); }
}
