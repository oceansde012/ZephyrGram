/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

import org.telegram.messenger.zephyr.ZephyrPush;

/**
 * ZephyrGram: foreground service that keeps the process (and thus the MTProto connection) alive,
 * replacing FCM. Declared with foregroundServiceType specialUse (dataSync on older APIs).
 */
public class NotificationsService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        ZephyrPush.serviceRunning = true;
        // startForegroundService() requires startForeground() within a few seconds
        if (!ZephyrPush.startForeground(this)) {
            stopSelf();
            return;
        }
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ZephyrPush.isServiceEnabled()) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        ZephyrPush.startForeground(this);
        return START_STICKY;
    }

    // Android 15+: called when the system time-limit of the foreground service type is hit.
    public void onTimeout(int startId, int fgsType) {
        stopSelf();
        ApplicationLoader.startPushService();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        ZephyrPush.serviceRunning = false;
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (ZephyrPush.isServiceEnabled()) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
