"""Runs the real zephyr_host + zephyr package against a synthetic Java side to check on_inline_result_send
(inline.hook) and client.send_media (media.send): permissions, return values, path handling, HTTPS-only downloads
with size limits. Run: python3 tools/test_inline_media.py (no Android, no network)."""
from pathlib import Path
import io
import json
import queue
import sys
import tempfile
import threading
import types
import unittest
from unittest import mock

sys.dont_write_bytecode = True
PYTHON_ROOT = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python"


class SecurityException(RuntimeError):
    pass


class JavaSide:
    def __init__(self):
        self.local = threading.local()
        self.permissions = {}
        self.calls = []
        self.logs = []
        self.queue = queue.Queue()
        self.host = None
        self.send_media_error = None

    def current(self):
        return getattr(self.local, "id", None)

    def check(self, plugin_id, name):
        if plugin_id is None or plugin_id != self.current():
            raise SecurityException("java.lang.SecurityException: Plugin identity mismatch in " + name)

    def require(self, plugin_id, permission):
        self.check(plugin_id, "requirePermission")
        if permission not in self.permissions.get(plugin_id, ()):
            raise SecurityException("java.lang.SecurityException: Plugin %s needs permission %s" % (plugin_id, permission))

    def with_identity(self, plugin_id, fn, *args):
        previous = self.current()
        self.local.id = plugin_id
        try:
            return fn(*args)
        finally:
            self.local.id = previous

    def load(self, plugin_id, path, data_dir):
        return self.with_identity(plugin_id, self.host.load_plugin, plugin_id, str(path), str(data_dir))

    def unload(self, plugin_id):
        return self.with_identity(plugin_id, self.host.unload_plugin, plugin_id)

    def invoke(self, plugin_id, method, *args):
        return self.with_identity(plugin_id, self.host.invoke, plugin_id, method, list(args))

    def drain(self):
        while not self.queue.empty():
            plugin_id, callback = self.queue.get()
            self.with_identity(plugin_id, self.host.run_callback, plugin_id, callback, [])


def install_fake_java(side):
    def guarded(name):
        def call(plugin_id, *args):
            side.check(plugin_id, name)
            side.calls.append((name, plugin_id) + args)
        return call

    def log(plugin_id, message):
        side.check(plugin_id, "log")
        side.logs.append(message)

    def run_in_background(plugin_id, callback):
        side.check(plugin_id, "runInBackground")
        side.queue.put((plugin_id, callback))

    runtime = types.SimpleNamespace(
        memoryApiVersion=lambda: 1, pickerContextApiVersion=lambda: 1, selectedAccount=lambda: 0,
        appVersion=lambda: "0", appLocale=lambda: "en", context=lambda: None,
        requirePermission=side.require, log=log, reportError=guarded("reportError"),
        runInBackground=run_in_background, runOnUi=guarded("runOnUi"))

    def add_inline_hook(plugin_id, priority):
        side.require(plugin_id, "inline.hook")  # re-checked natively, like PluginHooks.addInlineResultHook
        side.calls.append(("addInlineResultHook", plugin_id, priority))

    def send_media(plugin_id, dialog_id, path, kind, caption, reply_to, account):
        side.require(plugin_id, "media.send")
        if side.send_media_error:
            raise ValueError(side.send_media_error)
        side.calls.append(("sendMedia", plugin_id, dialog_id, path, kind, caption, reply_to, account))
        return kind or "document"

    class Result:
        caption = None

    hooks = types.SimpleNamespace(addInlineResultHook=add_inline_hook,
                                  removeInlineResultHook=guarded("removeInlineResultHook"))
    classes = {
        "org.telegram.messenger.zephyr.plugins.PluginRuntime": types.SimpleNamespace(INSTANCE=runtime),
        "org.telegram.messenger.zephyr.plugins.PluginHooks": hooks,
        "org.telegram.messenger.zephyr.plugins.PluginMenus": types.SimpleNamespace(),
        "org.telegram.messenger.zephyr.plugins.PluginSettingsModel": types.SimpleNamespace(),
        "org.telegram.messenger.zephyr.plugins.PluginHooks$Result": Result,
        "org.telegram.messenger.zephyr.plugins.PluginChatApi": types.SimpleNamespace(sendMedia=send_media),
    }
    java = types.ModuleType("java")
    java.jclass = lambda name: classes[name]
    sys.modules["java"] = java


PLUGIN = '''
from zephyr import BasePlugin, InlineAction, client
__id__ = "%(id)s"
__name__ = "Inline test"

class Plugin(BasePlugin):
    def on_plugin_load(self):
        self.seen = []
        self.done = []
        self.add_inline_result_hook(priority=5)

    def on_inline_result_send(self, account, info):
        self.seen.append((account, info))
        mode = info["title"]
        if mode == "cancel":
            return InlineAction.cancel()
        if mode == "own":
            return InlineAction.send_as_own()
        if mode == "own_caption":
            return InlineAction.send_as_own(caption="mine")
        if mode == "resend":
            client.send_media(info["dialog_id"], info["file_path"], kind="gif", reply_to=info["reply_to"],
                              on_done=lambda ok, error: self.done.append((ok, error)))
            return InlineAction.cancel()
        if mode == "bad":
            return "nope"
        return None
'''

INFO = {"id": "1", "query_id": 9, "type": "gif", "kind": "media", "title": "", "dialog_id": 777, "reply_to": 12,
        "file_path": "/cache/a.mp4", "caption": "bot caption", "can_send_as_own": True}


class FakeResponse(io.BytesIO):
    def __init__(self, body, status=200, headers=None):
        super().__init__(body)
        self.status = status
        self.headers = headers or {}

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


class InlineAndMedia(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.side = JavaSide()
        install_fake_java(cls.side)
        sys.path.insert(0, str(PYTHON_ROOT))
        import zephyr_host
        from zephyr import client
        cls.side.host = zephyr_host
        cls.client = client
        cls.tmp = tempfile.TemporaryDirectory()
        cls.data = Path(cls.tmp.name) / "data"
        cls.data.mkdir()
        cls.path = Path(cls.tmp.name) / "inline_test.plugin"
        cls.path.write_text(PLUGIN % {"id": "inline_test"}, encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()
        sys.path.remove(str(PYTHON_ROOT))
        for name in list(sys.modules):
            if name in ("java", "zephyr_host") or name == "zephyr" or name.startswith(("zephyr.", "_zephyr_plugin_")):
                sys.modules.pop(name)

    def setUp(self):
        self.side.permissions["inline_test"] = {"inline.hook", "media.send", "internet"}
        self.side.calls.clear()
        self.side.logs.clear()
        self.side.send_media_error = None
        self.side.load("inline_test", self.path, self.data)
        from zephyr import _state
        self.plugin = _state.plugins["inline_test"]

    def tearDown(self):
        self.side.unload("inline_test")

    def inline(self, title, **extra):
        info = dict(INFO, title=title, **extra)
        return self.side.invoke("inline_test", "on_inline_result_send", 0, json.dumps(info))

    def test_hook_registration_needs_inline_hook(self):
        self.assertIn(("addInlineResultHook", "inline_test", 5), self.side.calls)
        self.side.unload("inline_test")
        self.side.permissions["inline_test"] = {"media.send"}
        with self.assertRaises(PermissionError):
            self.side.load("inline_test", self.path, self.data)
        self.side.permissions["inline_test"] = {"inline.hook", "media.send", "internet"}
        self.side.load("inline_test", self.path, self.data)

    def test_hook_results(self):
        self.assertIsNone(self.inline("keep"))
        account, info = self.plugin.seen[-1]
        self.assertEqual((account, info["dialog_id"], info["file_path"]), (0, 777, "/cache/a.mp4"))
        self.assertEqual(self.inline("cancel").strategy, 1)
        own = self.inline("own")
        self.assertEqual((own.strategy, own.caption), (2, None))
        own = self.inline("own_caption")
        self.assertEqual((own.strategy, own.caption), (2, "mine"))
        with self.assertRaises(TypeError):
            self.inline("bad")

    def test_cancel_and_resend_local_file(self):
        self.assertEqual(self.inline("resend").strategy, 1)
        self.side.drain()
        self.assertIn(("sendMedia", "inline_test", 777, "/cache/a.mp4", "gif", None, 12, 0), self.side.calls)
        self.assertEqual(self.plugin.done, [(True, None)])

    def run_as_plugin(self, fn, *args, **kwargs):
        return self.side.with_identity("inline_test", lambda: self._in_callback(fn, *args, **kwargs))

    def _in_callback(self, fn, *args, **kwargs):
        from zephyr import _state
        token = _state.current.set(self.plugin)
        try:
            return fn(*args, **kwargs)
        finally:
            _state.current.reset(token)

    def test_send_media_validation(self):
        c = self.client
        with self.assertRaises(ValueError):
            self.run_as_plugin(c.send_media, 1, "http://example.com/a.jpg")
        with self.assertRaises(ValueError):
            self.run_as_plugin(c.send_media, 1, "a.jpg", kind="sticker")
        with self.assertRaises(ValueError):
            self.run_as_plugin(c.send_media, 0, "a.jpg")
        with self.assertRaises(ValueError):
            self.run_as_plugin(c.send_media, 1, "")
        self.side.permissions["inline_test"] = {"media.send"}
        with self.assertRaises(PermissionError):
            self.run_as_plugin(c.send_media, 1, "https://example.com/a.jpg")
        self.side.permissions["inline_test"] = {"internet"}
        with self.assertRaises(PermissionError):
            self.run_as_plugin(c.send_media, 1, "a.jpg")

    def test_relative_path_and_native_error(self):
        results = []
        self.run_as_plugin(self.client.send_media, 5, "out/a.jpg", caption="hi",
                           on_done=lambda ok, error: results.append((ok, error)))
        self.side.drain()
        expected = str(self.data / "out/a.jpg")
        self.assertIn(("sendMedia", "inline_test", 5, expected, None, "hi", 0, 0), self.side.calls)
        self.side.send_media_error = "File is larger than 10 MB for photo"
        self.run_as_plugin(self.client.send_media, 5, "/x/a.jpg", kind="photo")
        self.side.drain()
        self.assertTrue(any("larger than 10 MB" in line for line in self.side.logs))
        self.assertEqual(results, [(True, None)])

    def download(self, body, headers=None, status=200, max_bytes=None):
        results = []
        response = FakeResponse(body, status, headers)
        opener = types.SimpleNamespace(open=lambda request, timeout: response)
        with mock.patch.object(self.client, "_check_public_https") as check, \
                mock.patch.object(self.client.urllib.request, "build_opener", return_value=opener):
            self.run_as_plugin(self.client.send_media, 9, "https://cdn.example/x/clip?sig=1", kind="video",
                               max_bytes=max_bytes, on_done=lambda ok, error: results.append((ok, error)))
            self.side.drain()
            check.assert_called_with("https://cdn.example/x/clip?sig=1")
        return results

    def test_download_is_streamed_into_data_dir(self):
        results = self.download(b"\0\0\0\x20ftypisom" + b"x" * 1000, {"Content-Type": "video/mp4"})
        self.assertEqual(results, [(True, None)])
        call = [c for c in self.side.calls if c[0] == "sendMedia"][-1]
        path = Path(call[3])
        self.assertEqual(path.parent, self.data / "send_media")
        self.assertEqual(path.suffix, ".mp4")
        self.assertEqual(path.read_bytes()[:8], b"\0\0\0\x20ftyp")
        self.assertEqual(call[4], "video")
        self.assertFalse(list((self.data / "send_media").glob("*.part")))

    def test_download_limits(self):
        before = len([c for c in self.side.calls if c[0] == "sendMedia"])
        self.assertFalse(self.download(b"x" * 2048, {"Content-Length": "2048"}, max_bytes=1024)[0][0])
        self.assertFalse(self.download(b"x" * 2048, {}, max_bytes=1024)[0][0])  # no length header: streamed cut-off
        self.assertFalse(self.download(b"", {})[0][0])
        self.assertFalse(self.download(b"x", {}, status=204)[0][0])
        self.assertEqual(before, len([c for c in self.side.calls if c[0] == "sendMedia"]))
        self.assertFalse(list((self.data / "send_media").glob("*.part")))

    def test_private_hosts_rejected(self):
        with self.assertRaises(ValueError):
            self.client._check_public_https("https://127.0.0.1/a.jpg")


if __name__ == "__main__":
    unittest.main(verbosity=2)
