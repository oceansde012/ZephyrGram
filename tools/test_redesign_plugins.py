import unittest, pathlib
UI = pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/java/org/telegram/ui"
class T(unittest.TestCase):
    def test_cards_enabled(self):
        for f in ("ZephyrPluginsActivity", "ZephyrPluginDetailsActivity"):
            self.assertIn("ZephyrCards.enable(listView, false)", (UI / (f + ".java")).read_text(), f)
    def test_dev_header_and_hint(self):
        s = (UI / "ZephyrPluginsActivity.java").read_text()
        self.assertIn("ZephyrRdPluginsDevHeader", s)
        self.assertIn("ZephyrRdPluginsTapHint", s)
if __name__ == "__main__":
    unittest.main()
