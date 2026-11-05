"""Static checks for the 2 MiB plugin limit and the index-v2 transport.
Run: python3 tools/test_plugin_size_v2.py (no Android, no network)."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main"
PLUGINS = ROOT / "java/org/telegram/messenger/zephyr/plugins"
MANIFEST = (PLUGINS / "PluginManifest.java").read_text(encoding="utf-8")
REPO = (PLUGINS / "PluginRepository.java").read_text(encoding="utf-8")
RUNTIME = (PLUGINS / "PluginRuntime.java").read_text(encoding="utf-8")
HOST = (ROOT / "python/zephyr_host.py").read_text(encoding="utf-8")


class PluginSizeV2(unittest.TestCase):
    def test_java_limit_is_2_mib(self):
        self.assertIn("MAX_SOURCE_BYTES = 2 * 1024 * 1024;", MANIFEST)
        self.assertNotIn("512 * 1024", MANIFEST.split("public final class")[1])

    def test_python_host_limit_is_2_mib(self):
        self.assertIn("len(source) > 2 * 1024 * 1024", HOST)
        self.assertIn("larger than 2 MB", HOST)
        self.assertNotIn("512 * 1024", HOST)
        self.assertNotIn("512 KB", HOST + RUNTIME)

    def test_refresh_tries_v2_before_v1(self):
        body = REPO.split("public synchronized PluginIndex refreshSync()")[1].split("private static String seqKey")[0]
        self.assertLess(body.index('"index-v2.json"'), body.index('"index.json"'))
        fetch = REPO.split("private static Fetched fetchSigned")[1].split("private void loadCacheLocked")[0]
        self.assertLess(fetch.index("BASE + file"), fetch.index("FALLBACK_BASE + file"))
        self.assertIn('file + ".sig"', fetch)
        self.assertIn("verifySignedIndex(raw, sig, PUBLIC_KEYS)", fetch)

    def test_separate_rollback_keys(self):
        for key in ("highest_sequence_v2", "highest_hash_v2", "highest_sequence", "highest_hash", "cache_index"):
            self.assertIn('"%s"' % key, REPO)
        load = REPO.split("private void loadCacheLocked()")[1].split("public static PluginIndex verifySignedIndex")[0]
        self.assertIn("seqKey(cachedName)", load)
        self.assertIn("hashKey(cachedName)", load)
        refresh = REPO.split("public synchronized PluginIndex refreshSync()")[1].split("private static String seqKey")[0]
        self.assertIn("seqKey(indexName)", refresh)
        self.assertIn("hashKey(indexName)", refresh)

    def test_no_downgrade_to_v1_after_v2(self):
        refresh = REPO.split("public synchronized PluginIndex refreshSync()")[1].split("private static String seqKey")[0]
        catch = refresh.split("catch (Exception v2Failure)")[1].split('fetchSigned("index.json")')[0]
        self.assertIn('prefs().getLong("highest_sequence_v2", -1) >= 0', catch)
        self.assertIn("throw v2Failure", catch)

    def test_security_checks_kept(self):
        self.assertIn("v.size > PluginManifest.MAX_SOURCE_BYTES", REPO)
        self.assertIn("validateDownloadUrl(entry.id, v.url)", REPO)
        self.assertIn("Plugin index rollback was rejected", REPO)
        self.assertIn("Conflicting plugin index sequence was rejected", REPO)
        self.assertTrue(re.search(r"raw\.length != allowed\.size \|\| !sha256\(raw\)", REPO))


if __name__ == "__main__":
    unittest.main()
