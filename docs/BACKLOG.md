# Backlog: what the plugin side needs from the app

Sizes: S = under a day, M = a few days, L = a week or more. Estimates are from reading the current code (base branch `ccr-40da9ed7-07luj0`, 4.5k lines in `messenger/zephyr/plugins`).

1. ~~**Render `CHAT_ACTION` in the chat header menu**~~ - **done in 0.2.5**. `CHAT_ACTION` is rendered in the chat header menu and `PROFILE_ACTION` in the profile header menu (`PluginHeaderMenu`, re-evaluated each time the menu opens; context keys `account`, `dialog_id`, `message=None`, `fragment`). `DRAWER` remains the same pattern in the main drawer (S, not requested).

2. **Multi-file plugins to beat the 512 KB limit** - **L**. The whole pipeline assumes one source string: `PluginManifest.MAX_SOURCE_BYTES` (`PluginManifest.java:32`), manifest parsed from that string, one file per plugin in `PluginManager`, one sha256 per plugin and one `size` per index version (`PluginRepository.java:180,198`, `PluginManager.java:400`). Needs a package format (zip or file list), per-file hashes in the signed index, a size budget, loading modules from the plugin dir on the Chaquopy path, update and rollback of a set of files, and a security review (path traversal, which file carries the permissions). Cheaper stopgap: compressed single file or raising the limit - **S**, but it does not scale.

3. **Inline hook** - **S (remaining)**. Already implemented on `release/0.2.4` (`on_inline_result_send`, `InlineAction`, `client.send_media`, docs `docs/plugin-inline-media.md`). Remaining: release 0.2.4 and merge it into the integration branch; device testing is **unknown**. Any new inline behaviour (for example editing the result rather than cancelling or sending as own) would be M.

4. **Memory-style client** - **S (remaining)**. Transport and `memory.style` permission are implemented on `release/0.2.4` (`d3d87f7`, `e263db4`, docs `docs/plugin-memory-style-api.md`); the server contract lives in another repository (ZephyrGram-AI). Remaining: release, device test with a real server (**unknown**), and optionally a native UI for style/facts (M).
