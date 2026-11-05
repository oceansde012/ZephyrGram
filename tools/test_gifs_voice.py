"""client.get_saved_gifs and send_media(kind="voice") (0.2.7). Cache reads, the voice document (duration, waveform)
and the Ogg/Opus checks live in PluginChatApi / PluginMediaPolicy (Java). Run: python3 tools/test_gifs_voice.py"""
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_inline_media import JavaSide, PYTHON_ROOT, install_fake_java  # noqa: E402

PLUGIN = '''
from zephyr import BasePlugin, client
__id__ = "media_test"
__name__ = "Media test"

class Plugin(BasePlugin):
    def on_plugin_load(self):
        pass
'''


class GifsAndVoice(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.side = JavaSide()
        install_fake_java(cls.side)
        import java
        api = java.jclass("org.telegram.messenger.zephyr.plugins.PluginChatApi")
        cls.gifs_json = "[]"

        def get_saved_gifs(plugin_id, limit, account):
            cls.side.require(plugin_id, "chat.read")
            cls.side.calls.append(("getSavedGifs", plugin_id, limit, account))
            return cls.gifs_json

        api.getSavedGifs = get_saved_gifs
        sys.path.insert(0, str(PYTHON_ROOT))
        import zephyr_host
        from zephyr import client
        cls.side.host = zephyr_host
        cls.client = client
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.tmp.name) / "media_test.plugin"
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
        self.side.permissions["media_test"] = {"chat.read", "media.send"}
        type(self).gifs_json = "[]"
        self.side.load("media_test", self.path, self.tmp.name)

    def tearDown(self):
        self.side.unload("media_test")

    def call(self, fn, *args, **kwargs):
        from zephyr import _state
        token = _state.current.set(_state.plugins["media_test"])  # inside a callback of the plugin
        try:
            return self.side.with_identity("media_test", lambda: getattr(self.client, fn)(*args, **kwargs))
        finally:
            _state.current.reset(token)

    # GIF methods: first draft by Mistral (codestral); most of it was rewritten (wrong call style and flag test).
    def test_flags(self):
        self.assertTrue(self.client.SAVED_GIFS)
        self.assertTrue(self.client.SEND_VOICE)

    def test_gifs_permission(self):
        self.side.permissions["media_test"] = set()
        with self.assertRaises(PermissionError):
            self.call("get_saved_gifs")
        self.assertEqual(self.side.calls, [])

    def test_gifs_defaults_and_arguments(self):
        self.assertEqual(self.call("get_saved_gifs"), [])
        self.call("get_saved_gifs", "20", account=3)
        self.assertEqual(self.side.calls, [("getSavedGifs", "media_test", 100, 0),
                                           ("getSavedGifs", "media_test", 20, 3)])

    def test_gifs_normalisation(self):
        type(self).gifs_json = ('[{"id": "1", "mime": null, "duration": "2.5"}, '
                                '{"id": 2, "mime": "video/mp4", "w": 320, "h": 240, "duration": 3}]')
        self.assertEqual(self.call("get_saved_gifs"), [
            {"id": 1, "mime": "", "w": 0, "h": 0, "duration": 2.5},
            {"id": 2, "mime": "video/mp4", "w": 320, "h": 240, "duration": 3.0}])

    def test_send_voice_passes_kind(self):
        (Path(self.tmp.name) / "reply.ogg").write_bytes(b"OggS" + b"\0" * 60)
        self.call("send_media", 777, "reply.ogg", kind="VOICE", reply_to=5)
        self.side.drain()
        sent = [c for c in self.side.calls if c[0] == "sendMedia"]
        self.assertEqual(len(sent), 1)
        self.assertEqual(sent[0][2], 777)
        self.assertTrue(sent[0][3].endswith("reply.ogg"))
        self.assertEqual(sent[0][4], "voice")
        self.assertEqual(sent[0][6], 5)

    def test_unknown_kind_still_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            self.call("send_media", 777, "reply.ogg", kind="audio")
        self.assertIn("voice", str(ctx.exception))


class JavaSideChecks(unittest.TestCase):
    def test_java_api(self):
        root = Path(__file__).resolve().parents[1]
        s = (root / "TMessagesProj/src/main/java/org/telegram/messenger/zephyr/plugins/PluginChatApi.java").read_text("utf-8")
        gifs = s[s.index("public static String getSavedGifs("):][:2000]
        self.assertIn('requirePermission(id, "chat.read")', gifs)
        self.assertIn('stickerLoadDue(account + ":gifs")', gifs)
        voice = s[s.index("private static TLRPC.TL_documentAttributeAudio voiceAttribute("):][:1800]
        self.assertIn("MediaController.isOpusFile(path) != 1", voice)
        self.assertIn("MediaController.getWaveform(path)", voice)
        self.assertIn("a.voice = true;", voice)
        send = s[s.index("public static String sendMedia("):s.index("private static TLRPC.TL_documentAttributeAudio voiceAttribute(")]
        self.assertIn('voice.mime_type = "audio/ogg";', send)
        # The native decode runs on the worker thread, before the UI-thread send.
        self.assertLess(send.index("voiceAttribute(filePath)"), send.index("AndroidUtilities.runOnUIThread"))


if __name__ == "__main__":
    unittest.main()
