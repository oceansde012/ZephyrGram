"""Static wiring checks: plugin CHAT_ACTION / PROFILE_ACTION items appear in the chat and profile header menus."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java/org/telegram'


def read(path):
    return (JAVA / path).read_text(encoding='utf-8')


class ChatActionMenuWiringTests(unittest.TestCase):
    def test_helper_exists_and_is_contained(self):
        src = read('messenger/zephyr/plugins/PluginHeaderMenu.java')
        self.assertIn('public static void sync(', src)
        self.assertIn('public static boolean onClick(', src)
        self.assertIn('PluginMenus.getItems(type, account, dialogId, null, fragment)', src)
        self.assertIn('PluginMenus.isPluginItemId', src)
        self.assertGreaterEqual(src.count('catch (Throwable'), 3)
        self.assertEqual(src.count('{'), src.count('}'))

    def test_chat_activity_renders_and_routes(self):
        src = read('ui/ChatActivity.java')
        self.assertRegex(src, r'PluginHeaderMenu\.sync\(headerItem,\s*org\.telegram\.messenger\.zephyr\.plugins\.PluginMenus\.TYPE_CHAT_ACTION')
        self.assertIn('PluginHeaderMenu.onClick(id, currentAccount, getDialogId(), ChatActivity.this)', src)
        # The existing "Plugins" entry and the message-context wiring stay.
        self.assertIn('headerItem.lazilyAddSubItem(zephyr_plugins', src)
        self.assertIn('PluginMenus.TYPE_MESSAGE_CONTEXT', src)

    def test_profile_activity_renders_and_routes(self):
        src = read('ui/ProfileActivity.java')
        self.assertRegex(src, r'PluginHeaderMenu\.sync\(otherItem,\s*org\.telegram\.messenger\.zephyr\.plugins\.PluginMenus\.TYPE_PROFILE_ACTION')
        self.assertIn('PluginHeaderMenu.onClick(id, currentAccount, getDialogId(), ProfileActivity.this)', src)

    def test_plugin_id_range_does_not_clash(self):
        menus = read('messenger/zephyr/plugins/PluginMenus.java')
        base = int(re.search(r'ID_BASE = (0x[0-9A-Fa-f]+)', menus).group(1), 16)
        for name in ('ui/ChatActivity.java', 'ui/ProfileActivity.java'):
            for value in re.findall(r'static final int \w+ = (\d+);', read(name)) + re.findall(r'final static int \w+ = (\d+);', read(name)):
                self.assertLess(int(value), base, name)


if __name__ == '__main__':
    unittest.main()
