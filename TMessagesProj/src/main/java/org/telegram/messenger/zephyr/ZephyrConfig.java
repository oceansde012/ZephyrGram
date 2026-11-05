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

    // --- Batch 2 "variations" (exteraGram-derived, GPLv2) ---
    /** Avatar corner radius, percent of stock: 0 = square, 100 = stock (circle). */
    public static volatile int avatarCorners = 100;
    /** 0 = pill (stock), 1 = rounded square, 2 = underline, 3 = no indicator, 4 = filled pill. */
    public static volatile int tabStyle = 0;
    /** 0 = circle (stock), 1 = rounded square. */
    public static volatile int fabShape = 0;
    /** 0 = off, 1 = reaction (stock), 2 = reply, 3 = copy, 4 = forward, 5 = edit, 6 = delete. */
    public static volatile int doubleTapAction = 1;
    public static volatile int doubleTapActionOutOwner = 1;
    /** Sticker size, percent of stock. */
    public static volatile int stickerSize = 100;
    /** 0 = by date (stock), 1 = always New Year, 2 = never. */
    public static volatile int holidayMode = 0;
    public static volatile boolean forceSnow = false;
    public static volatile boolean forceBlur = false;

    private static boolean loaded;

    private static final String[] EXTERA_BOOL_KEYS = {
            "disableNumberRounding", "formatTimeWithSeconds", "hidePhoneNumber", "centerTitle", "tabCounter",
            "disableJumpToNextChannel", "hideKeyboardOnScroll", "hideShareButton", "hideStickerTime",
            "unlimitedRecentStickers", "pauseOnMinimize", "forceSnow", "forceBlur"
    };
    private static final String[] EXTERA_INT_KEYS = {"titleText", "sendPhotosQuality", "doubleTapSeekDuration",
            "avatarCorners", "tabStyle", "fabShape", "doubleTapAction", "doubleTapActionOutOwner", "stickerSize", "holidayMode"};

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
        forceSnow = p.getBoolean("forceSnow", false);
        forceBlur = p.getBoolean("forceBlur", false);
        avatarCorners = p.getInt("avatarCorners", 100);
        tabStyle = p.getInt("tabStyle", 0);
        fabShape = p.getInt("fabShape", 0);
        doubleTapAction = p.getInt("doubleTapAction", 1);
        doubleTapActionOutOwner = p.getInt("doubleTapActionOutOwner", 1);
        stickerSize = p.getInt("stickerSize", 100);
        holidayMode = p.getInt("holidayMode", 0);
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
            case "forceSnow": return forceSnow;
            case "forceBlur": return forceBlur;
            default: return getChat3Bool(key);
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
            case "forceSnow": forceSnow = value; break;
            case "forceBlur": forceBlur = value; break;
            default:
                if (!setChat3Bool(key, value)) return;
                break;
        }
        prefs().edit().putBoolean(key, value).apply();
    }

    public static int getInt(String key) {
        switch (key) {
            case "titleText": return titleText;
            case "sendPhotosQuality": return sendPhotosQuality;
            case "doubleTapSeekDuration": return doubleTapSeekDuration;
            case "avatarCorners": return avatarCorners;
            case "tabStyle": return tabStyle;
            case "fabShape": return fabShape;
            case "doubleTapAction": return doubleTapAction;
            case "doubleTapActionOutOwner": return doubleTapActionOutOwner;
            case "stickerSize": return stickerSize;
            case "holidayMode": return holidayMode;
            default: return getChat3Int(key);
        }
    }

    public static void setInt(String key, int value) {
        switch (key) {
            case "titleText": titleText = value; break;
            case "sendPhotosQuality": sendPhotosQuality = value; break;
            case "doubleTapSeekDuration": doubleTapSeekDuration = value; break;
            case "avatarCorners": avatarCorners = value; break;
            case "tabStyle": tabStyle = value; break;
            case "fabShape": fabShape = value; break;
            case "doubleTapAction": doubleTapAction = value; break;
            case "doubleTapActionOutOwner": doubleTapActionOutOwner = value; break;
            case "stickerSize": stickerSize = value; break;
            case "holidayMode": holidayMode = value; break;
            default:
                if (!setChat3Int(key, value)) return;
                break;
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
        removeChat3(e);
        removeBatch4(e);
        e.apply();
        loadExtera(prefs());
        loadChat3(prefs());
        loadBatch4(prefs());
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

    // --- Batch 3: chat behaviour and menus (ported from exteraGram, GPLv2; modified for ZephyrGram). ---
    public static volatile boolean showForwardNoAuthor = true;
    public static volatile boolean showSaveMessage = true;
    public static volatile boolean showCopyPhoto = true;
    public static volatile boolean showMessageDetails = false;
    public static volatile boolean permissionsShortcut = false;
    public static volatile boolean administratorsShortcut = false;
    public static volatile boolean membersShortcut = false;
    public static volatile boolean recentActionsShortcut = false;
    public static volatile boolean showActionTimestamps = false;
    public static volatile boolean addCommaAfterMention = false;
    public static volatile boolean hideCategories = false;
    public static volatile boolean hideCameraTile = false;
    public static volatile boolean staticZoom = false;
    /** 0 = mute/unmute (default), 1 = discuss (open the linked group). */
    public static volatile int bottomButton = 0;
    /** 0 = default, 1 = front, 2 = rear, 3 = last used. */
    public static volatile int videoMessagesCamera = 0;
    public static volatile boolean roundLastFront = true;

    private static final String[] CHAT3_BOOL_KEYS = {
            "showForwardNoAuthor", "showSaveMessage", "showCopyPhoto", "showMessageDetails", "permissionsShortcut",
            "administratorsShortcut", "membersShortcut", "recentActionsShortcut", "showActionTimestamps",
            "addCommaAfterMention", "hideCategories", "hideCameraTile", "staticZoom"
    };
    private static final String[] CHAT3_INT_KEYS = {"bottomButton", "videoMessagesCamera"};

    private static void loadChat3(SharedPreferences p) {
        showForwardNoAuthor = p.getBoolean("showForwardNoAuthor", true);
        showSaveMessage = p.getBoolean("showSaveMessage", true);
        showCopyPhoto = p.getBoolean("showCopyPhoto", true);
        showMessageDetails = p.getBoolean("showMessageDetails", false);
        permissionsShortcut = p.getBoolean("permissionsShortcut", false);
        administratorsShortcut = p.getBoolean("administratorsShortcut", false);
        membersShortcut = p.getBoolean("membersShortcut", false);
        recentActionsShortcut = p.getBoolean("recentActionsShortcut", false);
        showActionTimestamps = p.getBoolean("showActionTimestamps", false);
        addCommaAfterMention = p.getBoolean("addCommaAfterMention", false);
        hideCategories = p.getBoolean("hideCategories", false);
        hideCameraTile = p.getBoolean("hideCameraTile", false);
        staticZoom = p.getBoolean("staticZoom", false);
        bottomButton = p.getInt("bottomButton", 0);
        videoMessagesCamera = p.getInt("videoMessagesCamera", 0);
        roundLastFront = p.getBoolean("roundLastFront", true);
    }

    private static void removeChat3(SharedPreferences.Editor e) {
        for (String k : CHAT3_BOOL_KEYS) {
            e.remove(k);
        }
        for (String k : CHAT3_INT_KEYS) {
            e.remove(k);
        }
    }

    private static boolean getChat3Bool(String key) {
        switch (key) {
            case "showForwardNoAuthor": return showForwardNoAuthor;
            case "showSaveMessage": return showSaveMessage;
            case "showCopyPhoto": return showCopyPhoto;
            case "showMessageDetails": return showMessageDetails;
            case "permissionsShortcut": return permissionsShortcut;
            case "administratorsShortcut": return administratorsShortcut;
            case "membersShortcut": return membersShortcut;
            case "recentActionsShortcut": return recentActionsShortcut;
            case "showActionTimestamps": return showActionTimestamps;
            case "addCommaAfterMention": return addCommaAfterMention;
            case "hideCategories": return hideCategories;
            case "hideCameraTile": return hideCameraTile;
            case "staticZoom": return staticZoom;
            default: return getBatch4Bool(key);
        }
    }

    private static boolean setChat3Bool(String key, boolean value) {
        switch (key) {
            case "showForwardNoAuthor": showForwardNoAuthor = value; return true;
            case "showSaveMessage": showSaveMessage = value; return true;
            case "showCopyPhoto": showCopyPhoto = value; return true;
            case "showMessageDetails": showMessageDetails = value; return true;
            case "permissionsShortcut": permissionsShortcut = value; return true;
            case "administratorsShortcut": administratorsShortcut = value; return true;
            case "membersShortcut": membersShortcut = value; return true;
            case "recentActionsShortcut": recentActionsShortcut = value; return true;
            case "showActionTimestamps": showActionTimestamps = value; return true;
            case "addCommaAfterMention": addCommaAfterMention = value; return true;
            case "hideCategories": hideCategories = value; return true;
            case "hideCameraTile": hideCameraTile = value; return true;
            case "staticZoom": staticZoom = value; return true;
            default: return setBatch4Bool(key, value);
        }
    }

    private static int getChat3Int(String key) {
        switch (key) {
            case "bottomButton": return bottomButton;
            case "videoMessagesCamera": return videoMessagesCamera;
            default: return getBatch4Int(key);
        }
    }

    private static boolean setChat3Int(String key, int value) {
        switch (key) {
            case "bottomButton": bottomButton = value; return true;
            case "videoMessagesCamera": videoMessagesCamera = value; return true;
            default: return setBatch4Int(key, value);
        }
    }

    public static void setRoundLastFront(boolean front) {
        roundLastFront = front;
        prefs().edit().putBoolean("roundLastFront", front).apply();
    }

    /** Initial facing of the round-video camera: returns {@code current} for the default mode. */
    public static boolean roundCameraFront(boolean current) {
        switch (videoMessagesCamera) {
            case 1: return true;
            case 2: return false;
            case 3: return roundLastFront;
            default: return current;
        }
    }

    // --- Batch 4: profile / archive / system (ported from exteraGram, GPLv2; modified for ZephyrGram). ---
    public static volatile boolean archiveOnPull = false;
    public static volatile boolean disableUnarchiveSwipe = false;
    public static volatile boolean hideActionBarStatus = false;
    public static volatile boolean useSystemFonts = false;
    public static volatile boolean newSwitchStyle = false;
    public static volatile boolean disableDividers = false;
    /** 0 = hide, 1 = ID, 2 = ID + DC. */
    public static volatile int showIdAndDc = 0;
    /** 0 = auto, 1 = force tablet layout, 2 = force phone layout. Applied after restart. */
    public static volatile int tabletMode = 0;

    private static final String[] BATCH4_BOOL_KEYS = {"archiveOnPull", "disableUnarchiveSwipe", "hideActionBarStatus",
            "useSystemFonts", "newSwitchStyle", "disableDividers"};
    private static final String[] BATCH4_INT_KEYS = {"showIdAndDc", "tabletMode"};

    private static void loadBatch4(SharedPreferences p) {
        archiveOnPull = p.getBoolean("archiveOnPull", false);
        disableUnarchiveSwipe = p.getBoolean("disableUnarchiveSwipe", false);
        hideActionBarStatus = p.getBoolean("hideActionBarStatus", false);
        useSystemFonts = p.getBoolean("useSystemFonts", false);
        newSwitchStyle = p.getBoolean("newSwitchStyle", false);
        disableDividers = p.getBoolean("disableDividers", false);
        showIdAndDc = p.getInt("showIdAndDc", 0);
        tabletMode = p.getInt("tabletMode", 0);
    }

    private static void removeBatch4(SharedPreferences.Editor e) {
        for (String k : BATCH4_BOOL_KEYS) {
            e.remove(k);
        }
        for (String k : BATCH4_INT_KEYS) {
            e.remove(k);
        }
    }

    private static boolean getBatch4Bool(String key) {
        switch (key) {
            case "archiveOnPull": return archiveOnPull;
            case "disableUnarchiveSwipe": return disableUnarchiveSwipe;
            case "hideActionBarStatus": return hideActionBarStatus;
            case "useSystemFonts": return useSystemFonts;
            case "newSwitchStyle": return newSwitchStyle;
            case "disableDividers": return disableDividers;
            default: return false;
        }
    }

    private static boolean setBatch4Bool(String key, boolean value) {
        switch (key) {
            case "archiveOnPull": archiveOnPull = value; return true;
            case "disableUnarchiveSwipe": disableUnarchiveSwipe = value; return true;
            case "hideActionBarStatus": hideActionBarStatus = value; return true;
            case "useSystemFonts": useSystemFonts = value; return true;
            case "newSwitchStyle": newSwitchStyle = value; return true;
            case "disableDividers": disableDividers = value; return true;
            default: return false;
        }
    }

    private static int getBatch4Int(String key) {
        switch (key) {
            case "showIdAndDc": return showIdAndDc;
            case "tabletMode": return tabletMode;
            default: return 0;
        }
    }

    private static boolean setBatch4Int(String key, int value) {
        switch (key) {
            case "showIdAndDc": showIdAndDc = value; return true;
            case "tabletMode": tabletMode = value; return true;
            default: return false;
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
        loadChat3(p);
        loadBatch4(p);
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

    // --- AI assistant (ZephyrGramAI server client). Stored in a separate private prefs file; never log the token. ---
    private static final String AI_PREFS = "zephyrai";
    private static boolean aiLoaded;
    private static String aiServerUrl = "";
    private static String aiToken = "";
    private static String aiModel = "";
    private static String aiSystemPrompt = "";
    private static String aiBotUsername = "";
    private static int aiTemperaturePct = 70;
    /** Owner-only admin secret for minting access tokens. Never logged, never exposed to plugins. */
    private static String aiAdminSecret = "";

    private static SharedPreferences aiPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized void aiLoad() {
        if (aiLoaded || ApplicationLoader.applicationContext == null) {
            return;
        }
        SharedPreferences p = aiPrefs();
        aiServerUrl = p.getString("serverUrl", "");
        aiToken = p.getString("token", "");
        aiModel = p.getString("model", "");
        aiSystemPrompt = p.getString("systemPrompt", "");
        aiBotUsername = p.getString("botUsername", "");
        aiTemperaturePct = p.getInt("temperaturePct", 70);
        aiAdminSecret = p.getString("adminSecret", "");
        aiLoaded = true;
    }

    public static synchronized String getAiServerUrl() { aiLoad(); return aiServerUrl; }
    public static synchronized String getAiToken() { aiLoad(); return aiToken; }
    public static synchronized String getAiAdminSecret() { aiLoad(); return aiAdminSecret; }
    public static synchronized String getAiModel() { aiLoad(); return aiModel; }
    public static synchronized String getAiSystemPrompt() { aiLoad(); return aiSystemPrompt; }
    /** Bot username without '@'; the owner sets it. Empty by default. */
    public static synchronized String getAiBotUsername() { aiLoad(); return aiBotUsername; }
    /** Temperature in percent: 70 = 0.7. */
    public static synchronized int getAiTemperaturePct() { aiLoad(); return aiTemperaturePct; }

    public static synchronized void setAiString(String key, String value) {
        aiLoad();
        value = value == null ? "" : value.trim();
        switch (key) {
            case "serverUrl": aiServerUrl = value; break;
            case "token": aiToken = value; break;
            case "adminSecret": aiAdminSecret = value; break;
            case "model": aiModel = value; break;
            case "systemPrompt": aiSystemPrompt = value; break;
            case "botUsername": aiBotUsername = value.startsWith("@") ? value.substring(1) : value; value = aiBotUsername; break;
            default: return;
        }
        aiPrefs().edit().putString(key, value).apply();
    }

    public static synchronized void setAiTemperaturePct(int value) {
        aiLoad();
        aiTemperaturePct = Math.max(0, Math.min(200, value));
        aiPrefs().edit().putInt("temperaturePct", aiTemperaturePct).apply();
    }

    public static boolean isAiConfigured() {
        return !getAiServerUrl().isEmpty() && !getAiToken().isEmpty();
    }
}
