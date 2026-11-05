"""client.get_sticker_sets / client.get_set_stickers (0.2.7): chat.read, normalisation and argument passing.
Cache reads and the one-shot background loads live in PluginChatApi (Java). Run: python3 tools/test_sticker_sets.py"""
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_inline_media import JavaSide, PYTHON_ROOT, install_fake_java  # noqa: E402

PLUGIN = '''
from zephyr import BasePlugin, client
__id__ = "stickers_test"
__name__ = "Stickers test"

class Plugin(BasePlugin):
    def on_plugin_load(self):
        pass
'''


class StickerSets(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.side = JavaSide()
        install_fake_java(cls.side)
        import java
        api = java.jclass("org.telegram.messenger.zephyr.plugins.PluginChatApi")
        cls.sets_json = "[]"
        cls.stickers_json = "[]"

        def get_sticker_sets(plugin_id, account):
            cls.side.require(plugin_id, "chat.read")
            cls.side.calls.append(("getStickerSets", plugin_id, account))
            return cls.sets_json

        def get_set_stickers(plugin_id, set_id, limit, account):
            cls.side.require(plugin_id, "chat.read")
            cls.side.calls.append(("getSetStickers", plugin_id, set_id, limit, account))
            return cls.stickers_json

        api.getStickerSets = get_sticker_sets
        api.getSetStickers = get_set_stickers
        sys.path.insert(0, str(PYTHON_ROOT))
        import zephyr_host
        from zephyr import client
        cls.side.host = zephyr_host
        cls.client = client
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.tmp.name) / "stickers_test.plugin"
        cls.path.write_text(PLUGIN, encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()
        sys.path.remove(str(PYTHON_ROOT))
        for name in list(sys.modules):
            if name in ("java", "zephyr_host") or name == "zephyr" or name.startswith(("zephyr.", "_zephyr_plugin_")):
                sys.modules.pop(name)

    def setUp(self):
        self.side.calls.clear()
        self.side.permissions["stickers_test"] = {"chat.read"}
        type(self).sets_json = "[]"
        type(self).stickers_json = "[]"
        self.side.load("stickers_test", self.path, self.tmp.name)

    def tearDown(self):
        self.side.unload("stickers_test")

    def call(self, fn, *args, **kwargs):
        from zephyr import _state
        token = _state.current.set(_state.plugins["stickers_test"])  # inside a callback of the plugin
        try:
            return self.side.with_identity("stickers_test", lambda: getattr(self.client, fn)(*args, **kwargs))
        finally:
            _state.current.reset(token)

    # Methods below: first draft by Mistral (codestral), checked and corrected.
    def test_flag(self):
        self.assertTrue(self.client.STICKER_SETS)

    def test_permission(self):
        self.side.permissions["stickers_test"] = set()
        with self.assertRaises(PermissionError):
            self.call("get_sticker_sets")
        with self.assertRaises(PermissionError):
            self.call("get_set_stickers", 1)
        self.assertEqual(self.side.calls, [])

    def test_empty_lists(self):
        self.assertEqual(self.call("get_sticker_sets"), [])
        self.assertEqual(self.call("get_set_stickers", 5), [])
        self.assertEqual(self.side.calls, [("getStickerSets", "stickers_test", 0),
                                           ("getSetStickers", "stickers_test", 5, 200, 0)])

    def test_sets_normalisation(self):
        type(self).sets_json = '[{"id": "123", "title": null, "short_name": "cats", "count": null}, {"id": 7}]'
        self.assertEqual(self.call("get_sticker_sets", account=2), [
            {"id": 123, "title": "", "short_name": "cats", "count": 0},
            {"id": 7, "title": "", "short_name": "", "count": 0}])
        self.assertEqual(self.side.calls, [("getStickerSets", "stickers_test", 2)])

    def test_stickers_normalisation(self):
        type(self).stickers_json = ('[{"id": "9", "emoji": null, "set_title": "Cats", "mime": "image/webp", '
                                    '"is_animated": 1}, {"id": 10}]')
        self.assertEqual(self.call("get_set_stickers", "123"), [
            {"id": 9, "emoji": "", "set_title": "Cats", "mime": "image/webp", "is_animated": True},
            {"id": 10, "emoji": "", "set_title": "", "mime": "", "is_animated": False}])

    def test_set_stickers_arguments(self):
        self.call("get_set_stickers", "123", "50", account=1)
        self.assertEqual(self.side.calls, [("getSetStickers", "stickers_test", 123, 50, 1)])


class JavaSideChecks(unittest.TestCase):
    def test_java_api(self):
        root = Path(__file__).resolve().parents[1]
        s = (root / "TMessagesProj/src/main/java/org/telegram/messenger/zephyr/plugins/PluginChatApi.java").read_text("utf-8")
        sets = s[s.index("public static String getStickerSets("):s.index("public static String getSetStickers(")]
        one = s[s.index("public static String getSetStickers("):][:3500]
        for body in (sets, one):
            self.assertIn('requirePermission(id, "chat.read")', body)
            self.assertIn("onUiAndWait(", body)
            self.assertNotIn("notMainThread()", body)  # cache only: safe on any thread
        self.assertIn('stickerLoadDue(account + ":sets")', sets)
        self.assertIn('stickerLoadDue(account + ":set:" + setId)', one)
        self.assertIn("mdc.getStickerSet(input, false)", one)
        self.assertIn("MAX_SET_STICKERS = 200", s)


if __name__ == "__main__":
    unittest.main()
