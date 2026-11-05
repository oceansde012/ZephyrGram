import re, unittest, pathlib
R = pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main"
UI = R / "java/org/telegram/ui"

# every key that the old Chats/General/Appearance/History/Ghost screens exposed must still be reachable
KEYS = """disableJumpToNextChannel hideKeyboardOnScroll hideShareButton hideStickerTime unlimitedRecentStickers pauseOnMinimize
stickerSize doubleTapAction doubleTapActionOutOwner sendPhotosQuality doubleTapSeekDuration showForwardNoAuthor showSaveMessage
showCopyPhoto showMessageDetails permissionsShortcut administratorsShortcut membersShortcut recentActionsShortcut bottomButton
showActionTimestamps addCommaAfterMention hideCategories hideCameraTile videoMessagesCamera staticZoom disableNumberRounding
formatTimeWithSeconds hidePhoneNumber showIdAndDc archiveOnPull disableUnarchiveSwipe useSystemFonts newSwitchStyle disableDividers
hideActionBarStatus tabletMode titleText centerTitle tabCounter avatarCorners tabStyle fabShape holidayMode forceSnow forceBlur
saveDeleted saveEdited ghostNoRead ghostNoTyping ghostAlwaysOffline ghostNoStoryViews hideViaBot settingsCards""".split()

class T(unittest.TestCase):
    def test_all_keys_reachable(self):
        src = "".join((UI / f).read_text() for f in (
            "ZephyrChatsSettingsActivity.java", "ZephyrMediaSettingsActivity.java",
            "ZephyrAppearanceSettingsActivity.java", "ZephyrPrivacySettingsActivity.java"))
        for k in KEYS:
            self.assertIn('"%s"' % k, src, k)

    def test_strings_in_three_locales(self):
        used = set()
        for f in ("ZephyrChatsSettingsActivity", "ZephyrMediaSettingsActivity", "ZephyrAppearanceSettingsActivity", "ZephyrPrivacySettingsActivity"):
            used |= set(re.findall(r"R\.string\.(ZephyrRd\w+)", (UI / (f + ".java")).read_text()))
        for loc in ("values", "values-ru", "values-uk"):
            x = (R / "res" / loc / "strings_zephyr_redesign.xml").read_text()
            for name in used:
                self.assertIn('name="%s"' % name, x, (loc, name))

    def test_config_bool_hooks(self):
        s = (R / "java/org/telegram/messenger/zephyr/ZephyrConfig.java").read_text()
        for k in ("hideViaBot", "settingsCards"):
            self.assertIn('case "%s": return' % k, s)
            self.assertIn('case "%s": set' % k, s)

    def test_privacy_subtitle_real_value(self):
        s = (UI / "ZephyrSettingsActivity.java").read_text()
        self.assertIn("ZephyrConfig.showIdAndDc", s)

if __name__ == "__main__":
    unittest.main()
