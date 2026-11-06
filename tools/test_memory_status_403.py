"""v1 /status 403 keeps the server's fixed code (memory_token_not_owned / memory_account_mismatch). Run: python3 tools/test_memory_status_403.py"""
import unittest, pathlib
P = pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/java/org/telegram/messenger/zephyr/ZephyrMemoryClient.java"
S = P.read_text()
class T(unittest.TestCase):
    def test_status_403_uses_style_error(self):
        self.assertIn('if (status == 403 && "/status".equals(path)) throw styleError(status, errorCode(connection));', S)
    def test_order_before_generic_mapping(self):
        a = S.index('"/status".equals(path)) throw styleError')
        b = S.index('if (status == 401 || status == 403) throw error(ZephyrAiClient.ERR_UNAUTHORIZED, "memory_not_authorized")')
        self.assertLess(a, b)
    def test_style_error_codes(self):
        self.assertIn('"telegram_owned_token_required".equals(code) ? error(ZephyrAiClient.ERR_UNAUTHORIZED, "memory_token_not_owned")', S)
        self.assertIn('"memory_account_mismatch".equals(code) ? error(ERR_ACCOUNT, "memory_account_mismatch")', S)
    def test_other_v1_statuses_unchanged(self):
        self.assertIn('if (status == 404) throw error(ERR_UNSUPPORTED, "memory_not_supported")', S)
        self.assertIn('if (status == 409) throw error(ERR_CONSENT, "memory_consent_required")', S)
if __name__ == "__main__":
    unittest.main()
