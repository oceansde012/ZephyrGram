"""0.2.8 settings cards: ui item.parent serialisation (old plugins unchanged) and Java wiring.
Run: python3 tools/test_settings_cards.py"""
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_inline_media import JavaSide, PYTHON_ROOT, install_fake_java  # noqa: E402
sys.path.insert(0, str(PYTHON_ROOT))
install_fake_java(JavaSide())
from zephyr import ui  # noqa: E402

MAIN = Path(__file__).resolve().parent.parent / "TMessagesProj/src/main"


def read(path):
    return (MAIN / path).read_text(encoding="utf-8")


class Cards(unittest.TestCase):
    def test_old_items_have_no_parent_key(self):
        data = ui.Switch("a", "A", default=True, subtext="s")._data("a")
        self.assertNotIn("parent", data)
        self.assertEqual(data["subtext"], "s")

    def test_parent_is_an_attribute_not_a_kwarg(self):
        child = ui.Switch("b", "B")
        child.parent = "a"        # works on any app: old hosts never read it
        self.assertEqual(child._data("b")["parent"], "a")
        with self.assertRaises(TypeError):
            ui.Switch("c", "C", parent="a")   # the constructors are unchanged on purpose

    def test_flag(self):
        self.assertIn("NESTED_SETTINGS = True", read("python/zephyr/client.py"))

    def test_java_wiring(self):
        act = read("java/org/telegram/ui/ZephyrPluginSettingsActivity.java")
        for needle in ("buildEntries()", "ZephyrWrapCell.POSITION_", "wasSwitchTouched()", "expanded.contains(setting.key)",
                       "adapter.update(false)", "row.id=rowId(setting)", "blockItem(cacheKey.hashCode()"):
            if needle == "adapter.update(false)":
                needle = "listView.adapter.update(false)"
            self.assertIn(needle, act)
        cell = read("java/org/telegram/ui/Cells/ZephyrWrapCell.java")
        for needle in ("Theme.key_windowBackgroundWhite", "createRadSelectorDrawable", "addRoundRect", "class TextBlock"):
            self.assertIn(needle, cell)
        self.assertIn("it.parent =", read("java/org/telegram/messenger/zephyr/plugins/PluginSettingsModel.java"))


if __name__ == "__main__":
    unittest.main()
