package org.telegram.ui;

import android.Manifest;
import android.os.Build;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zephyr.ZephyrPush;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.PermissionRequest;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/** "Background connection": the FCM replacement (foreground service + persistent connection). */
public class ZephyrPushSettingsActivity extends ZephyrBaseSettingsActivity {

    private static final int ID_ENABLE = 9201;
    private static final int ID_STATUS = 9202;
    private static final int ID_BATTERY = 9203;
    private static final int ID_AUTOSTART = 9204;
    private static final int ID_NOTIF_PERMISSION = 9205;
    private static final int ID_CHANNEL = 9206;
    private static final int ID_DONTKILL = 9207;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrPushTitle);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private String statusText() {
        if (!ZephyrPush.isServiceEnabled()) {
            return LocaleController.getString(R.string.ZephyrPushStatusOff);
        }
        if (!ZephyrPush.serviceRunning) {
            return LocaleController.getString(R.string.ZephyrPushStatusStopped);
        }
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        return LocaleController.getString(state == ConnectionsManager.ConnectionStateConnected
                ? R.string.ZephyrPushStatusConnected : R.string.ZephyrPushStatusConnecting);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrPushTitle)));
        items.add(UItem.asCheck(ID_ENABLE, LocaleController.getString(R.string.ZephyrPushEnable)).setChecked(ZephyrPush.isServiceEnabled()));
        items.add(UItem.asButton(ID_STATUS, LocaleController.getString(R.string.ZephyrPushStatus), statusText()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrPushInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrPushReliability)));
        if (Build.VERSION.SDK_INT >= 23) {
            items.add(UItem.asButton(ID_BATTERY, LocaleController.getString(R.string.ZephyrPushBattery),
                    LocaleController.getString(ZephyrPush.isIgnoringBatteryOptimizations() ? R.string.ZephyrPushBatteryUnrestricted : R.string.ZephyrPushBatteryOptimized)));
        }
        items.add(UItem.asButton(ID_AUTOSTART, LocaleController.getString(R.string.ZephyrPushAutostart)));
        if (!ZephyrPush.hasNotificationPermission()) {
            items.add(UItem.asButton(ID_NOTIF_PERMISSION, LocaleController.getString(R.string.ZephyrPushNotifPermission),
                    LocaleController.getString(R.string.ZephyrPushNotifPermissionOff)));
        }
        if (Build.VERSION.SDK_INT >= 26) {
            items.add(UItem.asButton(ID_CHANNEL, LocaleController.getString(R.string.ZephyrPushHideNotification)));
        }
        items.add(UItem.asButton(ID_DONTKILL, LocaleController.getString(R.string.ZephyrPushVendorGuide)));
        String hint = LocaleController.getString(R.string.ZephyrPushReliabilityInfo);
        if (ZephyrPush.hasVendorRestrictions()) {
            hint += "\n\n" + LocaleController.formatString(R.string.ZephyrPushVendorHint, android.os.Build.MANUFACTURER);
        }
        items.add(UItem.asShadow(hint));
    }

    @Override
    protected void onCustomClick(UItem item, View view) {
        if (getParentActivity() == null) {
            return;
        }
        switch (item.id) {
            case ID_ENABLE: {
                boolean value = !ZephyrPush.isServiceEnabled();
                ZephyrPush.setEnabled(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
                item.checked = value;
                if (value && Build.VERSION.SDK_INT >= 33 && !ZephyrPush.hasNotificationPermission() && PermissionRequest.canAskPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                    PermissionRequest.requestPermission(Manifest.permission.POST_NOTIFICATIONS, granted -> refresh());
                }
                refresh();
                break;
            }
            case ID_STATUS:
                if (ZephyrPush.isServiceEnabled() && !ZephyrPush.serviceRunning) {
                    org.telegram.messenger.ApplicationLoader.startPushService();
                }
                refresh();
                break;
            case ID_BATTERY:
                if (ZephyrPush.isIgnoringBatteryOptimizations()) {
                    // already exempt: open the system list so the user can review it
                    ZephyrPush.openBatteryOptimizationList(getParentActivity());
                } else {
                    ZephyrPush.requestIgnoreBatteryOptimizations(getParentActivity());
                }
                break;
            case ID_AUTOSTART:
                ZephyrPush.openAutostartSettings(getParentActivity());
                break;
            case ID_NOTIF_PERMISSION:
                if (PermissionRequest.canAskPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                    PermissionRequest.requestPermission(Manifest.permission.POST_NOTIFICATIONS, granted -> refresh());
                } else {
                    PermissionRequest.showPermissionSettings(Manifest.permission.POST_NOTIFICATIONS);
                }
                break;
            case ID_CHANNEL:
                ZephyrPush.openChannelSettings(getParentActivity());
                break;
            case ID_DONTKILL:
                ZephyrPush.openDontKillMyApp(getParentActivity());
                break;
        }
    }
}
