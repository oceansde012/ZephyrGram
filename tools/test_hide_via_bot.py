"""Settings > Other > "Hide via @bot" (0.2.7). Run: python3 tools/test_hide_via_bot.py"""
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "TMessagesProj/src/main"


def read(path):
    return (MAIN / path).read_text(encoding="utf-8")


class HideViaBot(unittest.TestCase):
    def test_config_default_off(self):
        s = read("java/org/telegram/messenger/zephyr/ZephyrConfig.java")
        self.assertIn("hideViaBot = false;", s)
        self.assertIn('p.getBoolean("hideViaBot", false)', s)
        self.assertIn('putBoolean("hideViaBot", value)', s)

    def test_cell_clears_label_before_use(self):
        s = read("java/org/telegram/ui/Cells/ChatMessageCell.java")
        flag = s.index("ZephyrConfig.hideViaBot")
        self.assertLess(s.rindex("viaWidth = (int) Math.ceil", 0, flag), flag)
        self.assertLess(flag, s.index("boolean viaBot = (messageObject.messageOwner.fwd_from"))
        self.assertIn("viaUsername = null;", s[flag:flag + 400])

    def test_settings_row_and_strings(self):
        s = read("java/org/telegram/ui/ZephyrOtherSettingsActivity.java")
        self.assertIn("ID_HIDE_VIA_BOT", s)
        self.assertIn("setHideViaBot(value)", s)
        for d in ("values", "values-ru", "values-uk"):
            self.assertIn("ZephyrHideViaBot", read("res/%s/strings_zephyr_ux.xml" % d))

    def test_version(self):
        g = (ROOT / "gradle.properties").read_text()
        self.assertIn("ZEPHYR_VERSION_NAME=0.2.9", g)
        self.assertIn("ZEPHYR_VERSION_CODE=15", g)


if __name__ == "__main__":
    unittest.main()
