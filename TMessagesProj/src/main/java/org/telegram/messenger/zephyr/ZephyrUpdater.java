package org.telegram.messenger.zephyr;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Self-updater: looks up the latest GitHub release of ZephyrGram, offers it to the user,
 * downloads the APK into cacheDir/updates (covered by res/xml/provider_paths.xml) and hands
 * it to the system package installer.
 */
public class ZephyrUpdater {

    private static final String RELEASES_URL = "https://api.github.com/repos/oceansde012/ZephyrGram/releases/latest";
    private static final long AUTO_CHECK_INTERVAL = 24L * 60 * 60 * 1000;
    private static final int MAX_CHANGELOG_LENGTH = 1500;
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private static final int RESULT_UPDATE = 0;
    private static final int RESULT_UP_TO_DATE = 1;
    private static final int RESULT_NO_ASSET = 2;
    private static final int RESULT_ERROR = 3;

    private static volatile boolean checking;
    private static volatile boolean downloading;

    private static class Release {
        String version;
        String changelog;
        String apkUrl;
        String apkName;
        long apkSize;
    }

    private interface CheckCallback {
        void onResult(int result, Release release);
    }

    /** Called from LaunchActivity.onResume: at most one silent check per 24 hours. */
    public static void checkOnLaunch(Activity activity) {
        if (activity == null) {
            return;
        }
        ZephyrConfig.load();
        if (!ZephyrConfig.autoUpdateCheck || checking || downloading) {
            return;
        }
        if (!UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - ZephyrConfig.lastUpdateCheck) < AUTO_CHECK_INTERVAL) {
            return;
        }
        ZephyrConfig.setLastUpdateCheck(now);
        WeakReference<Activity> activityRef = new WeakReference<>(activity);
        check((result, release) -> {
            if (result != RESULT_UPDATE) {
                return;
            }
            Activity a = activityRef.get();
            if (!isUsable(a) || SharedConfig.isWaitingForPasscodeEnter || ApplicationLoader.mainInterfacePaused) {
                return;
            }
            showUpdateDialog(a, null, release);
        });
    }

    /** Manual check from ZephyrSettingsActivity; reports every outcome. */
    public static void checkManually(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null || checking || downloading) {
            return;
        }
        Activity activity = fragment.getParentActivity();
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        try {
            progress.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        ZephyrConfig.setLastUpdateCheck(System.currentTimeMillis());
        check((result, release) -> {
            try {
                progress.dismiss();
            } catch (Exception e) {
                FileLog.e(e);
            }
            Activity a = fragment.getParentActivity();
            if (!isUsable(a)) {
                return;
            }
            switch (result) {
                case RESULT_UPDATE:
                    showUpdateDialog(a, fragment, release);
                    break;
                case RESULT_UP_TO_DATE:
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.ZephyrUpToDate)).show();
                    break;
                case RESULT_NO_ASSET:
                    BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.ZephyrUpdateNoApk)).show();
                    break;
                default:
                    BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.ZephyrUpdateCheckFailed)).show();
                    break;
            }
        });
    }

    private static boolean isUsable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void check(CheckCallback callback) {
        checking = true;
        Utilities.globalQueue.postRunnable(() -> {
            int result;
            Release release = null;
            try {
                release = fetchLatestRelease();
                if (release == null) {
                    result = RESULT_ERROR;
                } else if (compareVersions(release.version, currentVersion()) <= 0) {
                    result = RESULT_UP_TO_DATE;
                } else if (release.apkUrl == null) {
                    result = RESULT_NO_ASSET;
                } else {
                    result = RESULT_UPDATE;
                }
            } catch (Throwable e) {
                FileLog.e(e);
                result = RESULT_ERROR;
            }
            final int finalResult = result;
            final Release finalRelease = release;
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                callback.onResult(finalResult, finalRelease);
            });
        });
    }

    public static String currentVersion() {
        String version = BuildConfig.ZEPHYR_VERSION;
        return TextUtils.isEmpty(version) ? "0.0.0" : version;
    }

    private static Release fetchLatestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "ZephyrGram/" + currentVersion() + " (Android)");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                // 403/429 = rate limited, 404 = no published release yet
                FileLog.d("ZephyrUpdater: releases API returned " + code);
                return null;
            }
            String body;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                body = out.toString("UTF-8");
            }
            JSONObject json = new JSONObject(body);
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                return null;
            }
            Release release = new Release();
            String tag = json.optString("tag_name", "").trim();
            if (tag.startsWith("v") || tag.startsWith("V")) {
                tag = tag.substring(1);
            }
            if (TextUtils.isEmpty(tag)) {
                return null;
            }
            release.version = tag;
            String changelog = json.isNull("body") ? "" : json.optString("body", "").trim();
            if (changelog.length() > MAX_CHANGELOG_LENGTH) {
                changelog = changelog.substring(0, MAX_CHANGELOG_LENGTH).trim() + "…";
            }
            release.changelog = changelog;

            JSONArray assets = json.optJSONArray("assets");
            JSONObject fallback = null;
            JSONObject preferred = null;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) {
                        continue;
                    }
                    String name = asset.optString("name", "").toLowerCase(Locale.US);
                    if (!name.endsWith(".apk")) {
                        continue;
                    }
                    if (preferred == null && name.contains("arm64")) {
                        preferred = asset;
                    }
                    if (fallback == null) {
                        fallback = asset;
                    }
                }
            }
            JSONObject asset = preferred != null ? preferred : fallback;
            if (asset != null) {
                String url = asset.optString("browser_download_url", "");
                if (!TextUtils.isEmpty(url)) {
                    release.apkUrl = url;
                    release.apkName = asset.optString("name", "ZephyrGram.apk");
                    release.apkSize = asset.optLong("size", 0);
                }
            }
            return release;
        } finally {
            connection.disconnect();
        }
    }

    /** Numeric semver comparison; pre-release/build suffixes (-beta, +meta) are ignored. */
    static int compareVersions(String a, String b) {
        int[] va = parseVersion(a);
        int[] vb = parseVersion(b);
        for (int i = 0; i < Math.max(va.length, vb.length); i++) {
            int x = i < va.length ? va[i] : 0;
            int y = i < vb.length ? vb[i] : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        if (version == null) {
            return new int[0];
        }
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        int cut = v.length();
        int dash = v.indexOf('-');
        int plus = v.indexOf('+');
        if (dash >= 0) {
            cut = Math.min(cut, dash);
        }
        if (plus >= 0) {
            cut = Math.min(cut, plus);
        }
        v = v.substring(0, cut);
        String[] parts = v.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            int end = 0;
            while (end < p.length() && Character.isDigit(p.charAt(end))) {
                end++;
            }
            try {
                result[i] = end == 0 ? 0 : Integer.parseInt(p.substring(0, end));
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static void showUpdateDialog(Activity activity, BaseFragment fragment, Release release) {
        StringBuilder message = new StringBuilder(LocaleController.formatString(
                R.string.ZephyrUpdateAvailableText, release.version, currentVersion()));
        if (!TextUtils.isEmpty(release.changelog)) {
            message.append("\n\n").append(release.changelog);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.ZephyrUpdateAvailable));
        builder.setMessage(message.toString());
        builder.setPositiveButton(LocaleController.getString(R.string.ZephyrUpdateButton), (dialog, which) -> startUpdate(activity, release));
        builder.setNegativeButton(LocaleController.getString(R.string.ZephyrUpdateLater), null);
        AlertDialog dialog = builder.create();
        try {
            if (fragment != null) {
                fragment.showDialog(dialog);
            } else {
                dialog.show();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static boolean canInstall(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ApplicationLoader.applicationContext.getPackageManager().canRequestPackageInstalls()) {
            try {
                AlertsCreator.createApkRestrictedDialog(activity, null).show();
            } catch (Exception e) {
                FileLog.e(e);
            }
            return false;
        }
        return true;
    }

    /**
     * External app-specific dir first (readable by the pre-N installer via file://, and covered by
     * the "media"/"external_files" FileProvider paths), internal cacheDir/updates as a fallback.
     */
    private static File updatesDir() {
        File dir = null;
        try {
            dir = ApplicationLoader.applicationContext.getExternalFilesDir("updates");
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (dir == null) {
            dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "updates");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static void startUpdate(Activity activity, Release release) {
        if (downloading || !isUsable(activity) || !canInstall(activity)) {
            return;
        }
        File dir = updatesDir();
        String fileName = "ZephyrGram-" + release.version.replaceAll("[^0-9A-Za-z._-]", "_") + ".apk";
        File target = new File(dir, fileName);
        if (target.exists() && release.apkSize > 0 && target.length() == release.apkSize) {
            install(activity, target);
            return;
        }
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                f.delete();
            }
        }

        downloading = true;
        final boolean[] cancelled = new boolean[1];
        AlertDialog progressDialog = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING);
        progressDialog.setMessage(LocaleController.getString(R.string.ZephyrUpdateDownloading));
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.setCancelable(true);
        progressDialog.setOnCancelListener(d -> cancelled[0] = true);
        try {
            progressDialog.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        WeakReference<Activity> activityRef = new WeakReference<>(activity);

        new Thread(() -> {
            File temp = new File(dir, fileName + ".part");
            boolean success = false;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "ZephyrGram/" + currentVersion() + " (Android)");
                connection.setRequestProperty("Accept", "application/octet-stream");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP " + connection.getResponseCode());
                }
                long total = connection.getContentLength();
                if (total <= 0) {
                    total = release.apkSize;
                }
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    long done = 0;
                    int lastPercent = -1;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancelled[0]) {
                            break;
                        }
                        out.write(buffer, 0, read);
                        done += read;
                        if (total > 0) {
                            int percent = (int) Math.min(100, done * 100 / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                AndroidUtilities.runOnUIThread(() -> progressDialog.setProgress(percent));
                            }
                        }
                    }
                }
                if (!cancelled[0]) {
                    if (release.apkSize > 0 && temp.length() != release.apkSize) {
                        throw new Exception("size mismatch: " + temp.length() + " != " + release.apkSize);
                    }
                    success = temp.renameTo(target);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                if (!success) {
                    temp.delete();
                }
            }
            final boolean finalSuccess = success;
            AndroidUtilities.runOnUIThread(() -> {
                downloading = false;
                try {
                    progressDialog.dismiss();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (cancelled[0]) {
                    return;
                }
                Activity a = activityRef.get();
                if (finalSuccess) {
                    if (isUsable(a)) {
                        install(a, target);
                    }
                } else if (isUsable(a)) {
                    try {
                        new AlertDialog.Builder(a)
                                .setTitle(LocaleController.getString(R.string.ZephyrUpdateAvailable))
                                .setMessage(LocaleController.getString(R.string.ZephyrUpdateDownloadFailed))
                                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                                .show();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            });
        }, "ZephyrUpdateDownload").start();
    }

    private static void install(Activity activity, File apk) {
        if (!canInstall(activity)) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", apk);
            } else {
                uri = Uri.fromFile(apk);
            }
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
