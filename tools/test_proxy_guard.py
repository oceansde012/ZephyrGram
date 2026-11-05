"""dynamic_proxy crash guard (0.2.7): an exception in a plugin proxy method never reaches the Java caller.
The fake below mirrors Chaquopy 17's metaclass rules (proxy base named _chaquopy_proxy must be the first base,
interface methods found via iface.getClass().getMethods()). Run: python3 tools/test_proxy_guard.py"""
from pathlib import Path
import sys
import tempfile
import types
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_inline_media import JavaSide, PYTHON_ROOT, install_fake_java  # noqa: E402

PROXY_BASE_NAME = "_chaquopy_proxy"


class _Named:
    def __init__(self, name):
        self._name = name

    def getName(self):
        return self._name


class _Method:
    def __init__(self, name, ret):
        self._name, self._ret = name, ret

    def getName(self):
        return self._name

    def getReturnType(self):
        return _Named(self._ret)


def interface(name, **methods):
    klass = types.SimpleNamespace(getMethods=lambda: [_Method(m, r) for m, r in methods.items()],
                                  isInterface=lambda: True, getName=lambda: name)
    return types.SimpleNamespace(getClass=lambda: klass)


class FakeProxyClass(type):
    """Same structural checks as chaquopy ProxyClass.__new__."""
    def __new__(metacls, cls_name, bases, cls_dict):
        if cls_name == PROXY_BASE_NAME:
            return type.__new__(metacls, cls_name, bases, cls_dict)
        if not (bases and bases[0].__name__ == PROXY_BASE_NAME):
            raise TypeError("dynamic_proxy must be used first in class bases")
        return type.__new__(metacls, cls_name, bases, cls_dict)


def fake_dynamic_proxy(*implements):
    for i in implements:
        if not i.getClass().isInterface():
            raise TypeError("not a Java interface")
    return FakeProxyClass(PROXY_BASE_NAME, (), dict(implements=implements))


ON_CLICK = interface("android.content.DialogInterface$OnClickListener", onClick="void")
CALLBACKS = interface("test.Callbacks", accept="boolean", count="int", size="long", ratio="double",
                      label="java.lang.String")

PLUGIN = '''
from zephyr import BasePlugin, client
import java
__id__ = "proxy_test"
__name__ = "Proxy test"

class Plugin(BasePlugin):
    def on_plugin_load(self):
        self.calls = []

    def make(self, onclick, callbacks):
        plugin = self

        class Click(java.dynamic_proxy(onclick)):
            def onClick(self, dialog, which):
                plugin.calls.append(("click", which))
                if which < 0:
                    raise RuntimeError("boom with private data")
                if which == 99:
                    # Java-initiated call: no Java caller identity, so the bridge refuses; must stay contained.
                    client.run_in_bg(lambda: None)

        class Many(java.dynamic_proxy(callbacks)):
            def accept(self, x):
                raise ValueError(x)

            def count(self):
                raise KeyError("k")

            def size(self):
                raise OverflowError()

            def ratio(self):
                raise ZeroDivisionError()

            def label(self):
                raise TypeError()

            def helper(self):
                raise RuntimeError("not an interface method, not wrapped")

        return Click(), Many()
'''


class ProxyGuard(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.side = JavaSide()
        install_fake_java(cls.side)
        import java
        java.dynamic_proxy = fake_dynamic_proxy
        runtime = java.jclass("org.telegram.messenger.zephyr.plugins.PluginRuntime").INSTANCE
        cls.proxy_logs = []
        runtime.logProxyError = lambda pid, method, err: cls.proxy_logs.append((pid, method, err))
        # Owner tokens like PluginRuntime: issued only to the current caller, enter/exit switch the Java identity.
        cls.tokens = {}
        cls.token_issuing = True

        def proxy_token(pid):
            cls.side.check(pid, "proxyToken")
            if not cls.token_issuing:
                raise RuntimeError("old app")
            token = 1000 + len(cls.tokens)
            cls.tokens[token] = pid
            return token

        def proxy_enter(token):
            pid = cls.tokens.get(token)
            if pid is None or pid not in cls.side.host_loaded():
                return False
            stack = cls.side.__dict__.setdefault("stack", [])
            stack.append(cls.side.current())
            cls.side.local.id = pid
            return True

        def proxy_exit(token):
            cls.side.local.id = cls.side.stack.pop()

        runtime.proxyToken = proxy_token
        runtime.proxyEnter = proxy_enter
        runtime.proxyExit = proxy_exit
        sys.path.insert(0, str(PYTHON_ROOT))
        import zephyr_host
        cls.side.host = zephyr_host
        from zephyr import _state as _st
        cls.side.host_loaded = lambda: set(_st.plugins)
        cls.java = java
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.tmp.name) / "proxy_test.plugin"
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
        self.proxy_logs.clear()
        self.side.permissions["proxy_test"] = set()
        self.side.load("proxy_test", self.path, self.tmp.name)
        from zephyr import _state
        self.plugin = _state.plugins["proxy_test"]
        token = _state.current.set(self.plugin)  # proxies are created inside a plugin callback
        try:
            self.click, self.many = self.side.with_identity(
                "proxy_test", self.plugin.make, ON_CLICK, CALLBACKS)
        finally:
            _state.current.reset(token)

    def tearDown(self):
        try:
            self.side.unload("proxy_test")
        except Exception:
            pass

    def test_installed_once(self):
        self.assertTrue(getattr(self.java.dynamic_proxy, "_zephyr_safe", False))
        from zephyr import _proxy
        _proxy.install(self.java)
        self.assertIs(self.java.dynamic_proxy._zephyr_safe, True)

    def test_raising_void_method_does_not_reach_java(self):
        self.assertIsNone(self.click.onClick(None, -1))  # Java caller: no exception
        self.assertEqual(self.plugin.calls, [("click", -1)])
        self.assertEqual(self.proxy_logs, [("proxy_test", "onClick", "RuntimeError")])

    def test_plugin_context_is_bound_for_java_calls(self):
        from zephyr import _state
        seen = []

        class Recorder(list):
            def append(self, item):
                seen.append(_state.current.get())
                super().append(item)
        self.plugin.calls = Recorder()
        self.click.onClick(None, 1)
        self.assertEqual(seen, [self.plugin])
        self.assertIsNone(_state.current.get())  # restored after the call
        self.assertEqual(self.proxy_logs, [])

    def test_client_api_works_in_java_initiated_calls(self):
        self.side.permissions["proxy_test"] = set()
        self.assertIsNone(self.side.current())          # Java thread: no caller identity
        self.assertIsNone(self.click.onClick(None, 99))  # client.run_in_bg inside the proxy
        self.assertEqual(self.proxy_logs, [])
        self.assertFalse(self.side.queue.empty())        # the call went through as proxy_test
        self.assertIsNone(self.side.current())          # identity restored after the call
        self.side.drain()

    def test_without_owner_token_the_refusal_is_contained(self):
        type(self).token_issuing = False
        try:
            from zephyr import _state
            token = _state.current.set(self.plugin)
            try:
                click, _ = self.side.with_identity("proxy_test", self.plugin.make, ON_CLICK, CALLBACKS)
            finally:
                _state.current.reset(token)
        finally:
            type(self).token_issuing = True
        self.assertIsNone(click.onClick(None, 99))      # old app: run_in_bg refused, contained and logged
        self.assertEqual(len(self.proxy_logs), 1)
        self.assertEqual(self.proxy_logs[0][:2], ("proxy_test", "onClick"))

    def test_safe_defaults_by_return_type(self):
        self.assertIs(self.many.accept(1), False)
        self.assertEqual(self.many.count(), 0)
        self.assertEqual(self.many.size(), 0)
        self.assertEqual(self.many.ratio(), 0.0)
        self.assertIsNone(self.many.label())
        self.assertEqual([m for _, m, _ in self.proxy_logs], ["accept", "count", "size", "ratio", "label"])
        self.assertNotIn("boom", repr(self.proxy_logs))

    def test_non_interface_methods_are_untouched(self):
        with self.assertRaises(RuntimeError):
            self.many.helper()

    def test_unloaded_plugin_code_is_not_run(self):
        self.side.unload("proxy_test")
        self.assertIsNone(self.click.onClick(None, 5))
        self.assertEqual(self.plugin.calls, [])

    def test_base_rules_still_enforced(self):
        with self.assertRaises(TypeError):
            self.java.dynamic_proxy(types.SimpleNamespace(getClass=lambda: types.SimpleNamespace(
                isInterface=lambda: False)))


class JavaSideChecks(unittest.TestCase):
    def test_log_entry_point(self):
        root = Path(__file__).resolve().parents[1]
        s = (root / "TMessagesProj/src/main/java/org/telegram/messenger/zephyr/plugins/PluginRuntime.java").read_text("utf-8")
        body = s[s.index("public static void logProxyError("):s.index("private static String sanitizeName(")]
        self.assertIn("loaded.containsKey(id)", body)
        self.assertIn("sanitizeName(method)", body)
        tok = s[s.index("public long proxyToken("):s.index("public boolean proxyEnter(")]
        self.assertIn("PluginHooks.requireCaller(id);", tok)
        self.assertIn("SecureRandom", s)
        enter = s[s.index("public boolean proxyEnter("):s.index("public void proxyExit(")]
        self.assertIn("loaded.get(owner.id) != owner.generation", enter)
        exit_ = s[s.index("public void proxyExit("):s.index("public static void logProxyError(")]
        self.assertIn("owner.id.equals(PluginHooks.getCurrentPlugin())", exit_)
        host = (root / "TMessagesProj/src/main/python/zephyr_host.py").read_text("utf-8")
        self.assertIn("_proxy.install(_java)", host)


if __name__ == "__main__":
    unittest.main()
