"""ui.FilePicker (0.2.7): item serialisation, host routing of the "files" event, Java wiring.
Run: python3 tools/test_file_picker.py"""
from pathlib import Path
import json
import re
import sys
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "TMessagesProj/src/main"
sys.path.insert(0, str(MAIN / "python"))
from zephyr import ui  # noqa: E402


def read(path):
    return (MAIN / path).read_text(encoding="utf-8")


class FilePicker(unittest.TestCase):
    def test_serialised(self):
        item = ui.FilePicker("pics", "Pick", lambda paths: None, subtext="s", kind="IMAGE", multiple=True)
        data = item._data("pics")
        self.assertEqual((data["type"], data["kind"], data["multiple"]), (9, "image", True))

    def test_bad_arguments(self):
        with self.assertRaises(ValueError):
            ui.FilePicker("a", "b", None)
        with self.assertRaises(ValueError):
            ui.FilePicker("a", "b", lambda p: None, kind="exe")

    def test_host_routes_files_event(self):
        host = read("python/zephyr_host.py")
        m = re.search(r'if event == "files":(.*?)callback = getattr\(item, "on_change"', host, re.S)
        self.assertIsNotNone(m)
        self.assertIn("on_pick", m.group(1))
        self.assertIn("isinstance(path, str)", m.group(1))

    def test_java_wiring(self):
        model = read("java/org/telegram/messenger/zephyr/plugins/PluginSettingsModel.java")
        self.assertIn("TYPE_FILE_PICKER = 9", model)
        self.assertIn('"files"', model)
        act = read("java/org/telegram/ui/ZephyrPluginSettingsActivity.java")
        self.assertIn("case PluginSettingsModel.TYPE_FILE_PICKER: openFilePicker(setting)", act)
        self.assertIn("ACTION_OPEN_DOCUMENT", act)
        self.assertIn('"picked"', act)
        # names are sanitised: no path separator can survive
        self.assertIn('replaceAll("[^\\\\p{L}\\\\p{N}._-]+","_")', act)
        self.assertIn("MAX_PICKED_BYTES", act)

    def test_flag_and_strings(self):
        self.assertIn("FILE_PICKER = True", read("python/zephyr/client.py"))
        for d in ("values", "values-ru", "values-uk"):
            self.assertIn("ZephyrPluginPickedTooBig", read("res/%s/strings_zephyr_plugins2.xml" % d))


if __name__ == "__main__":
    unittest.main()
