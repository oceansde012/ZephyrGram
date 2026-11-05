"""client.notify (0.2.6): passes the owner's id, texts and account to PluginRuntime.notify and returns its result.
Rate limit, channel and the tap intent live in PluginRuntime.notify (Java). Run: python3 tools/test_plugin_notify.py"""
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_inline_media import JavaSide, PYTHON_ROOT, install_fake_java  # noqa: E402

PLUGIN = '''
from zephyr import BasePlugin, client
__id__ = "notify_test"
__name__ = "Notify test"

class Plugin(BasePlugin):
    def on_plugin_load(self):
        pass
'''


class Notify(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.side = JavaSide()
        install_fake_java(cls.side)
        import java
        cls.runtime = java.jclass("org.telegram.messenger.zephyr.plugins.PluginRuntime").INSTANCE
        cls.shown = True

        def notify(plugin_id, title, text, account):
            cls.side.check(plugin_id, "notify")
            cls.side.calls.append(("notify", plugin_id, title, text, account))
            return cls.shown
        cls.runtime.notify = notify
        sys.path.insert(0, str(PYTHON_ROOT))
        import zephyr_host
        from zephyr import client
        cls.side.host = zephyr_host
        cls.client = client
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.tmp.name) / "notify_test.plugin"
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
        self.side.load("notify_test", self.path, self.tmp.name)

    def tearDown(self):
        self.side.unload("notify_test")

    def call(self, *args, plugin_id="notify_test", **kwargs):
        from zephyr import _state
        token = _state.current.set(_state.plugins["notify_test"])  # inside a callback of the plugin
        try:
            return self.side.with_identity(plugin_id, lambda: self.client.notify(*args, **kwargs))
        finally:
            _state.current.reset(token)

    def test_feature_flag(self):
        self.assertTrue(self.client.NOTIFY)

    def test_passes_owner_texts_and_selected_account(self):
        type(self).shown = True
        self.assertIs(self.call("Question", "Send the reply?"), True)
        self.assertEqual(self.side.calls, [("notify", "notify_test", "Question", "Send the reply?", 0)])

    def test_explicit_account_and_none_texts(self):
        self.call(None, None, account=2)
        self.assertEqual(self.side.calls[-1], ("notify", "notify_test", "", "", 2))

    def test_false_when_java_refuses(self):
        type(self).shown = False
        try:
            self.assertIs(self.call("t", "x"), False)
        finally:
            type(self).shown = True

    def test_other_identity_is_rejected(self):
        with self.assertRaises(Exception):
            self.call("t", "x", plugin_id="someone_else")  # Java sees another caller identity


if __name__ == "__main__":
    unittest.main()
