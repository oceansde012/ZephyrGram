"""Python wrappers of the memory style layer (memory.style) against a synthetic native boundary.
No Android, network or credentials. Run: python3 tools/test_memory_style_bridge.py"""
import importlib.util
import json
from pathlib import Path
import sys
import types
import unittest

CLIENT = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python/zephyr/client.py"
BINDING = "b" * 64
PAIR = {"contextText": "synthetic question?", "replyText": "synthetic answer", "source": "owner_real"}


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


def load(native, required, allowed, style_version=1):
    name = "memory_style_fixture"
    package = types.ModuleType(name)
    package.__path__ = []
    state = types.ModuleType(name + "._state")
    state.owner = lambda: types.SimpleNamespace(id="synthetic_plugin")
    bridge = types.ModuleType(name + "._bridge")
    bridge.ChatApi = native
    runtime = types.SimpleNamespace(memoryApiVersion=lambda: 1)
    if style_version is not None:
        runtime.memoryStyleApiVersion = lambda: style_version
    bridge.Runtime = runtime

    def require(plugin, permission):
        required.append((plugin.id, permission))
        if permission not in allowed:
            raise PermissionError("missing " + permission)
    bridge.require = require
    for module in (package, state, bridge):
        sys.modules[module.__name__] = module
    spec = importlib.util.spec_from_file_location(name + ".client", CLIENT)
    client = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(client)
    return client


class StyleWrappers(unittest.TestCase):
    def setUp(self):
        self.native = Native()
        self.required = []
        self.allowed = {"memory.style"}
        self.client = load(self.native, self.required, self.allowed)

    def tearDown(self):
        for name in list(sys.modules):
            if name.startswith("memory_style_fixture"):
                sys.modules.pop(name)

    def body(self, index=-1):
        payload = self.native.calls[index][1][4]
        return None if payload is None else json.loads(payload)

    def test_capability_flags(self):
        self.assertTrue(self.client.MEMORY_STYLE)
        self.assertEqual(self.client.MEMORY_STYLE_API_VERSION, 1)
        for version in (None, 0, 2):
            old = load(Native(), [], {"memory.style"}, style_version=version)
            self.assertFalse(old.MEMORY_STYLE)
            with self.assertRaises(old.AiError) as failure:
                old.memory_style_status(0)
            self.assertEqual(failure.exception.code, 9)

    def test_status_needs_only_memory_style(self):
        self.client.memory_style_status(1, timeout=99)
        self.assertEqual(self.native.calls, [("aiMemoryStyleStatus", ("synthetic_plugin", 1, 30))])
        self.assertEqual(self.required, [("synthetic_plugin", "memory.style")])

    def test_v1_memory_still_requires_ai_memory(self):
        with self.assertRaises(PermissionError):
            self.client.ai_memory_status(0)
        self.assertEqual(self.native.calls, [])

    def test_every_operation_passes_fixed_operation_area_body_timeout_and_binding(self):
        c = self.client
        c.memory_style_add(0, "dialog:3000", [PAIR], timeout=5, expected_binding=BINDING)
        c.memory_style_search(0, "dialog:3000", "в кино?", k=3, timeout=5, expected_binding=BINDING)
        c.memory_feedback(0, "dialog:3000", [{"outcome": "edited", "draftText": "Да", "finalText": "да)", "contextText": "?"}],
                          timeout=5, expected_binding=BINDING)
        c.memory_facts_add(0, "global", [{"subject": "self", "fact": "synthetic", "confidence": 0.9, "sourceMsg": "3000:1"}],
                           timeout=5, expected_binding=BINDING)
        c.memory_facts_search(0, "global", "peer", timeout=5, expected_binding=BINDING)
        c.memory_digest(0, "global", timeout=5, expected_binding=BINDING)
        c.memory_delete_area(0, "dialog:3000", timeout=5, expected_binding=BINDING)
        c.memory_delete_all_areas(0, timeout=5, expected_binding=BINDING)
        ops = [(args[2], args[3]) for _, args in self.native.calls]
        self.assertEqual(ops, [("style_add", "dialog:3000"), ("style_search", "dialog:3000"), ("feedback", "dialog:3000"),
                               ("facts_add", "global"), ("facts_search", "global"), ("digest", "global"),
                               ("delete_area", "dialog:3000"), ("delete_all", None)])
        for name, args in self.native.calls:
            self.assertEqual(name, "aiMemoryStyle")
            self.assertEqual(args[:2], ("synthetic_plugin", 0))
            self.assertEqual(args[-2:], (5, BINDING))
        self.assertEqual(self.body(0), {"examples": [PAIR]})
        self.assertEqual(self.body(1), {"query": "в кино?", "k": 3})
        self.assertEqual(self.body(4), {"subject": "peer", "query": "", "k": 10})
        self.assertEqual(self.body(5), {"generate": False})
        self.assertIsNone(self.body(6))
        self.assertIsNone(self.body(7))
        self.assertEqual(set(self.required), {("synthetic_plugin", "memory.style")})

    def test_pairs_accept_tuples_default_source_and_drop_none(self):
        self.client.memory_style_add(0, "global", [("q", "a"), ("q2", "a2", "accepted"),
                                                   {"contextText": "q3", "replyText": "a3", "quality": None}],
                                     expected_binding=BINDING)
        self.assertEqual(self.body()["examples"], [
            {"contextText": "q", "replyText": "a", "source": "owner_real"},
            {"contextText": "q2", "replyText": "a2", "source": "accepted"},
            {"contextText": "q3", "replyText": "a3", "source": "owner_real"}])

    def test_digest_generation_gets_a_longer_timeout(self):
        self.client.memory_digest(0, "global", generate=True, period_days=7, timeout=120, expected_binding=BINDING)
        self.assertEqual(self.native.calls[-1][1][5], 60)
        self.assertEqual(self.body(), {"generate": True, "periodDays": 7})
        self.client.memory_digest(0, "global", timeout=120, expected_binding=BINDING)
        self.assertEqual(self.native.calls[-1][1][5], 30)

    def test_area_helper(self):
        c = self.client
        self.assertEqual(c.memory_style_area(3000), "dialog:3000")
        self.assertEqual(c.memory_style_area(-1001234567890, 7), "dialog:-1001234567890:7")
        for dialog, topic in ((0, 0), (3000, -1), (True, 0), (3000, None)):
            with self.assertRaises(c.AiError):
                c.memory_style_area(dialog, topic)

    def test_missing_binding_never_reaches_native(self):
        c = self.client
        for action in (lambda: c.memory_style_add(0, "global", [PAIR]),
                       lambda: c.memory_style_search(0, "global", "q"),
                       lambda: c.memory_feedback(0, "global", [{"outcome": "rejected", "draftText": "x"}]),
                       lambda: c.memory_facts_add(0, "global", [{"subject": "self", "fact": "x"}]),
                       lambda: c.memory_facts_search(0, "global", "self"),
                       lambda: c.memory_digest(0, "global"),
                       lambda: c.memory_delete_area(0, "global"),
                       lambda: c.memory_delete_all_areas(0)):
            with self.assertRaises(c.AiError) as failure:
                action()
            self.assertEqual(failure.exception.code, 13)
        self.assertEqual(self.native.calls, [])

    def test_invalid_input_fails_before_native(self):
        c, b = self.client, BINDING
        cases = [
            lambda: c.memory_style_add(0, "dialog/3000", [PAIR], expected_binding=b),
            lambda: c.memory_style_add(0, "", [PAIR], expected_binding=b),
            lambda: c.memory_style_add(0, "x" * 65, [PAIR], expected_binding=b),
            lambda: c.memory_style_add(0, "global", [], expected_binding=b),
            lambda: c.memory_style_add(0, "global", [PAIR] * 51, expected_binding=b),
            lambda: c.memory_style_add(0, "global", [dict(PAIR, source="model")], expected_binding=b),
            lambda: c.memory_style_add(0, "global", [dict(PAIR, quality=True)], expected_binding=b),
            lambda: c.memory_style_add(0, "global", [dict(PAIR, replyText=" ")], expected_binding=b),
            lambda: c.memory_style_add(0, "global", [dict(PAIR, replyText="x" * 4001)], expected_binding=b),
            lambda: c.memory_style_add(0, "global", [dict(PAIR, ownerId="9999")], expected_binding=b),
            lambda: c.memory_style_add(0, "global", [("only",)], expected_binding=b),
            lambda: c.memory_style_search(0, "global", "q", k=9, expected_binding=b),
            lambda: c.memory_style_search(0, "global", "", expected_binding=b),
            lambda: c.memory_feedback(0, "global", [{"outcome": "edited", "draftText": "a", "finalText": "b",
                                                      "editDistance": 1}], expected_binding=b),
            lambda: c.memory_feedback(0, "global", [{"outcome": "edited", "draftText": "a"}], expected_binding=b),
            lambda: c.memory_feedback(0, "global", [{"outcome": "rejected", "draftText": "a", "finalText": "b"}],
                                      expected_binding=b),
            lambda: c.memory_feedback(0, "global", [{"outcome": "owner_first", "draftText": "a"}], expected_binding=b),
            lambda: c.memory_feedback(0, "global", [{"outcome": "accepted"}], expected_binding=b),
            lambda: c.memory_facts_add(0, "global", [{"subject": "them", "fact": "x"}], expected_binding=b),
            lambda: c.memory_facts_add(0, "global", [{"subject": "self", "fact": "x", "confidence": True}], expected_binding=b),
            lambda: c.memory_facts_add(0, "global", [{"subject": "self", "fact": "x", "confidence": 1.5}], expected_binding=b),
            lambda: c.memory_facts_add(0, "global", [{"subject": "self", "fact": "x", "sourceMsg": "a/b"}], expected_binding=b),
            lambda: c.memory_facts_add(0, "global", [{"subject": "peer", "fact": "x", "expiresInDays": 366}], expected_binding=b),
            lambda: c.memory_facts_add(0, "global", [{"subject": "self", "fact": "x" * 501}], expected_binding=b),
            lambda: c.memory_facts_search(0, "global", "both", expected_binding=b),
            lambda: c.memory_facts_search(0, "global", "self", k=21, expected_binding=b),
            lambda: c.memory_facts_search(0, "global", "self", query="x" * 201, expected_binding=b),
            lambda: c.memory_digest(0, "global", generate="yes", expected_binding=b),
            lambda: c.memory_digest(0, "global", generate=True, period_days=32, expected_binding=b),
            lambda: c.memory_delete_area(0, None, expected_binding=b),
            lambda: c.memory_style_status(-1),
        ]
        for index, action in enumerate(cases):
            with self.assertRaises(c.AiError, msg=str(index)) as failure:
                action()
            self.assertIn(failure.exception.code, (10, 12), msg=str(index))
        self.assertEqual(self.native.calls, [])

    def test_permission_is_separate_from_ai_memory(self):
        self.allowed.clear()
        self.allowed.add("ai.memory")
        with self.assertRaises(PermissionError):
            self.client.memory_style_status(0)
        with self.assertRaises(PermissionError):
            self.client.memory_delete_area(0, "global", expected_binding=BINDING)
        with self.assertRaises(PermissionError):
            self.client.memory_style_cancel()
        self.assertEqual(self.native.calls, [])

    def test_native_security_error_becomes_permission_error(self):
        self.native.failure = type("JavaSecurityException", (RuntimeError,), {})("private payload")
        with self.assertRaises(PermissionError) as failure:
            self.client.memory_style_status(0)
        self.assertEqual(str(failure.exception), "Plugin needs permission memory.style")

    def test_native_errors_keep_code_and_fixed_key_only(self):
        self.native.failure = RuntimeError("memory_daily_limit")
        self.native.failure.code = 3
        with self.assertRaises(self.client.AiError) as failure:
            self.client.memory_digest(0, "global", generate=True, expected_binding=BINDING)
        self.assertEqual((str(failure.exception), failure.exception.code), ("memory_daily_limit", 3))
        self.native.failure = RuntimeError("synthetic-secret-in-server-error")
        with self.assertRaises(self.client.AiError) as failure:
            self.client.memory_style_search(0, "global", "q", expected_binding=BINDING)
        self.assertEqual(str(failure.exception), "memory_operation_failed")

    def test_cancel_is_local_and_needs_memory_style(self):
        self.assertIsNone(self.client.memory_style_cancel())
        self.assertEqual(self.native.calls, [("aiMemoryStyleCancel", ("synthetic_plugin",))])

    def test_older_native_runtime_fails_closed(self):
        self.client.MEMORY_STYLE_API_VERSION = 0
        with self.assertRaises(self.client.AiError) as failure:
            self.client.memory_style_add(0, "global", [PAIR], expected_binding=BINDING)
        self.assertEqual(failure.exception.code, 9)
        self.assertEqual(self.native.calls, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
