"""0.2.8 redesign, step 1: M3 switch (default on), inverted switches, expandable N/M groups. Run: python3 tools/test_redesign_common.py"""
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
MAIN = Path(__file__).resolve().parent.parent / "TMessagesProj/src/main"


def read(path):
    return (MAIN / path).read_text(encoding="utf-8")


class Common(unittest.TestCase):
    def test_m3_switch_default_on(self):
        cfg = read("java/org/telegram/messenger/zephyr/ZephyrConfig.java")
        self.assertIn("newSwitchStyle = true;", cfg)
        self.assertIn('p.getBoolean("newSwitchStyle", true)', cfg)
        sw = read("java/org/telegram/ui/Components/Switch.java")
        self.assertIn("dp(52)", sw)
        self.assertIn("dpf2(32)", sw)
        self.assertIn("dpf2(8 + 4 * progress)", sw)     # thumb 16 -> 24 dp (radius 8 -> 12)

    def test_group_and_inverted(self):
        base = read("java/org/telegram/ui/ZephyrBaseSettingsActivity.java")
        for needle in ("class Group", "class GroupChild", "class Inverted", "addGroup(", "addInvertedSwitch(",
                       "boolean enable = group.on() == 0", "expandedGroups.remove(group.id)"):
            self.assertIn(needle, base)
        # the keys are never renamed or rewritten: an inverted switch flips the stored value
        self.assertIn("ZephyrConfig.setBool(key, stored)", base)
        cell = read("java/org/telegram/ui/Cells/ZephyrWrapCell.java")
        self.assertIn("setCheckMode", cell)
        self.assertIn("boolean standalone", cell)


if __name__ == "__main__":
    unittest.main()
