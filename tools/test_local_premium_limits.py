"""Static checks: Local Premium must not raise server-enforced limits or surface premium-only server errors."""
import pathlib
import re
import unittest

SRC = pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/java/org/telegram"


def read(rel):
    return (SRC / rel).read_text(encoding="utf-8")


class LocalPremiumLimits(unittest.TestCase):
    def test_real_premium_defined(self):
        s = read("messenger/UserConfig.java")
        self.assertRegex(s, r"public boolean isRealPremium\(\)\s*\{[^}]*user\.premium;")

    def test_server_limits_use_real_premium(self):
        checks = {
            "messenger/MessagesController.java": ["messageLengthLimitPremium", "captionLengthLimitPremium",
                                                   "aboutLengthLimitPremium", "reactionsUserMaxPremium",
                                                   "dialogFiltersLimitDefault"],
            "messenger/FileUploadOperation.java": ["uploadMaxFilePartsPremium"],
                        "messenger/SavedMessagesController.java": ["savedDialogsPinnedLimitPremium"],
            "ui/Stories/StoriesController.java": ["storyCaptionLengthLimitPremium", "storyExpiringLimitPremium"],
            "ui/DialogsActivity.java": ["dialogFiltersPinnedLimitPremium"],
        }
        for rel, markers in checks.items():
            s = read(rel)
            for m in markers:
                self.assertRegex(s, r"isRealPremium\(\)[^;]{0,300}" + re.escape(m),
                                 f"{rel}: {m} must be chosen by isRealPremium()")
                self.assertNotRegex(s, r"\bisPremium\(\)[^;]{0,300}" + re.escape(m),
                                    f"{rel}: {m} still chosen by isPremium()")
        self.assertIn("boolean premium = AccountInstance.getInstance(currentAccount).getUserConfig().isRealPremium()",
                      read("messenger/FileLoader.java"))

    def test_premium_errors_suppressed(self):
        cfg = read("messenger/zephyr/ZephyrConfig.java")
        self.assertIn("public static boolean isLocalPremiumError(int account, String errorText)", cfg)
        alerts = read("ui/Components/AlertsCreator.java")
        m = re.search(r"public static Dialog processError\(int currentAccount.*?\{(.*?)BALANCE_TOO_LOW", alerts, re.S)
        self.assertIsNotNone(m)
        self.assertIn("isLocalPremiumError(currentAccount, error.text)", m.group(1))
        self.assertEqual(read("ui/Components/BulletinFactory.java").count("isLocalPremiumError("), 3)


class DisableAdsDefault(unittest.TestCase):
    def test_ads_hidden_by_default(self):
        cfg = read("messenger/zephyr/ZephyrConfig.java")
        self.assertIn("public static volatile boolean disableAds = true;", cfg)
        self.assertIn('p.getBoolean("disableAds", true)', cfg)
        self.assertIn("setChecked(ZephyrConfig.disableAds)", read("ui/ZephyrOtherSettingsActivity.java"))


if __name__ == "__main__":
    unittest.main()
