"""0.2.8 redesign, step 2: main screen (tiles, four card groups, diagnostics). Run: python3 tools/test_redesign_main.py"""
from pathlib import Path
import re
import sys
import unittest

sys.dont_write_bytecode = True
MAIN = Path(__file__).resolve().parent.parent / "TMessagesProj/src/main"


def read(path):
    return (MAIN / path).read_text(encoding="utf-8")


class MainScreen(unittest.TestCase):
    def test_layout_order(self):
        s = read("java/org/telegram/ui/ZephyrSettingsActivity.java")
        names = re.findall(r"private static final int (ROW_[A-Z_]+) = (\d+);", s)
        order = [n for n, _ in sorted(names, key=lambda x: int(x[1]))]
        want = ["ROW_TILES", "ROW_TILES_INFO", "ROW_UI_HEADER", "ROW_APPEARANCE", "ROW_CHATS", "ROW_MEDIA",
                "ROW_PRIVACY_HEADER", "ROW_PRIVACY", "ROW_HISTORY", "ROW_FILTERS", "ROW_EXT_HEADER", "ROW_PLUGINS",
                "ROW_AUTOREPLY", "ROW_ASSISTANT", "ROW_SYSTEM_HEADER", "ROW_PUSH", "ROW_UPDATES", "ROW_DIAGNOSTICS",
                "ROW_GAP", "ROW_CREDITS", "ROW_RESET", "ROW_CREDITS_INFO", "ROW_COUNT"]
        self.assertEqual(order, want)
        self.assertIn("ZephyrCards.enable(listView, true)", s)
        self.assertIn("ZephyrConfig.setGhostAll(!ghostOn())", s)
        self.assertIn("key_text_RedRegular", s)

    def test_every_old_destination_still_reachable(self):
        s = read("java/org/telegram/ui/ZephyrSettingsActivity.java")
        for cls in ("ZephyrAppearanceSettingsActivity", "ZephyrChatsSettingsActivity", "ZephyrHistorySettingsActivity",
                    "ZephyrFiltersActivity", "ZephyrPluginsActivity", "ZephyrAutoReplyChatsActivity",
                    "ZephyrAssistantSettingsActivity", "ZephyrPushSettingsActivity", "ZephyrUpdatesSettingsActivity",
                    "ZephyrDiagnosticsActivity", "ZephyrGhostSettingsActivity"):
            self.assertIn("new %s()" % cls, s)

    def test_diagnostics_has_all_log_items(self):
        d = read("java/org/telegram/ui/ZephyrDiagnosticsActivity.java")
        for needle in ("ZephyrEnableLogs", "ZephyrShareLogs", "ZephyrCopyFreezeLog", "ZephyrLogsInfo", "readJournal", "sendLogs"):
            self.assertIn(needle, d)

    def test_strings_in_three_languages(self):
        keys = re.findall(r'name="(ZephyrNav[A-Za-z]+|ZephyrTile[A-Za-z]+|ZephyrDisableAdsTile)"', read("res/values/strings_zephyr_nav.xml"))
        for d in ("values-ru", "values-uk"):
            text = read("res/%s/strings_zephyr_nav.xml" % d)
            for k in ("ZephyrNavMedia", "ZephyrNavPrivacy", "ZephyrNavTilesInfo", "ZephyrTileOnN", "ZephyrDisableAdsTile", "ZephyrNavFiltersState"):
                self.assertIn('name="%s"' % k, text)
        self.assertTrue(keys)


if __name__ == "__main__":
    unittest.main()
