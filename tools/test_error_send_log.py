"""Plugin error dialog has a "Send log" button (0.2.7). Run: python3 tools/test_error_send_log.py"""
from pathlib import Path
import re
import sys
import unittest

sys.dont_write_bytecode = True
MAIN = Path(__file__).resolve().parent.parent / "TMessagesProj/src/main"


class ErrorSendLog(unittest.TestCase):
    def test_button(self):
        s = (MAIN / "java/org/telegram/ui/ZephyrPluginsActivity.java").read_text(encoding="utf-8")
        m = re.search(r"static void error\(.*?create\(\)\);", s, re.S)
        self.assertIsNotNone(m)
        self.assertIn("setNeutralButton(s(R.string.ZephyrPluginErrorSendLog)", m.group(0))
        self.assertIn("ProfileActivity.sendLogs(activity, false)", m.group(0))

    def test_strings(self):
        for d in ("values", "values-ru", "values-uk"):
            self.assertIn("ZephyrPluginErrorSendLog", (MAIN / ("res/%s/strings_zephyr_plugins2.xml" % d)).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
