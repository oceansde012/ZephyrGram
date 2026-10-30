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

    private static boolean loaded;

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
}
