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
        self.assertGreaterEqual(B.count("setRowChecked(view"), 2)
        self.assertIn("setSwitchChecked(checked)", B)
    def test_bottom_short(self):
        self.assertIn(".shorts(", (UI / "ZephyrChatsSettingsActivity.java").read_text())
        for loc in ("values", "values-ru", "values-uk"):
            self.assertIn("ZephyrRdBottomMuteShort", (R / "res" / loc / "strings_zephyr_redesign.xml").read_text())
    def test_regex_matches_default_marks(self):
        pat = re.compile(r"\s*\((?:[^)]*(?:умолч|замовч|default)[^)]*)\)\s*$")
        for s, want in (("Таблетка (по умолчанию)", "Таблетка"), ("Pill (default)", "Pill"), ("Таблетка (за замовчуванням)", "Таблетка"), ("Круг", "Круг")):
            self.assertEqual(pat.sub("", s), want)

class Main(unittest.TestCase):
    def test_main_rows_wrap(self):
        s = (UI / "ZephyrSettingsActivity.java").read_text()
        self.assertIn("new org.telegram.ui.Cells.ZephyrWrapCell(context, false)", s)
        self.assertNotIn("new TextDetailSettingsCell", s)
    def test_history_button(self):
        s = (UI / "ZephyrHistorySettingsActivity.java").read_text()
        self.assertIn("ZephyrRdClearSaved", s)
        self.assertNotIn("ZephyrHistoryClear), historyStats == null", s)


class Colors(unittest.TestCase):
    def test_bind_reapplies_colors(self):
        i = W.index("public void bind(")
        self.assertIn("applyColors();", W[i:i + 400])


class Helper(unittest.TestCase):
    def test_set_row_checked_everywhere(self):
        self.assertIn("static void setRowChecked(View view, boolean checked)", B)
        for n in ("Push", "Updates", "History", "Appearance", "General", "Ghost"):
            s = (UI / ("Zephyr%sSettingsActivity.java" % n)).read_text()
            self.assertIn("setRowChecked(view,", s, n)
            self.assertNotRegex(s, r"\(\(TextCheckCell\) view\)\.setChecked", n)
        self.assertIn("setRowChecked(view,", (UI / "ZephyrDiagnosticsActivity.java").read_text())


class NoDuplicateMeasure(unittest.TestCase):
    def test_single_outer_onMeasure(self):
        # the outer class has exactly one onMeasure (4-space indent); the nested TextBlock has its own at 8 spaces
        self.assertEqual(len(re.findall(r"(?m)^    protected void onMeasure\(", W)), 1)
        self.assertEqual(W.count("valueView.setMaxWidth("), 1)
        self.assertIn("MeasureSpec.UNSPECIFIED", W)

if __name__ == "__main__":
    unittest.main()
