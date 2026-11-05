"""Runs the real zephyr_host + zephyr package against a synthetic Java side that enforces the same per-thread
current-plugin rule as PluginHooks.requireCaller, so a callback path that loses its identity fails here.
Run: python3 tools/test_plugin_identity.py (no Android, no network)."""
from pathlib import Path
import queue
import sys
import tempfile
import threading
import types
import unittest

PYTHON_ROOT = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python"


class SecurityException(RuntimeError):
    pass


class JavaSide:
    """Mirrors PluginRuntime: identity is set per thread around load/unload/invoke/run_callback only."""

    def __init__(self):
        self.local = threading.local()
        self.calls = []
        self.queue = queue.Queue()
        self.host = None

    def current(self):
        return getattr(self.local, "id", None)

    def check(self, plugin_id, name):
        if plugin_id is None or plugin_id != self.current():
            raise SecurityException("java.lang.SecurityException: Plugin identity mismatch in " + name)
        self.calls.append((name, plugin_id))

    def with_identity(self, plugin_id, fn, *args):
        previous = self.current()
        self.local.id = plugin_id
        try:
            return fn(*args)
        finally:
            self.local.id = previous

    def load(self, plugin_id, path):
        return self.with_identity(plugin_id, self.host.load_plugin, plugin_id, str(path), tempfile.gettempdir())

    def unload(self, plugin_id):
        return self.with_identity(plugin_id, self.host.unload_plugin, plugin_id)

    def invoke(self, plugin_id, method, *args):
        return self.with_identity(plugin_id, self.host.invoke, plugin_id, method, list(args))

    def drain(self):
        """Runs queued run_on_ui / run_in_bg / sendRequest callbacks on fresh threads, like the Java executors."""
        while not self.queue.empty():
            plugin_id, callback, args = self.queue.get()
            errors = []

            def run():
                try:
                    self.with_identity(plugin_id, self.host.run_callback, plugin_id, callback, list(args))
                except BaseException as exc:  # noqa: BLE001 - reported to the test
                    errors.append(exc)
            worker = threading.Thread(target=run)
            worker.start()
            worker.join()
            if errors:
                raise errors[0]


def install_fake_java(side):
    settings = {}

    def guarded(name, result=None):
        def call(plugin_id, *args):
            side.check(plugin_id, name)
            return result
        return call

    runtime = types.SimpleNamespace(
        memoryApiVersion=lambda: 1, selectedAccount=lambda: 0, appVersion=lambda: "0", appLocale=lambda: "en",
        context=lambda: None,
        requirePermission=guarded("requirePermission"), log=guarded("log"), reportError=guarded("reportError"),
        keepAwake=guarded("keepAwake"), bulletin=guarded("bulletin"), copyToClipboard=guarded("copyToClipboard"))

    def run_on_ui(plugin_id, callback, delay):
        side.check(plugin_id, "runOnUi")
        side.queue.put((plugin_id, callback, ()))

    def run_in_background(plugin_id, callback):
        side.check(plugin_id, "runInBackground")
        side.queue.put((plugin_id, callback, ()))

    def send_request(plugin_id, request, callback, account):
        runtime.requirePermission(plugin_id, "network.requests")
        if callback is not None:
            side.queue.put((plugin_id, callback, ("response", None)))
        return 1
    runtime.runOnUi, runtime.runInBackground, runtime.sendRequest = run_on_ui, run_in_background, send_request

    def get_raw(plugin_id, key):
        side.check(plugin_id, "getRaw")
        return settings.get((plugin_id, key))

    def put_raw(plugin_id, key, value):
        side.check(plugin_id, "putRaw")
        settings[(plugin_id, key)] = value

    class Result:
        pass

    classes = {
        "org.telegram.messenger.zephyr.plugins.PluginRuntime": types.SimpleNamespace(INSTANCE=runtime),
        "org.telegram.messenger.zephyr.plugins.PluginHooks": types.SimpleNamespace(**{
            n: guarded(n) for n in ("addRequestHook", "removeRequestHook", "addUpdateHook", "addUpdatesHook",
                                    "addSendMessageHook", "addMessageHook")}),
        "org.telegram.messenger.zephyr.plugins.PluginMenus": types.SimpleNamespace(add=guarded("menuAdd"),
                                                                                remove=guarded("menuRemove")),
        "org.telegram.messenger.zephyr.plugins.PluginSettingsModel": types.SimpleNamespace(getRaw=get_raw,
                                                                                        putRaw=put_raw),
        "org.telegram.messenger.zephyr.plugins.PluginHooks$Result": Result,
        "org.telegram.messenger.zephyr.plugins.PluginChatApi": types.SimpleNamespace(
            getDialogInfo=guarded("getDialogInfo", '{"id": 1}')),
    }
    java = types.ModuleType("java")
    java.jclass = lambda name: classes[name]
    sys.modules["java"] = java


PLUGIN = '''
from zephyr import BasePlugin, MenuItem, MenuType, client, ui
__id__ = "%(id)s"
__name__ = "Identity test"

class Plugin(BasePlugin):
    def on_plugin_load(self):
        self.log("load")
        self.add_on_new_message_hook()
        self.add_menu_item(MenuItem(MenuType.MESSAGE_CONTEXT, "x", self.click, condition=self.cond, key="m"))
        self.set_setting("loaded", True)

    def cond(self, ctx):
        self.log("cond")
        return True

    def click(self, ctx):
        client.run_in_bg(lambda: client.get_dialog_info(1))

    def on_new_message(self, account, message):
        client.keep_awake(10)
        client.run_on_ui(lambda: client.run_in_bg(self.later), delay_ms=10)

    def later(self):
        self.set_setting("later", 1)
        client.send_request(object(), lambda response, error: self.log("response " + str(response)))
        client.bulletin("done")

    def on_app_event(self, event):
        self.error("event " + event)

    def create_settings(self, page=None):
        self.get_setting("loaded")
        return [ui.Switch("s", "S", on_change=lambda v: client.run_in_bg(lambda: self.set_setting("changed", v))),
                ui.Text("t", on_click=lambda: client.copy_to_clipboard("x"), key="t")]

    def on_plugin_unload(self):
        self.remove_menu_item("m")
        self.log("unload")
'''


class PluginIdentity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sys.dont_write_bytecode = True
        cls.side = JavaSide()
        install_fake_java(cls.side)
        sys.path.insert(0, str(PYTHON_ROOT))
        import zephyr_host
        cls.side.host = zephyr_host
        cls.tmp = tempfile.TemporaryDirectory()
        cls.paths = {}
        for plugin_id in ("plugin_a", "plugin_b"):
            path = Path(cls.tmp.name) / (plugin_id + ".plugin")
            path.write_text(PLUGIN % {"id": plugin_id}, encoding="utf-8")
            cls.paths[plugin_id] = path

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()
        sys.path.remove(str(PYTHON_ROOT))
        for name in list(sys.modules):
            if name in ("java", "zephyr_host") or name == "zephyr" or name.startswith(("zephyr.", "_zephyr_plugin_")):
                sys.modules.pop(name)

    def test_every_legitimate_callback_path_keeps_its_identity(self):
        side = self.side
        for plugin_id in self.paths:
            side.load(plugin_id, self.paths[plugin_id])
        for plugin_id in self.paths:
            side.calls.clear()
            side.invoke(plugin_id, "on_new_message", 0, None)
            side.invoke(plugin_id, "on_app_event", "START")
            self.assertTrue(side.invoke(plugin_id, "__menu_condition__", "m", 0, 1, None, None))
            side.invoke(plugin_id, "__menu_click__", "m", 0, 1, None, None)
            side.invoke(plugin_id, "__create_settings__")
            side.invoke(plugin_id, "__settings_event__", "s", "change", "true")
            side.invoke(plugin_id, "__settings_event__", "t", "click", "null")
            side.drain()   # run_on_ui -> run_in_bg -> send_request callback, all on fresh threads
            side.drain()
            side.drain()
            names = [name for name, _ in side.calls]
            for expected in ("keepAwake", "runOnUi", "runInBackground", "putRaw", "requirePermission", "bulletin",
                             "reportError", "log", "getRaw", "getDialogInfo", "copyToClipboard"):
                self.assertIn(expected, names)
            self.assertEqual({owner for _, owner in side.calls}, {plugin_id})
            self.assertGreaterEqual(names.count("log"), 2)  # cond + sendRequest response
        for plugin_id in self.paths:
            side.unload(plugin_id)

    def test_spoofed_id_and_identity_less_threads_are_rejected(self):
        side = self.side
        side.load("plugin_a", self.paths["plugin_a"])
        from zephyr._bridge import Runtime
        from zephyr import client
        with self.assertRaises(SecurityException):
            side.with_identity("plugin_a", Runtime.log, "plugin_b", "spoof")
        errors = []

        def raw_thread():
            try:
                client.bulletin("x")
            except BaseException as exc:  # noqa: BLE001
                errors.append(exc)
        worker = threading.Thread(target=raw_thread)
        worker.start()
        worker.join()
        self.assertEqual(len(errors), 1)
        side.unload("plugin_a")


if __name__ == "__main__":
    unittest.main(verbosity=2)
