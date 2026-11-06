import unittest, pathlib
UI = pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/java/org/telegram/ui"
def src(n): return (UI / (n + ".java")).read_text()
class T(unittest.TestCase):
    def test_previews_use_theme_and_config(self):
        s = src("ZephyrPreviews")
        for k in ("key_chat_inBubble", "key_chat_outBubble", "key_avatar_backgroundRed", "stickerSize", "hideStickerTime", "avatarCorners", "tabStyle", "tabCounter", "fabShape"):
            self.assertIn(k, s, k)
    def test_screens_embed_previews(self):
        self.assertIn("ZephyrPreviews.Bubbles", src("ZephyrChatsSettingsActivity"))
        self.assertIn("ZephyrPreviews.Sticker", src("ZephyrMediaSettingsActivity"))
        a = src("ZephyrAppearanceSettingsActivity")
        for c in ("TabStyles", "Fab", "Avatars"):
            self.assertIn("ZephyrPreviews." + c, a)
    def test_live_sliders(self):
        self.assertIn("sticker.refresh()", src("ZephyrMediaSettingsActivity"))
        self.assertIn("avatars.refresh()", src("ZephyrAppearanceSettingsActivity"))
    def test_geometry(self):
        # mirrors ZephyrPreviews.stickerSidePx / avatarRadiusPx clamping
        self.assertIn("Math.max(50, Math.min(150, percent))", src("ZephyrPreviews"))
        self.assertIn("Math.max(0, Math.min(100, percent))", src("ZephyrPreviews"))

class TabsLayout(unittest.TestCase):
    def test_tabs_preview_measured_layout(self):
        s = src("ZephyrPreviews")
        self.assertIn("measureText(labels[k])", s)
        self.assertIn("HEIGHT_DP", s)
        self.assertNotIn("3 * 92 + 16", s)
        self.assertNotIn("3 * 92 + 16", src("ZephyrAppearanceSettingsActivity"))
        self.assertIn("Theme.key_divider", s)          # unselected cards keep a light border
        self.assertIn("i == selected ? 2 : 1", s)

if __name__ == "__main__":
    unittest.main()
