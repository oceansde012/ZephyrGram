"""Static checks for the plugin chat header button (MenuType.CHAT_HEADER_BUTTON)."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
PY = ROOT / "TMessagesProj/src/main/python/zephyr"


def read(path):
    return path.read_text(encoding="utf-8")


class HeaderButton(unittest.TestCase):
    def test_type_matches_on_both_sides(self):
        self.assertIn("CHAT_HEADER_BUTTON = 4", read(PY / "__init__.py"))
        self.assertIn("public static final int TYPE_CHAT_HEADER_BUTTON = 4;",
                      read(JAVA / "messenger/zephyr/plugins/PluginMenus.java"))
        self.assertIn("HEADER_BUTTON = True", read(PY / "client.py"))

    def test_missing_permission_is_logged_not_raised(self):
        s = read(PY / "__init__.py")
        m = re.search(r"def add_menu_item\(self, item\):(.*?)Menus\.add\(", s, re.S)
        self.assertIsNotNone(m)
        body = m.group(1)
        self.assertIn("MenuType.CHAT_HEADER_BUTTON", body)
        self.assertIn("except PermissionError", body)
        self.assertIn("self.log(", body)
        self.assertIn("return None", body)

    def test_calls_are_contained(self):
        s = read(JAVA / "messenger/zephyr/plugins/PluginHeaderButton.java")
        for name in ("pick", "apply", "onClick"):
            m = re.search(r"public static \w+(?:\.\w+)? " + name + r"\(.*?\n    \}\n", s, re.S)
            self.assertIsNotNone(m, name)
            self.assertIn("catch (Throwable", m.group(0), name)

    def test_chat_activity_wiring(self):
        s = read(JAVA / "ui/ChatActivity.java")
        self.assertIn("menu.addItem(zephyr_plugin_header_button, R.drawable.ai_chat, themeDelegate)", s)
        self.assertIn("PluginHeaderButton.onClick(pluginHeaderButtonItemId", s)
        self.assertIn("pluginHeaderButton != null ? pluginHeaderButtonItemId : -1", s)
        self.assertIn("PluginMenus.removeListener(pluginMenusListener)", s)
        resume = s[s.index("public void onResume() {"):][:300]
        self.assertIn("updatePluginHeaderButton();", resume)
        create = s[s.index("pluginHeaderButton = menu.addItem"):][:10]
        cond = s[:s.index("pluginHeaderButton = menu.addItem")].rsplit("if (", 1)[1]
        self.assertIn("currentEncryptedChat == null", cond)
        self.assertIn("!isReport()", cond)
        self.assertIn("chatMode == 0", cond)

    def test_overflow_into_three_dots(self):
        s = read(JAVA / "messenger/zephyr/plugins/PluginHeaderMenu.java")
        self.assertIn("int shownHeaderButtonId", s)
        self.assertIn("b.id != shownHeaderButtonId", s)

    def test_stock_icon_exists(self):
        self.assertTrue((ROOT / "TMessagesProj/src/main/res/drawable/ai_chat.xml").is_file())


if __name__ == "__main__":
    unittest.main()
