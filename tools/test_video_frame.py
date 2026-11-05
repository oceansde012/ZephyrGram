"""client.video_frame (0.2.7): chat.read, argument passing and failure handling. Frame extraction, the thumbnail
fallback and the frames folder live in PluginChatApi.videoFrame (Java). Run: python3 tools/test_video_frame.py"""
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_inline_media import JavaSide, PYTHON_ROOT, install_fake_java  # noqa: E402

PLUGIN = '''
from zephyr import BasePlugin, client
__id__ = "frame_test"
__name__ = "Frame test"

class Plugin(BasePlugin):
    def on_plugin_load(self):
        pass
'''


class VideoFrame(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.side = JavaSide()
        install_fake_java(cls.side)
        import java
        api = java.jclass("org.telegram.messenger.zephyr.plugins.PluginChatApi")
        cls.frame = None

        def video_frame(plugin_id, dialog_id, message_id, account, at_ms, timeout_s):
            cls.side.require(plugin_id, "chat.read")
            cls.side.calls.append(("videoFrame", plugin_id, dialog_id, message_id, account, at_ms, timeout_s))
            if isinstance(cls.frame, Exception):
                raise cls.frame
            return cls.frame

        api.videoFrame = video_frame
        sys.path.insert(0, str(PYTHON_ROOT))
        import zephyr_host
        from zephyr import client
        cls.side.host = zephyr_host
        cls.client = client
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.tmp.name) / "frame_test.plugin"
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
        self.side.permissions["frame_test"] = {"chat.read"}
        type(self).frame = None
        self.side.load("frame_test", self.path, self.tmp.name)

    def tearDown(self):
        self.side.unload("frame_test")

    def call(self, fn, *args, **kwargs):
        from zephyr import _state
        token = _state.current.set(_state.plugins["frame_test"])  # inside a callback of the plugin
        try:
            return self.side.with_identity("frame_test", lambda: getattr(self.client, fn)(*args, **kwargs))
        finally:
            _state.current.reset(token)

    # Methods below: first draft by Mistral (codestral), checked and corrected.
    def test_video_frame_flag(self):
        self.assertTrue(self.client.VIDEO_FRAME)

    def test_video_frame_permission(self):
        self.side.permissions["frame_test"] = set()
        with self.assertRaises(PermissionError):
            self.call("video_frame", 1, 2)
        self.assertEqual(self.side.calls, [])

    def test_video_frame_path_returned_with_defaults(self):
        type(self).frame = "/cache/zephyr_frames/frame.jpg"
        self.assertEqual(self.call("video_frame", 1, 2), "/cache/zephyr_frames/frame.jpg")
        self.assertEqual(self.side.calls, [("videoFrame", "frame_test", 1, 2, 0, -1, 20)])

    def test_video_frame_none(self):
        self.assertIsNone(self.call("video_frame", 1, 2))

    def test_video_frame_exception_none(self):
        type(self).frame = ValueError("decoder failed")
        self.assertIsNone(self.call("video_frame", 1, 2))

    def test_video_frame_at_ms(self):
        type(self).frame = "p"
        for value, expected in ((None, -1), (-10, -1), (0, 0), (1500, 1500), ("250", 250)):
            self.side.calls.clear()
            self.call("video_frame", 1, 2, at_ms=value)
            self.assertEqual(self.side.calls[0][5], expected, value)

    def test_video_frame_ids_account_timeout(self):
        type(self).frame = "p"
        self.call("video_frame", "-100123", "2", account=1, timeout_s="30")
        self.assertEqual(self.side.calls, [("videoFrame", "frame_test", -100123, 2, 1, -1, 30)])


class JavaSideChecks(unittest.TestCase):
    def test_java_api(self):
        root = Path(__file__).resolve().parents[1]
        s = (root / "TMessagesProj/src/main/java/org/telegram/messenger/zephyr/plugins/PluginChatApi.java").read_text("utf-8")
        body = s[s.index("public static String videoFrame("):s.index("private static void pruneFrames(")]
        self.assertIn('requirePermission(id, "chat.read")', body)
        self.assertIn("notMainThread();", body)
        self.assertIn("isEncryptedDialog(dialogId)) return null", body)
        self.assertIn("VIDEO_FRAME_SIDE = 512", s)
        self.assertIn("encodeVideoFrame(vf, VIDEO_FRAME_SIDE, atMs", body)
        self.assertIn("pickSize(doc.thumbs", body)
        # Never downloads the video itself: only thumbnails go through FileLoader.
        self.assertNotIn("fl.loadFile(doc", body)


if __name__ == "__main__":
    unittest.main()
