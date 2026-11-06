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
if __name__ == "__main__":
    unittest.main()
