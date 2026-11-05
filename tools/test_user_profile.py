"""client.get_user_profile / client.download_profile_photo (0.2.7): chat.read, normalisation of the Java JSON,
account and argument passing. Cache reads, the one-shot UserFull load and the download live in PluginChatApi (Java).
Run: python3 tools/test_user_profile.py"""
from pathlib import Path
import json
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_inline_media import JavaSide, PYTHON_ROOT, install_fake_java  # noqa: E402

PLUGIN = '''
from zephyr import BasePlugin, client
__id__ = "profile_test"
__name__ = "Profile test"

class Plugin(BasePlugin):
    def on_plugin_load(self):
        pass
'''

FULL = {"id": 42, "first_name": "Ann", "last_name": "Lee", "username": "ann", "bio": "Hi there",
        "bio_loaded": True, "is_premium": True, "has_photo": True, "is_bot": False, "is_self": False}


class UserProfile(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.side = JavaSide()
        install_fake_java(cls.side)
        import java
        api = java.jclass("org.telegram.messenger.zephyr.plugins.PluginChatApi")
        cls.profile = None
        cls.photo = None

        def get_user_profile(plugin_id, user_id, account):
            cls.side.require(plugin_id, "chat.read")
            cls.side.calls.append(("getUserProfile", plugin_id, user_id, account))
            return cls.profile

        def download_profile_photo(plugin_id, user_id, small, account, timeout_s):
            cls.side.require(plugin_id, "chat.read")
            cls.side.calls.append(("downloadProfilePhoto", plugin_id, user_id, small, account, timeout_s))
            if isinstance(cls.photo, Exception):
                raise cls.photo
            return cls.photo

        api.getUserProfile = get_user_profile
        api.downloadProfilePhoto = download_profile_photo
        sys.path.insert(0, str(PYTHON_ROOT))
        import zephyr_host
        from zephyr import client
        cls.side.host = zephyr_host
        cls.client = client
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.tmp.name) / "profile_test.plugin"
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
        self.side.permissions["profile_test"] = {"chat.read"}
        type(self).profile = None
        type(self).photo = None
        self.side.load("profile_test", self.path, self.tmp.name)

    def tearDown(self):
        self.side.unload("profile_test")

    def call(self, fn, *args, **kwargs):
        from zephyr import _state
        token = _state.current.set(_state.plugins["profile_test"])  # inside a callback of the plugin
        try:
            return self.side.with_identity("profile_test", lambda: getattr(self.client, fn)(*args, **kwargs))
        finally:
            _state.current.reset(token)

    def test_feature_flag(self):
        self.assertTrue(self.client.USER_PROFILE)

    def test_permission_required(self):
        self.side.permissions["profile_test"] = set()
        with self.assertRaises(PermissionError):
            self.call("get_user_profile", 42)
        with self.assertRaises(PermissionError):
            self.call("download_profile_photo", 42)
        self.assertEqual(self.side.calls, [])

    def test_unknown_user(self):
        self.assertIsNone(self.call("get_user_profile", 42))
        self.assertEqual(self.side.calls, [("getUserProfile", "profile_test", 42, 0)])

    def test_full_profile(self):
        type(self).profile = json.dumps(FULL)
        self.assertEqual(self.call("get_user_profile", "42", account=2), FULL)
        self.assertEqual(self.side.calls, [("getUserProfile", "profile_test", 42, 2)])

    def test_bio_not_loaded_and_empty_values(self):
        type(self).profile = json.dumps({"id": 7, "first_name": None, "last_name": None, "username": None,
                                         "bio": None, "bio_loaded": False})
        p = self.call("get_user_profile", 7)
        self.assertIsNone(p["bio"])
        self.assertFalse(p["bio_loaded"])
        self.assertEqual((p["first_name"], p["last_name"], p["username"]), ("", "", None))
        self.assertEqual((p["is_premium"], p["has_photo"], p["is_bot"], p["is_self"]), (False, False, False, False))

    def test_empty_bio_stays_empty_and_empty_username_is_none(self):
        type(self).profile = json.dumps(dict(FULL, bio="", username=""))
        p = self.call("get_user_profile", 42)
        self.assertEqual(p["bio"], "")
        self.assertIsNone(p["username"])

    def test_download_path_and_arguments(self):
        type(self).photo = "/cache/42.jpg"
        self.assertEqual(self.call("download_profile_photo", 42), "/cache/42.jpg")
        self.assertEqual(self.call("download_profile_photo", 42, small=0, account=1, timeout_s="30"), "/cache/42.jpg")
        self.assertEqual(self.side.calls, [("downloadProfilePhoto", "profile_test", 42, True, 0, 20),
                                           ("downloadProfilePhoto", "profile_test", 42, False, 1, 30)])

    def test_download_none_and_failure(self):
        self.assertIsNone(self.call("download_profile_photo", 42))
        type(self).photo = ValueError("timeout")
        self.assertIsNone(self.call("download_profile_photo", 42))


class JavaSideChecks(unittest.TestCase):
    def test_java_api(self):
        root = Path(__file__).resolve().parents[1]
        s = (root / "TMessagesProj/src/main/java/org/telegram/messenger/zephyr/plugins/PluginChatApi.java").read_text("utf-8")
        prof = s[s.index("public static String getUserProfile("):s.index("public static String downloadProfilePhoto(")]
        self.assertIn('requirePermission(id, "chat.read")', prof)
        self.assertIn("onUiAndWait(", prof)
        self.assertIn("loadFullUser(u, 0, false)", prof)
        self.assertIn("FULL_USER_RETRY_MS", prof)
        self.assertNotIn("notMainThread()", prof)  # cache read, safe on the UI thread
        dl = s[s.index("public static String downloadProfilePhoto("):][:2500]
        self.assertIn('requirePermission(id, "chat.read")', dl)
        self.assertIn("notMainThread();", dl)
        self.assertIn("Math.min(60, timeoutS)", dl)


if __name__ == "__main__":
    unittest.main()
