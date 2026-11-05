package org.telegram.messenger.zephyr;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.LaunchActivity;

import java.util.Locale;

/**
 * Background delivery without FCM. This fork cannot use Telegram's Firebase project, so the only
 * reliable way to get messages while the app is closed is a persistent MTProto connection held by
 * a foreground service (NotificationsService). Everything related to that lives here:
 * defaults, the service notification, battery-optimization / OEM autostart helpers, first-run prompt.
 * The on/off state is stored in the same keys Telegram already uses ("pushService", "pushConnection").
 */
public class ZephyrPush {

    public static final String CHANNEL_ID = "zephyr_push_service";
    public static final int NOTIFICATION_ID = 7421;
    private static final String PREFS = "zephyr_push";

    /** Set by NotificationsService while it is alive. */
    public static volatile boolean serviceRunning;

    /**
     * True only when a Firebase token is known to actually deliver Telegram pushes. In this fork it
     * never does (the token would belong to a foreign Firebase project), so the persistent
     * connection is the default transport.
     */
    public static boolean hasWorkingFcm() {
        return false;
    }

    public static boolean isServiceEnabled() {
        SharedPreferences p = MessagesController.getGlobalNotificationsSettings();
        if (p.contains("pushService")) {
            return p.getBoolean("pushService", true);
        }
        return !hasWorkingFcm() || MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", false);
    }

    public static boolean isConnectionEnabled() {
        SharedPreferences p = MessagesController.getGlobalNotificationsSettings();
        if (p.contains("pushConnection")) {
            return p.getBoolean("pushConnection", true);
        }
        return !hasWorkingFcm() || MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("backgroundConnection", false);
    }

    /** Toggles the service and the background MTProto connection together. */
    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalNotificationsSettings().edit()
                .putBoolean("pushService", enabled)
                .putBoolean("pushConnection", enabled)
                .commit();
        ApplicationLoader.startPushService();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager.getInstance(a).setPushConnectionEnabled(enabled);
            }
        }
    }

    // ---------------------------------------------------------------- foreground notification

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.ZephyrPushChannel), NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(LocaleController.getString(R.string.ZephyrPushChannelInfo));
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.enableLights(false);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static Notification buildNotification(Context context) {
        ensureChannel(context);
        Intent open = new Intent(context, LaunchActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.ZephyrPushNotifTitle))
                .setContentText(LocaleController.getString(R.string.ZephyrPushNotifText))
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    /** Must be called from the service right after startForegroundService(). Returns false when it failed. */
    public static boolean startForeground(Service service) {
        try {
            Notification n = buildNotification(service);
            if (Build.VERSION.SDK_INT >= 34) {
                service.startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                service.startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                service.startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    // ---------------------------------------------------------------- battery / OEM

    public static boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        try {
            Context c = ApplicationLoader.applicationContext;
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return ApplicationLoader.applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean start(Context context, Intent intent) {
        try {
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (Throwable e) {
            FileLog.e("ZephyrPush: startActivity failed " + e.getClass().getSimpleName() + " action=" + intent.getAction());
            return false;
        }
    }

    /** Shows the system "allow this app to ignore battery optimizations" dialog. */
    public static void requestIgnoreBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        String pkg = context.getPackageName();
        if (start(context, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)))) {
            return;
        }
        if (start(context, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            return;
        }
        openAppDetails(context);
    }

    public static void openBatteryOptimizationList(Context context) {
        if (Build.VERSION.SDK_INT >= 23 && start(context, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            return;
        }
        openAppDetails(context);
    }

    public static void openAppDetails(Context context) {
        start(context, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName())));
    }

    /** Opens the settings of the service notification channel, so the user can hide the icon. */
    public static void openChannelSettings(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ensureChannel(context);
            Intent i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID);
            if (start(context, i)) {
                return;
            }
        }
        openAppDetails(context);
    }

    public static String manufacturer() {
        return Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
    }

    private static final String[][] AUTOSTART = {
            // manufacturer keyword, package, class
            {"xiaomi", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"redmi", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"poco", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"huawei", "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"honor", "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"oppo", "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"oppo", "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"realme", "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"vivo", "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"vivo", "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"samsung", "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"},
            {"samsung", "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"oneplus", "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
            {"asus", "com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"},
            {"letv", "com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
            {"meizu", "com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
    };

    /** True if this manufacturer is known to have an aggressive vendor-specific app killer. */
    public static boolean hasVendorRestrictions() {
        String m = manufacturer();
        for (String[] e : AUTOSTART) {
            if (m.contains(e[0])) {
                return true;
            }
        }
        return m.contains("tecno") || m.contains("infinix") || m.contains("itel") || m.contains("nokia") || m.contains("lenovo") || m.contains("zte");
    }

    /** Tries the vendor autostart / app-launch screen; falls back to the app details page. */
    public static void openAutostartSettings(Context context) {
        String m = manufacturer();
        PackageManager pm = context.getPackageManager();
        for (String[] e : AUTOSTART) {
            if (!m.contains(e[0])) {
                continue;
            }
            Intent i = new Intent().setComponent(new ComponentName(e[1], e[2]));
            try {
                if (pm.resolveActivity(i, 0) != null && start(context, i)) {
                    return;
                }
            } catch (Throwable ignore) {
            }
        }
        openAppDetails(context);
    }

    public static void openDontKillMyApp(Context context) {
        String m = manufacturer().replace(' ', '-');
        String url = "https://dontkillmyapp.com/" + (m.isEmpty() ? "" : m);
        start(context, new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    // ---------------------------------------------------------------- first-run prompt

    /**
     * Asks once (per install) for the battery-optimization exemption. Safe to call on every resume:
     * does nothing when the connection is off, the exemption is already granted, nobody is logged in,
     * or the user has already seen the prompt.
     */
    public static void maybeShowFirstRunPrompt(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || Build.VERSION.SDK_INT < 23) {
                return;
            }
            SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean("promptShown", false) || !isServiceEnabled() || isIgnoringBatteryOptimizations()
                    || !UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed() || ApplicationLoader.mainInterfacePaused || prefs.getBoolean("promptShown", false)) {
                    return;
                }
                prefs.edit().putBoolean("promptShown", true).apply();
                AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                builder.setTitle(LocaleController.getString(R.string.ZephyrPushPromptTitle));
                builder.setMessage(LocaleController.getString(R.string.ZephyrPushPromptText));
                builder.setPositiveButton(LocaleController.getString(R.string.ZephyrPushPromptAllow), (d, w) -> requestIgnoreBatteryOptimizations(activity));
                builder.setNegativeButton(LocaleController.getString(R.string.ZephyrPushPromptLater), null);
                builder.show();
            }, 4000);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
