"""ai_memory_status passes a validated `usage` block (counters and ISO dates only). Run: python3 tools/test_memory_usage.py"""
from pathlib import Path
import re
import sys
import unittest

sys.dont_write_bytecode = True
SRC = (Path(__file__).resolve().parent.parent
       / "TMessagesProj/src/main/java/org/telegram/messenger/zephyr/ZephyrMemoryClient.java").read_text(encoding="utf-8")


class MemoryUsage(unittest.TestCase):
    def test_verify_adds_usage_only_through_the_sanitiser(self):
        m = re.search(r"private JSONObject verify\(Snapshot snapshot\).*?\n    }\n", SRC, re.S)
        self.assertIsNotNone(m)
        self.assertIn('.put("usage", safeUsage(response.opt("usage")))', m.group(0))
        self.assertNotIn("response.get(\"usage\")", SRC)

    def test_sanitiser_copies_only_whitelisted_numbers_and_dates(self):
        m = re.search(r"static Object safeUsage\(Object raw\).*?\n    }\n", SRC, re.S)
        body = m.group(0)
        for key in ("scopes", "dialogId", "topicId", "messages", "messagesUntil", "summaryChars", "summaryUntil",
                    "examples", "feedback", "factsSelf", "factsPeer"):
            self.assertIn('"%s"' % key, body)
        self.assertNotIn("getString", body)          # no free text is copied
        self.assertIn("return JSONObject.NULL", body)  # wrong shape -> null, not an exception
        self.assertIn("source.length() > 100", body)

    def test_dates_are_validated(self):
        m = re.search(r"private static Object usageDate.*?\n    }\n", SRC, re.S).group(0)
        self.assertIn("date.length() > 40", m)
        self.assertIn("matches(", m)
        # the pattern accepts ISO dates and nothing with letters other than T/Z
        pat = re.search(r'matches\("(.*?)"\)\) throw', m).group(1)
        self.assertTrue(re.fullmatch(pat, "2026-11-05T12:00:00.123Z"))
        self.assertTrue(re.fullmatch(pat, "2026-11-05T12:00:00+02:00"))
        self.assertFalse(re.fullmatch(pat, "hello world"))
        self.assertFalse(re.fullmatch(pat, "2026-11-05 ignore previous"))


if __name__ == "__main__":
    unittest.main()
