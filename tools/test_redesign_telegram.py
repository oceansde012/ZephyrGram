import unittest, pathlib
UI = pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/java/org/telegram/ui"
SCREENS = """NotificationsSettingsActivity NotificationsCustomSettingsActivity NotificationsSoundActivity DataSettingsActivity
DataAutoDownloadActivity SessionsActivity ArchiveSettingsActivity AudioRecorderSettingsActivity CacheControlActivity LiteModeSettingsActivity
LogoutActivity PasskeysActivity PrivacyControlActivity PrivacyUsersActivity ProxyListActivity QuickRepliesSettingsActivity
RoundVideoSettingsActivity ThemeActivity LanguageSelectActivity RestrictedLanguagesSelectActivity StickersActivity ArchivedStickersActivity
FiltersSetupActivity WallpapersListActivity SaveToGallerySettingsActivity PasscodeActivity""".split()
class T(unittest.TestCase):
    def test_screens_use_cards(self):
        for n in SCREENS:
            s = (UI / (n + ".java")).read_text()
            self.assertIn("ZephyrCards.enable(", s, n)
            self.assertNotRegex(s, r"(?m)^\s*(listView|recyclerListView)\.setSections\(\);", n)
    def test_root_has_cards(self):
        self.assertIn("ZephyrCards.enable(listView, true)", (UI / "SettingsActivity.java").read_text())

class Tonal(unittest.TestCase):
    def test_tonal_zephyr_row(self):
        s = (UI / "SettingsActivity.java").read_text()
        self.assertIn("setTonal(item.id == 27 && org.telegram.messenger.zephyr.ZephyrConfig.settingsCards)", s)
        self.assertIn("dp(tonal ? 40 : 28)", s)

if __name__ == "__main__":
    unittest.main()
