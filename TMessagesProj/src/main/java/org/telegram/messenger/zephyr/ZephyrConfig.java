package org.telegram.messenger.zephyr;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * ZephyrGram settings. Plain SharedPreferences, read through cached volatile fields
 * because the network layer queries them on every outgoing request.
 */
public class ZephyrConfig {

    private static final String PREFS = "zephyrconfig";

    public static volatile boolean ghostNoRead = false;
    public static volatile boolean ghostNoTyping = false;
    public static volatile boolean ghostAlwaysOffline = false;
    public static volatile boolean ghostNoStoryViews = false;
    public static volatile boolean saveDeleted = false;
    public static volatile boolean saveEdited = false;
    public static volatile boolean localPremium = false;
    public static volatile boolean disableAds = false;
    public static volatile boolean autoUpdateCheck = true;
    public static volatile long lastUpdateCheck = 0;

    // --- exteraGram-derived UI/behaviour settings (GPLv2, see Credits). Key names match exteraGram. ---
    public static volatile boolean disableNumberRounding = false;
    public static volatile boolean formatTimeWithSeconds = false;
    public static volatile boolean hidePhoneNumber = false;
    /** 0 = app name, 1 = username, 2 = name. */
    public static volatile int titleText = 0;
    public static volatile boolean centerTitle = false;
    public static volatile boolean tabCounter = true;
    public static volatile boolean disableJumpToNextChannel = false;
    public static volatile boolean hideKeyboardOnScroll = false;
    public static volatile boolean hideShareButton = false;
    /** 0 = 800, 1 = 1280, 2 = 2560. */
    public static volatile int sendPhotosQuality = 1;
    public static volatile boolean hideStickerTime = false;
    public static volatile boolean unlimitedRecentStickers = false;
    /** 0 = 5s, 1 = 10s, 2 = 15s, 3 = 30s. */
    public static volatile int doubleTapSeekDuration = 1;
    public static volatile boolean pauseOnMinimize = false;

    private static boolean loaded;

    private static final String[] EXTERA_BOOL_KEYS = {
            "disableNumberRounding", "formatTimeWithSeconds", "hidePhoneNumber", "centerTitle", "tabCounter",
            "disableJumpToNextChannel", "hideKeyboardOnScroll", "hideShareButton", "hideStickerTime",
            "unlimitedRecentStickers", "pauseOnMinimize"
    };
    private static final String[] EXTERA_INT_KEYS = {"titleText", "sendPhotosQuality", "doubleTapSeekDuration"};

    private static void loadExtera(SharedPreferences p) {
        disableNumberRounding = p.getBoolean("disableNumberRounding", false);
        formatTimeWithSeconds = p.getBoolean("formatTimeWithSeconds", false);
        hidePhoneNumber = p.getBoolean("hidePhoneNumber", false);
        titleText = p.getInt("titleText", 0);
        centerTitle = p.getBoolean("centerTitle", false);
        tabCounter = p.getBoolean("tabCounter", true);
        disableJumpToNextChannel = p.getBoolean("disableJumpToNextChannel", false);
        hideKeyboardOnScroll = p.getBoolean("hideKeyboardOnScroll", false);
        hideShareButton = p.getBoolean("hideShareButton", false);
        sendPhotosQuality = p.getInt("sendPhotosQuality", 1);
        hideStickerTime = p.getBoolean("hideStickerTime", false);
        unlimitedRecentStickers = p.getBoolean("unlimitedRecentStickers", false);
        doubleTapSeekDuration = p.getInt("doubleTapSeekDuration", 1);
        pauseOnMinimize = p.getBoolean("pauseOnMinimize", false);
    }

    public static boolean getBool(String key) {
        switch (key) {
            case "disableNumberRounding": return disableNumberRounding;
            case "formatTimeWithSeconds": return formatTimeWithSeconds;
            case "hidePhoneNumber": return hidePhoneNumber;
            case "centerTitle": return centerTitle;
            case "tabCounter": return tabCounter;
            case "disableJumpToNextChannel": return disableJumpToNextChannel;
            case "hideKeyboardOnScroll": return hideKeyboardOnScroll;
            case "hideShareButton": return hideShareButton;
            case "hideStickerTime": return hideStickerTime;
            case "unlimitedRecentStickers": return unlimitedRecentStickers;
            case "pauseOnMinimize": return pauseOnMinimize;
            default: return false;
        }
    }

    /** Shared write helper: updates the cached field and persists the value. */
    public static void setBool(String key, boolean value) {
        switch (key) {
            case "disableNumberRounding": disableNumberRounding = value; break;
            case "formatTimeWithSeconds": formatTimeWithSeconds = value; break;
            case "hidePhoneNumber": hidePhoneNumber = value; break;
            case "centerTitle": centerTitle = value; break;
            case "tabCounter": tabCounter = value; break;
            case "disableJumpToNextChannel": disableJumpToNextChannel = value; break;
            case "hideKeyboardOnScroll": hideKeyboardOnScroll = value; break;
            case "hideShareButton": hideShareButton = value; break;
            case "hideStickerTime": hideStickerTime = value; break;
            case "unlimitedRecentStickers": unlimitedRecentStickers = value; break;
            case "pauseOnMinimize": pauseOnMinimize = value; break;
            default: return;
        }
        prefs().edit().putBoolean(key, value).apply();
    }

    public static int getInt(String key) {
        switch (key) {
            case "titleText": return titleText;
            case "sendPhotosQuality": return sendPhotosQuality;
            case "doubleTapSeekDuration": return doubleTapSeekDuration;
            default: return 0;
        }
    }

    public static void setInt(String key, int value) {
        switch (key) {
            case "titleText": titleText = value; break;
            case "sendPhotosQuality": sendPhotosQuality = value; break;
            case "doubleTapSeekDuration": doubleTapSeekDuration = value; break;
            default: return;
        }
        prefs().edit().putInt(key, value).apply();
    }

    /** Resets only the exteraGram-derived UI settings; ghost/saved-messages/update settings are kept. */
    public static synchronized void resetUiSettings() {
        SharedPreferences.Editor e = prefs().edit();
        for (String k : EXTERA_BOOL_KEYS) {
            e.remove(k);
        }
        for (String k : EXTERA_INT_KEYS) {
            e.remove(k);
        }
        e.apply();
        loadExtera(prefs());
    }

    public static int sendPhotoSize(boolean highQuality) {
        if (highQuality) {
            return 2560;
        }
        switch (sendPhotosQuality) {
            case 0: return 800;
            case 2: return 2560;
            default: return 1280;
        }
    }

    public static int seekStepMs() {
        switch (doubleTapSeekDuration) {
            case 0: return 5000;
            case 2: return 15000;
            case 3: return 30000;
            default: return 10000;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void load() {
        if (loaded || ApplicationLoader.applicationContext == null) {
            return;
        }
        SharedPreferences p = prefs();
        ghostNoRead = p.getBoolean("ghostNoRead", false);
        ghostNoTyping = p.getBoolean("ghostNoTyping", false);
        ghostAlwaysOffline = p.getBoolean("ghostAlwaysOffline", false);
        ghostNoStoryViews = p.getBoolean("ghostNoStoryViews", false);
        saveDeleted = p.getBoolean("saveDeleted", false);
        saveEdited = p.getBoolean("saveEdited", false);
        localPremium = p.getBoolean("localPremium", false);
        disableAds = p.getBoolean("disableAds", false);
        autoUpdateCheck = p.getBoolean("autoUpdateCheck", true);
        lastUpdateCheck = p.getLong("lastUpdateCheck", 0);
        loadExtera(p);
        loaded = true;
    }

    public static boolean isGhostEnabled() {
        return ghostNoRead || ghostNoTyping || ghostAlwaysOffline || ghostNoStoryViews;
    }

    public static void setGhostNoRead(boolean value) {
        ghostNoRead = value;
        prefs().edit().putBoolean("ghostNoRead", value).apply();
    }

    public static void setGhostNoTyping(boolean value) {
        ghostNoTyping = value;
        prefs().edit().putBoolean("ghostNoTyping", value).apply();
    }

    public static void setGhostAlwaysOffline(boolean value) {
        ghostAlwaysOffline = value;
        prefs().edit().putBoolean("ghostAlwaysOffline", value).apply();
    }

    public static void setGhostNoStoryViews(boolean value) {
        ghostNoStoryViews = value;
        prefs().edit().putBoolean("ghostNoStoryViews", value).apply();
    }

    /** Master switch used by the drawer toggle: turns every ghost option on or off together. */
    public static void setGhostAll(boolean value) {
        ghostNoRead = value;
        ghostNoTyping = value;
        ghostAlwaysOffline = value;
        ghostNoStoryViews = value;
        prefs().edit()
                .putBoolean("ghostNoRead", value)
                .putBoolean("ghostNoTyping", value)
                .putBoolean("ghostAlwaysOffline", value)
                .putBoolean("ghostNoStoryViews", value)
                .apply();
    }

    public static void setSaveDeleted(boolean value) {
        saveDeleted = value;
        prefs().edit().putBoolean("saveDeleted", value).apply();
    }

    public static void setSaveEdited(boolean value) {
        saveEdited = value;
        prefs().edit().putBoolean("saveEdited", value).apply();
    }

    public static void setLocalPremium(boolean value) {
        localPremium = value;
        prefs().edit().putBoolean("localPremium", value).apply();
    }

    public static void setDisableAds(boolean value) {
        disableAds = value;
        prefs().edit().putBoolean("disableAds", value).apply();
    }

    public static void setAutoUpdateCheck(boolean value) {
        autoUpdateCheck = value;
        prefs().edit().putBoolean("autoUpdateCheck", value).apply();
    }

    public static void setLastUpdateCheck(long value) {
        lastUpdateCheck = value;
        prefs().edit().putLong("lastUpdateCheck", value).apply();
    }
}
