"""Test actual Python host wrappers using a synthetic native boundary; no network or credentials."""
import importlib.util
import json
from pathlib import Path
import sys
import types
import unittest

CLIENT = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python/zephyr/client.py"
BINDING = "a" * 64
MESSAGE = {"messageId": 1, "senderId": "3000", "speaker": "other", "text": "synthetic example", "date": "2026-11-03T08:00:00Z"}
RECORD = {"summary": "synthetic old summary", "throughId": 12, "revision": 2, "updatedAt": "2026-11-02T10:00:00Z"}


class Native:
    def __init__(self):
        self.calls = []
        self.failure = None
        self.response = {"ok": True}

    def __getattr__(self, name):
        def call(*args):
            self.calls.append((name, args))
            if self.failure:
                raise self.failure
            return json.dumps(self.response)
        return call


class MemoryWrappers(unittest.TestCase):
    def setUp(self):
        self.native = Native()
        self.allowed = True
        self.required = []
        name = "memory_bridge_fixture"
        package = types.ModuleType(name)
        package.__path__ = []
        state = types.ModuleType(name + "._state")
        state.owner = lambda: types.SimpleNamespace(id="synthetic_plugin")
        bridge = types.ModuleType(name + "._bridge")
        bridge.ChatApi = self.native
        bridge.Runtime = types.SimpleNamespace(memoryApiVersion=lambda: 1)

        def require(plugin, permission):
            self.required.append((plugin.id, permission))
            if not self.allowed:
                raise PermissionError("missing ai.memory")
        bridge.require = require
        for module in (package, state, bridge):
            sys.modules[module.__name__] = module
        spec = importlib.util.spec_from_file_location(name + ".client", CLIENT)
        self.client = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.client)

    def tearDown(self):
        for name in list(sys.modules):
            if name.startswith("memory_bridge_fixture"):
                sys.modules.pop(name)

    def test_status_capability_and_explicit_account(self):
        self.assertEqual(self.client.MEMORY_API_VERSION, 1)
        self.client.ai_memory_status(1, timeout=5)
        self.assertEqual(self.native.calls, [("aiMemoryStatus", ("synthetic_plugin", 1, 5))])
        self.assertEqual(self.required, [("synthetic_plugin", "ai.memory")])
        for account in (None, True, "0", -1):
            with self.assertRaises(self.client.AiError):
                self.client.ai_memory_status(account)

    def test_all_data_methods_pass_binding_and_scope_without_owner_override(self):
        c = self.client
        c.ai_memory_consent(0, 3000, 0, 30, timeout=5, expected_binding=BINDING)
        c.ai_memory_write(0, 3000, 0, [MESSAGE], timeout=5, expected_binding=BINDING)
        c.ai_memory_context(0, 3000, 0, "example", timeout=5, expected_binding=BINDING)
        c.ai_memory_delete(0, 3000, 0, timeout=5, expected_binding=BINDING)
        c.ai_memory_import(0, 3000, 0, RECORD, timeout=5, expected_binding=BINDING)
        self.assertEqual([name for name, _ in self.native.calls],
                         ["aiMemoryConsent", "aiMemoryWrite", "aiMemoryContext", "aiMemoryDelete", "aiMemoryImport"])
        for _, args in self.native.calls:
            self.assertEqual(args[:4], ("synthetic_plugin", 0, 3000, 0))
            self.assertEqual(args[-2:], (5, BINDING))
        self.assertEqual(json.loads(self.native.calls[1][1][4]), [MESSAGE])
        self.assertEqual(json.loads(self.native.calls[4][1][4]), RECORD)

    def test_cancel_is_local_synchronous_native_call_with_permission(self):
        self.assertIsNone(self.client.ai_memory_cancel())
        self.assertEqual(self.native.calls, [("aiMemoryCancel", ("synthetic_plugin",))])
        self.assertEqual(self.required, [("synthetic_plugin", "ai.memory")])

    def test_missing_binding_never_reaches_native_or_network(self):
        c = self.client
        for action in (lambda: c.ai_memory_consent(0, 3000, 0),
                       lambda: c.ai_memory_write(0, 3000, 0, [MESSAGE]),
                       lambda: c.ai_memory_context(0, 3000, 0),
                       lambda: c.ai_memory_delete(0, 3000, 0),
                       lambda: c.ai_memory_import(0, 3000, 0, RECORD)):
            with self.assertRaises(c.AiError) as failure:
                action()
            self.assertEqual(failure.exception.code, 13)
        self.assertEqual(self.native.calls, [])

    def test_permission_is_required_independently_of_chat_completion(self):
        self.allowed = False
        with self.assertRaises(PermissionError):
            self.client.ai_memory_status(0)
        with self.assertRaises(PermissionError):
            self.client.ai_memory_delete(0, 3000, 0, expected_binding=BINDING)
        self.assertEqual(self.native.calls, [])

    def test_older_runtime_fails_closed(self):
        self.client.MEMORY_API_VERSION = 0
        with self.assertRaises(self.client.AiError) as failure:
            self.client.ai_memory_status(0)
        self.assertEqual(failure.exception.code, 9)
        self.assertEqual(self.native.calls, [])

    def test_unexpected_native_error_is_sanitized(self):
        self.native.failure = RuntimeError("synthetic-secret-in-server-error")
        with self.assertRaises(self.client.AiError) as failure:
            self.client.ai_memory_status(0)
        self.assertEqual(str(failure.exception), "memory_operation_failed")
        self.assertNotIn("synthetic-secret", str(failure.exception))

    def test_native_security_error_remains_permission_error(self):
        self.native.failure = type("JavaSecurityException", (RuntimeError,), {})("private payload")
        with self.assertRaises(PermissionError) as failure:
            self.client.ai_memory_status(0)
        self.assertEqual(str(failure.exception), "Plugin needs permission ai.memory")

    def test_native_error_code_survives_without_exposing_payload(self):
        self.native.failure = RuntimeError("memory_binding_changed")
        self.native.failure.code = 13
        with self.assertRaises(self.client.AiError) as failure:
            self.client.ai_memory_status(0)
        self.assertEqual(failure.exception.code, 13)

    def test_write_rejects_invalid_sources_and_bounded_payloads(self):
        invalid = [[], [MESSAGE] * 51, [MESSAGE, MESSAGE],
                   [dict(MESSAGE, ownerId="9999")], [dict(MESSAGE, senderId=3000)],
                   [dict(MESSAGE, speaker="system")], [dict(MESSAGE, text="x" * 4001)],
                   [dict(MESSAGE, date="no timezone")], [dict(MESSAGE, messageId=True)]]
        for rows in invalid:
            with self.assertRaises(self.client.AiError):
                self.client.ai_memory_write(0, 3000, 0, rows, expected_binding=BINDING)
        self.assertEqual(self.native.calls, [])

    def test_import_never_fakes_group_provenance(self):
        for dialog, topic in ((-1001234567890, 3), (3000, 1)):
            with self.assertRaises(self.client.AiError):
                self.client.ai_memory_import(0, dialog, topic, RECORD, expected_binding=BINDING)
        for record in (dict(RECORD, summary="x" * 8001), dict(RECORD, revision=0), dict(RECORD, ownerId="9999")):
            with self.assertRaises(self.client.AiError):
                self.client.ai_memory_import(0, 3000, 0, record, expected_binding=BINDING)
        self.assertEqual(self.native.calls, [])

    def test_unknown_topic_and_invalid_account_scope_fail_before_native(self):
        for topic in (None, True, -1, 2147483648):
            with self.assertRaises(self.client.AiError):
                self.client.ai_memory_context(0, -1001234567890, topic, expected_binding=BINDING)
        self.assertEqual(self.native.calls, [])

    def test_http_get_refuses_cleartext_and_non_public_targets(self):
        import socket
        from unittest import mock
        for url in ("http://example.com/", "ftp://example.com/", "https://127.0.0.1/", "https://10.0.0.5/x",
                    "https://169.254.169.254/latest", "https://[::1]/", "https://[::ffff:192.168.1.1]/",
                    "https://[fe80::1]/", "https://0.0.0.0/", "https:///nohost"):
            with self.assertRaises(ValueError, msg=url):
                self.client.http_get(url)

        def resolve(host, port, **kwargs):
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ({"internal.test": "10.1.2.3"}.get(host, "93.184.216.34"), port))]
        with mock.patch.object(socket, "getaddrinfo", resolve):
            with self.assertRaises(ValueError):
                self.client.http_get("https://internal.test/")
            self.client._check_public_https("https://public.test/path")
        for ok in ("https://[64:ff9b::808:808]/", "https://[2606:4700::1111]/"):
            self.client._check_public_https(ok)  # NAT64 of a public IPv4 (IPv6-only mobile networks) stays allowed
        for bad in ("https://[64:ff9b::a00:1]/", "https://[64:ff9b::7f00:1]/", "https://[2002:a00:1::1]/"):
            with self.assertRaises(ValueError, msg=bad):
                self.client._check_public_https(bad)
        with mock.patch.object(socket, "getaddrinfo", resolve):
            with self.assertRaises(ValueError):
                self.client._PublicHttpsRedirects().redirect_request(
                    None, None, 302, "Found", {}, "http://public.test/")
            with self.assertRaises(ValueError):
                self.client._PublicHttpsRedirects().redirect_request(
                    None, None, 302, "Found", {}, "https://internal.test/")


if __name__ == "__main__":
    unittest.main(verbosity=2)
