"""0.2.8 ZephyrCards: one place for the geometry, wired into our base screen and the two Telegram probes,
flag to turn it off. Run: python3 tools/test_zephyr_cards.py"""
from pathlib import Path
import re
import sys
import unittest

sys.dont_write_bytecode = True
MAIN = Path(__file__).resolve().parent.parent / "TMessagesProj/src/main"


def read(path):
    return (MAIN / path).read_text(encoding="utf-8")


class Cards(unittest.TestCase):
    def test_constants(self):
        s = read("java/org/telegram/ui/ZephyrCards.java")
        self.assertIn("MARGIN_DP = 12", s)
        self.assertIn("CARD_RADIUS_DP = 18", s)
        self.assertIn("INNER_RADIUS_DP = 12", s)
        self.assertIn("SPACING_DP = 16", s)
        self.assertNotRegex(s, r"0x[0-9a-fA-F]{6}")                    # colours come from the theme only
        self.assertIn("Theme.key_windowBackgroundWhiteGrayText2", s)

    def test_headers_stay_outside_the_card(self):
        s = read("java/org/telegram/ui/ZephyrCards.java")
        m = re.search(r"private static boolean isCardType.*?\n    }\n", s, re.S).group(0)
        for t in ("VIEW_TYPE_HEADER", "VIEW_TYPE_ANIMATED_HEADER", "VIEW_TYPE_BLACK_HEADER", "isShadow"):
            self.assertIn(t, m)
        self.assertIn("view instanceof HeaderCell", s)

    def test_wiring(self):
        self.assertIn("ZephyrCards.enable(listView, false)", read("java/org/telegram/ui/ZephyrBaseSettingsActivity.java"))
        self.assertIn("ZephyrCards.enable(listView, true)", read("java/org/telegram/ui/SettingsActivity.java"))
        self.assertIn("ZephyrCards.enable(listView, true)", read("java/org/telegram/ui/PrivacySettingsActivity.java"))
        self.assertIn("setApplyBackground(false)", read("java/org/telegram/ui/ZephyrCards.java"))

    def test_flag(self):
        cfg = read("java/org/telegram/messenger/zephyr/ZephyrConfig.java")
        self.assertIn("settingsCards = true", cfg)
        self.assertIn('p.getBoolean("settingsCards", true)', cfg)
        self.assertIn("if (!ZephyrConfig.settingsCards)", read("java/org/telegram/ui/ZephyrCards.java"))
        self.assertIn("ID_SETTINGS_CARDS", read("java/org/telegram/ui/ZephyrOtherSettingsActivity.java"))
        for d in ("values", "values-ru", "values-uk"):
            self.assertIn("ZephyrSettingsCards", read("res/%s/strings_zephyr_ux.xml" % d))

    def test_plugin_cells_share_the_constants(self):
        self.assertIn("ZephyrCards.MARGIN_DP", read("java/org/telegram/ui/Cells/ZephyrWrapCell.java"))


if __name__ == "__main__":
    unittest.main()
