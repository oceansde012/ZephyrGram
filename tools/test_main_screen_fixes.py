"""0.2.9 main-screen fixes: tiles layout, icons, subtitles. Run: python3 tools/test_main_screen_fixes.py"""
import re, unittest, pathlib
R = pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main"
S = (R / "java/org/telegram/ui/ZephyrSettingsActivity.java").read_text()
def res(loc): return (R / "res" / loc / "strings_zephyr_nav.xml").read_text()
class T(unittest.TestCase):
    def test_tiles(self):
        self.assertIn("setMinimumHeight(org.telegram.messenger.AndroidUtilities.dp(116))", S)
        self.assertIn("Gravity.TOP | android.view.Gravity.LEFT", S)
        self.assertIn("BREAK_STRATEGY_SIMPLE", S)
        self.assertIn("HYPHENATION_FREQUENCY_NONE", S)
        self.assertIn("setSingleLine(true)", S)
        self.assertNotIn("Gravity.BOTTOM", S)
        self.assertIn("LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f", S)
    def test_icons(self):
        for d in ("msg_premium_ads", "msg_settings_premium", "msg_forward_replace", "msg_viewintopic"):
            self.assertIn("R.drawable." + d, S)
            self.assertTrue(list((R / "res").glob("drawable*/%s.*" % d)), d)
        for old in ("msg_instant;", "msg_reply_small"):
            self.assertNotIn(old, S)
    def test_history_ghost_labels(self):
        for loc in ("values", "values-ru", "values-uk"):
            x = res(loc)
            for k in ("ZephyrNavHistoryBoth", "ZephyrNavHistoryDeleted", "ZephyrNavHistoryEdited", "ZephyrNavHistoryNone", "ZephyrNavGhostShort"):
                self.assertIn('name="%s"' % k, x, (loc, k))
            self.assertRegex(x, r'ZephyrNavGhostStateOn">[^<]*%1\$d')
        self.assertNotIn("ZephyrNavHistoryState", S)
    def test_uk_ads_short(self):
        self.assertIn(">Ховати рекламу<", res("values-uk"))
if __name__ == "__main__":
    unittest.main()
