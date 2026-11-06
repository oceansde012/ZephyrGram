"""0.2.9: switches and buttons of our screens are wrapping ZephyrWrapCell rows. Run: python3 tools/test_wrap_rows.py"""
import re, unittest, pathlib
R = pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main"
UI = R / "java/org/telegram/ui"
B = (UI / "ZephyrBaseSettingsActivity.java").read_text()
W = (UI / "Cells/ZephyrWrapCell.java").read_text()
class T(unittest.TestCase):
    def test_hook(self):
        self.assertIn("fillItemsInternal", (UI / "Components/UniversalFragment.java").read_text())
        self.assertIn("protected void fillItemsInternal", B)
        self.assertIn("VIEW_TYPE_CHECK", B); self.assertIn("VIEW_TYPE_TEXT", B)
    def test_value_width(self):
        self.assertIn("* 0.45f", W)
        self.assertNotIn("setMaxWidth(AndroidUtilities.dp(170))", W)
    def test_default_mark_stripped(self):
        self.assertIn("умолч|замовч|default", B)
    def test_click_updates_switch(self):
        self.assertGreaterEqual(B.count("setSwitchChecked"), 2)
    def test_bottom_short(self):
        self.assertIn(".shorts(", (UI / "ZephyrChatsSettingsActivity.java").read_text())
        for loc in ("values", "values-ru", "values-uk"):
            self.assertIn("ZephyrRdBottomMuteShort", (R / "res" / loc / "strings_zephyr_redesign.xml").read_text())
    def test_regex_matches_default_marks(self):
        pat = re.compile(r"\s*\((?:[^)]*(?:умолч|замовч|default)[^)]*)\)\s*$")
        for s, want in (("Таблетка (по умолчанию)", "Таблетка"), ("Pill (default)", "Pill"), ("Таблетка (за замовчуванням)", "Таблетка"), ("Круг", "Круг")):
            self.assertEqual(pat.sub("", s), want)
if __name__ == "__main__":
    unittest.main()
