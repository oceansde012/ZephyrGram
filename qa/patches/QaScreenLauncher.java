package org.telegram.ui;

import android.content.Intent;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/**
 * QA ONLY. Copied into the app by qa/apply_screen_patch.py inside the CI runner for screenshot builds;
 * it is never committed to the app sources and is not part of any release build.
 * adb shell am start -n org.zephyrgram.messenger/org.telegram.ui.LaunchActivity --es qa_screen ZephyrMediaSettingsActivity --es qa_lang ru --es qa_theme dark
 */
public final class QaScreenLauncher {
    private QaScreenLauncher() {}

    public static BaseFragment create(Intent intent) {
        if (intent == null || intent.getStringExtra("qa_screen") == null) {
            return null;
        }
        try {
            String lang = intent.getStringExtra("qa_lang");
            if (lang != null) {
                LocaleController.LocaleInfo info = LocaleController.getInstance().languagesDict.get(lang);
                if (info != null) {
                    LocaleController.getInstance().applyLanguage(info, true, false, UserConfig.selectedAccount);
                }
            }
            String theme = intent.getStringExtra("qa_theme");
            if (theme != null) {
                Theme.ThemeInfo ti = Theme.getTheme("dark".equals(theme) ? "Night" : "Day");
                if (ti != null) {
                    Theme.applyTheme(ti, "dark".equals(theme));
                }
            }
            String name = intent.getStringExtra("qa_screen");
            Class<?> c = Class.forName("org.telegram.ui." + name);
            return (BaseFragment) c.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            FileLog.e(t);
            android.util.Log.e("QaScreenLauncher", "cannot open screen", t);
            return null;
        }
    }
}
