"""zephyr_host caches compiled plugin bytecode next to the plugin file (0.2.6)."""
import builtins
import contextvars
import importlib.util
from pathlib import Path
import sys
import tempfile
import types
import unittest

ROOT = Path(__file__).resolve().parents[1]
PYTHON = ROOT / 'TMessagesProj/src/main/python'


class BytecodeCacheTests(unittest.TestCase):
    def setUp(self):
        self.saved = {n: m for n, m in sys.modules.items() if n == 'zephyr' or n.startswith('zephyr.')}
        package = types.ModuleType('zephyr'); package.__path__ = []
        package.BasePlugin = type('BasePlugin', (), {})
        package.HookResult = type('HookResult', (), {})
        package._state = types.SimpleNamespace(plugins={}, current=contextvars.ContextVar('cache_test'))
        bridge = types.ModuleType('zephyr._bridge')
        bridge.Runtime = types.SimpleNamespace()
        sys.modules['zephyr'] = package
        sys.modules['zephyr._bridge'] = bridge
        spec = importlib.util.spec_from_file_location('cache_host', PYTHON / 'zephyr_host.py')
        self.host = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.host)
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / 'demo.plugin'
        self.compiles = 0
        real = builtins.compile

        def counting(*args, **kwargs):
            self.compiles += 1
            return real(*args, **kwargs)
        self.host.compile = counting  # module global shadows the builtin inside zephyr_host

    def tearDown(self):
        self.dir.cleanup()
        for name in list(sys.modules):
            if name == 'zephyr' or name.startswith('zephyr.'):
                sys.modules.pop(name)
        sys.modules.update(self.saved)

    def run_code(self, source):
        self.path.write_bytes(source)
        scope = {}
        exec(self.host._compiled(source, str(self.path)), scope)
        return scope['VALUE']

    def test_second_load_uses_cache(self):
        self.assertEqual(self.run_code(b'VALUE = 1\n'), 1)
        self.assertTrue(Path(str(self.path) + '.pyc').exists())
        self.assertEqual(self.run_code(b'VALUE = 1\n'), 1)
        self.assertEqual(self.compiles, 1)

    def test_changed_source_recompiles(self):
        self.run_code(b'VALUE = 1\n')
        self.assertEqual(self.run_code(b'VALUE = 2\n'), 2)
        self.assertEqual(self.compiles, 2)

    def test_corrupt_cache_falls_back(self):
        self.run_code(b'VALUE = 1\n')
        cache = Path(str(self.path) + '.pyc')
        cache.write_bytes(cache.read_bytes()[:40] + b'garbage')
        self.assertEqual(self.run_code(b'VALUE = 1\n'), 1)
        self.assertEqual(self.compiles, 2)
        self.assertEqual(self.run_code(b'VALUE = 1\n'), 1)
        self.assertEqual(self.compiles, 2)

    def test_errors_keep_their_file_name(self):
        self.path.write_bytes(b'x = (\n')
        with self.assertRaises(SyntaxError) as e:
            self.host._compiled(b'x = (\n', str(self.path))
        self.assertEqual(e.exception.filename, str(self.path))


if __name__ == '__main__':
    unittest.main()
