"""Execute actual Python client/UI/host files at a synthetic native boundary; no Telegram I/O."""
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

class PickerContextTests(unittest.TestCase):
    def setUp(self):
        self.saved = {name: module for name, module in sys.modules.items() if name == 'zephyr' or name.startswith('zephyr.')}
        self.state = types.SimpleNamespace(plugins={}, current=contextvars.ContextVar('picker_test_owner'))
        package = types.ModuleType('zephyr'); package.__path__ = []
        package.BasePlugin = type('BasePlugin', (), {})
        package.HookResult = type('HookResult', (), {})
        package._state = self.state
        self.runtime = types.SimpleNamespace(pickerContextApiVersion=lambda: 1)
        bridge = types.ModuleType('zephyr._bridge')
        bridge.Runtime = self.runtime
        bridge.ChatApi = types.SimpleNamespace()
        bridge.require = lambda *args: None
        sys.modules['zephyr'] = package
        sys.modules['zephyr._bridge'] = bridge
        sys.modules['zephyr._state'] = self.state
        self.ui = read_module('picker_ui', PYTHON / 'zephyr/ui.py')
        self.host = read_module('picker_host', PYTHON / 'zephyr_host.py')
        self.calls = []
        self.plugin = types.SimpleNamespace(_settings={})
        self.state.plugins['synthetic'] = self.plugin

    def tearDown(self):
        for name in list(sys.modules):
            if name == 'zephyr' or name.startswith('zephyr.'):
                sys.modules.pop(name)
        sys.modules.update(self.saved)

    def event(self, item, context=None):
        self.plugin._settings['choose'] = item
        data = {'id': 42, 'title': 'synthetic peer'}
        if context is not None: data['context'] = context
        self.host.invoke('synthetic', '__settings_event__', ('choose', 'pick', json.dumps(data)))

    def test_old_dialog_callback_still_receives_exactly_two_args(self):
        self.event(self.ui.DialogPicker('choose', 'Pick', lambda *args: self.calls.append(args)), {'account': 1, 'ownerId': '3000'})
        self.assertEqual(self.calls, [(42, 'synthetic peer')])

    def test_old_username_callback_still_receives_exactly_two_args(self):
        self.event(self.ui.UsernameInput('choose', 'Name', lambda *args: self.calls.append(args)))
        self.assertEqual(self.calls, [(42, 'synthetic peer')])

    def test_context_dialog_callback_preserves_source_account_and_owner(self):
        self.event(self.ui.DialogPicker('choose', 'Pick', on_pick_context=lambda *args: self.calls.append(args)), {'account': 0, 'ownerId': '2000'})
        self.assertEqual(self.calls, [(42, 'synthetic peer', {'account': 0, 'ownerId': '2000'})])

    def test_context_username_callback_has_same_contract(self):
        self.event(self.ui.UsernameInput('choose', 'Name', on_resolved_context=lambda *args: self.calls.append(args)), {'account': 1, 'ownerId': '3000'})
        self.assertEqual(self.calls[0][2], {'account': 1, 'ownerId': '3000'})

    def test_old_native_event_cannot_invoke_context_callback(self):
        self.event(self.ui.DialogPicker('choose', 'Pick', on_pick_context=lambda *args: self.calls.append(args)))
        self.assertEqual(self.calls, [])

    def test_invalid_context_does_not_fallback_to_legacy_callback(self):
        item = self.ui.DialogPicker('choose', 'Pick', on_pick_context=lambda *args: self.calls.append(args))
        for context in ({}, {'account': True, 'ownerId': '2000'}, {'account': -1, 'ownerId': '2000'},
                        {'account': 0, 'ownerId': 2000}, {'account': 0, 'ownerId': '0'}):
            self.event(item, context)
        self.assertEqual(self.calls, [])

    def test_both_callbacks_are_rejected_without_retrying_signatures(self):
        with self.assertRaises(ValueError):
            self.ui.DialogPicker('choose', 'Pick', lambda *args: None, on_pick_context=lambda *args: None)
        with self.assertRaises(ValueError):
            self.ui.UsernameInput('choose', 'Name', lambda *args: None, on_resolved_context=lambda *args: None)

    def test_callback_exception_is_not_retried_as_legacy(self):
        def callback(*args):
            self.calls.append(args)
            raise TypeError('synthetic callback error')
        with self.assertRaises(TypeError):
            self.event(self.ui.DialogPicker('choose', 'Pick', on_pick_context=callback), {'account': 0, 'ownerId': '2000'})
        self.assertEqual(len(self.calls), 1)

    def test_client_capability_calls_actual_native_method(self):
        client = read_module('zephyr.client', PYTHON / 'zephyr/client.py')
        self.assertEqual(client.PICKER_CONTEXT_API_VERSION, 1)

    def test_old_runtime_has_no_picker_capability(self):
        del self.runtime.pickerContextApiVersion
        client = read_module('zephyr.client', PYTHON / 'zephyr/client.py')
        self.assertEqual(client.PICKER_CONTEXT_API_VERSION, 0)

if __name__ == '__main__':
    unittest.main(verbosity=2)
