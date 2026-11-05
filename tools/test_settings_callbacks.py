"""Settings callbacks survive a rebuild of the main screen while a sub-screen is open (0.2.6)."""
import contextvars
import importlib.util
import json
from pathlib import Path
import sys
import types
import unittest

ROOT = Path(__file__).resolve().parents[1]
PYTHON = ROOT / 'TMessagesProj/src/main/python'


def read_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SettingsCallbackTests(unittest.TestCase):
    def setUp(self):
        self.saved = {n: m for n, m in sys.modules.items() if n == 'zephyr' or n.startswith('zephyr.')}
        self.state = types.SimpleNamespace(plugins={}, current=contextvars.ContextVar('settings_test_owner'))
        package = types.ModuleType('zephyr'); package.__path__ = []
        package.BasePlugin = type('BasePlugin', (), {})
        package.HookResult = type('HookResult', (), {})
        package._state = self.state
        bridge = types.ModuleType('zephyr._bridge')
        bridge.Runtime = types.SimpleNamespace(pickerContextApiVersion=lambda: 1)
        bridge.ChatApi = types.SimpleNamespace()
        bridge.require = lambda *args: None
        sys.modules['zephyr'] = package
        sys.modules['zephyr._bridge'] = bridge
        sys.modules['zephyr._state'] = self.state
        self.ui = read_module('settings_ui', PYTHON / 'zephyr/ui.py')
        sys.modules['zephyr.ui'] = self.ui
        self.host = read_module('settings_host', PYTHON / 'zephyr_host.py')
        self.clicks, self.logs = [], []
        ui, clicks, logs = self.ui, self.clicks, self.logs

        class Plugin(object):
            _settings = None

            def create_settings(self, page=None):
                if page == 'pg_chat':
                    return [ui.Switch(key='keep', text='Reply', default=True, on_change=lambda v: clicks.append(('keep', v))),
                            ui.Text(text='Test', key='test', on_click=lambda: clicks.append('test'))]
                return [ui.Text(text='Root action', key='root_action', on_click=lambda: clicks.append('root'))]

            def log(self, message):
                logs.append(message)

        self.plugin = Plugin(); self.plugin._settings = {}
        self.state.plugins['synthetic'] = self.plugin

    def tearDown(self):
        for name in list(sys.modules):
            if name == 'zephyr' or name.startswith('zephyr.'):
                sys.modules.pop(name)
        sys.modules.update(self.saved)

    def build(self, page=None):
        args = (page,) if page else ()
        return json.loads(self.host.invoke('synthetic', '__create_settings__', args))

    def event(self, key, event='click', value='null'):
        return self.host.invoke('synthetic', '__settings_event__', (key, event, value))

    def test_sub_screen_buttons_work_after_main_screen_rebuild(self):
        self.build(); self.build('pg_chat')
        self.build()  # the main screen under the open chat page reloads (a chat switch, a plugin update)
        self.event('test')
        self.event('keep', 'change', 'false')
        self.event('root_action')
        self.assertEqual(self.clicks, ['test', ('keep', False), 'root'])
        self.assertEqual(self.logs, [])

    def test_rebuilt_key_takes_the_newest_callback(self):
        self.build('pg_chat')
        first = self.plugin._settings['test']
        self.build('pg_chat')
        self.assertIsNot(self.plugin._settings['test'], first)

    def test_unknown_key_is_logged_not_silent(self):
        self.build()
        self.assertIsNone(self.event('nope'))
        self.assertEqual(len(self.logs), 1)
        self.assertIn("'nope'", self.logs[0])


if __name__ == '__main__':
    unittest.main()
