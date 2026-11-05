# ZephyrGram: current state

Snapshot taken 2026-11-05 from GitHub (`oceansde012/ZephyrGram`) and the code of branch `ccr-40da9ed7-07luj0` (head `c6006d1`). Anything not verifiable from the repo or GitHub is marked **unknown**.

## Release status

| Item | State |
| --- | --- |
| Latest public release | **v0.2.3**, published 2026-11-04 21:28 UTC, built by workflow run #15 (manual dispatch on `release/0.2.3` @ `4075ba1`, success). Tag `v0.2.3` -> `4075ba1`. |
| Previous releases | 0.2.2 (2026-11-03, tag `0.2.2`, no `v`), 0.2.1, 0.2.0, 0.1.5 ... 0.1.2 |
| **0.2.4 (version code 10)** | **Not released.** No `v0.2.4` tag and no GitHub Release exist. `release/0.2.4` @ `348ea7d` ("Bump version to 0.2.4 (code 10)") was built by workflow run #17 (`workflow_dispatch`, 2026-11-05 11:33-12:06 UTC): **conclusion success**, `publish` not set, so it produced only a preview artifact `ZephyrGram-preview-0.2.4-42019979732` (50.3 MB, sha256 `0a1cdd32...7512bb`), expires 2026-11-08 12:06 UTC (3-day retention). Run: https://github.com/oceansde012/ZephyrGram/actions/runs/42019979732 |
| 0.2.4 on a device | **unknown** (no evidence in the repo or GitHub) |
| 0.2.4 content | 12 commits over 0.2.3: `inline.hook` + `media.send` permissions, `on_inline_result_send` hook, `client.send_media`, `memory.style` client (style/feedback/facts/digest/area deletion), docs `plugin-inline-media.md` and `plugin-memory-style-api.md`. `feature/inline-hook` (`e30d2bc`) and `feature/memory-style-client` (`e662da1`) are both fully contained in `release/0.2.4`. |
| `gradle.properties` on the base branch | still `ZEPHYR_VERSION_NAME=0.2.2`, `ZEPHYR_VERSION_CODE=8`. 0.2.3 and 0.2.4 version bumps live only on their release branches. |

To publish 0.2.4: re-run the Release workflow (`workflow_dispatch`) on `release/0.2.4` with `version=0.2.4`, `publish=true`, or push tag `v0.2.4`. Not done here.

## Branches and pull requests

Default branch `main` is **one commit** ("Initial commit", README only, `5863231`). It has never received the code. All real work is on `ccr-40da9ed7-07luj0` ("integration branch", `c6006d1`); releases are cut from release branches, not from `main`.

Open PRs:

| PR | Head -> base | State |
| --- | --- | --- |
| #1 Import DrKLO/Telegram 13.1.1 (7237) as the Android base | `ccr-1b036d8c-3qrrtq` (`ab759db`, v0.1.5) -> `main` | open, not merged. Stale: contained in the integration branch history. |
| #2 ZephyrGram 0.2.x: plugin UI, settings categories, chat plugins menu, upstream watch | `ccr-40da9ed7-07luj0` -> `main` | open, not merged. Merging it is what would put the code on `main`. |
| #4 Keep source account and owner when selecting plugin chats | `feature/picker-account-context` (`b0f2fa0`) -> `ccr-40da9ed7-07luj0` | **draft**, open. Content is already in `release/0.2.3` (and so in 0.2.3 and 0.2.4) but NOT in the integration branch. |
| #3 account-bound memory bridge + preview APK build | `feature/neon-memory-bridge` -> `ccr-40da...` | merged 2026-11-03 |

Branches (ahead of the integration branch `c6006d1`):

| Branch | Ahead | Status |
| --- | --- | --- |
| `release/0.2.3` `4075ba1` | 7 | Released as v0.2.3. Merge of `fix/review-security` + `feature/picker-account-context`. Not merged back into the integration branch. |
| `release/0.2.4` `348ea7d` | 18 | Contains everything in 0.2.3 plus inline-hook and memory-style. Preview built, not released, not merged back. |
| `fix/review-security` `f5b8a54` | 5 | Plugin identity binding, `http_get` https-only/public-only, AI client hardening, release workflow branch trigger dropped. In 0.2.3 and 0.2.4; no PR. |
| `feature/inline-hook` `e30d2bc` | 12 | In 0.2.4 only; no PR. |
| `feature/memory-style-client` `e662da1` | 10 | In 0.2.4 only; no PR. |
| `feature/picker-account-context` | 1 | PR #4 draft. |
| `feature/neon-memory-bridge` | 0 | merged (PR #3). |
| `main` | behind | initial commit only. |

Local-only branches/worktrees seen on the dev machine (not on origin, status **unknown**): `feature/approval-bot`, `feature/gui-ai-hub`, `feature/media-library`, `feature/profile-context`, `feature/video-voice`, `client-nav`.

Net: the integration branch is behind both release branches. Anything built from it is older than v0.2.3.

## Builds and releases

- Single workflow `.github/workflows/release.yml` ("Release"), plus `upstream-watch.yml` (daily check of DrKLO/Telegram, opens an `upstream` issue; base commit in `UPSTREAM_BASE`).
- Triggers: push of a tag `v*` (publishes a GitHub Release), manual `workflow_dispatch` with inputs `version` (required) and `publish` (default **false**), and, on the integration branch only, a push to `feature/neon-memory-bridge` (preview, fixed version 0.2.2). `release/0.2.3` removed the branch push trigger (`09fdbfd`); whether the 0.2.4 workflow still has it is not checked here.
- Build: Ubuntu runner, JDK 17, Python 3.13 (Chaquopy bytecode), Android SDK 36 / NDK 27.2.12479018 / CMake 3.22.1, `assembleAfatRelease` limited to **arm64-v8a** via an init script. ~35 minutes. The APK is signed with the release keystore from secrets and verified with `apksigner`; `signing-certificate-sha256.txt`, `build-info.txt` and `SHA256SUMS.txt` are produced.
- Preview (publish=false): artifact `ZephyrGram-preview-<ver>-<run_id>`, 3 days. Publish: `softprops/action-gh-release` with `ZephyrGram-<ver>-arm64-v8a.apk`, generated notes; versions with `-` become pre-releases (skipped by the in-app updater, which reads `releases/latest`).
- Version: `ZEPHYR_VERSION_NAME` comes from the tag/input; `ZEPHYR_VERSION_CODE` in `gradle.properties` must be raised by hand. APK versionCode = `ZEPHYR_VERSION_CODE*100000 + APP_VERSION_CODE*10 + abi`.
- Secrets (names only): `ZEPHYR_KEYSTORE_BASE64`, `ZEPHYR_KEYSTORE_PASSWORD`, `ZEPHYR_KEY_ALIAS`, `ZEPHYR_KEY_PASSWORD`, `TG_API_ID`, `TG_API_HASH`. Set without a trailing newline.
- Local: `scripts/build-android.sh`, `scripts/init-submodules.sh`, Windows helpers `configure-windows.ps1` / `build-windows.ps1`. Needs own `TG_API_ID`/`TG_API_HASH`.
- Past failures worth knowing: runs #1 (setup-android used a removed `sdkmanager tools`), #7 and #8 (compile errors, fixed in the next commit), several cancelled by concurrency.

## Docs in the repo

- Base branch: `README.md`, `docs/ux-plan.md`.
- `release/0.2.4` only: `docs/plugin-inline-media.md`, `docs/plugin-memory-style-api.md` (Russian). No `docs/plugin-*.md` exist on the base branch.

## Plugin runtime and API surfaces

Chaquopy Python runtime, single-file `.plugin` modules (dunder metadata parsed in Java without running Python, `PluginManifest.java`), Ed25519-signed store index, permissions (`network.requests`, `updates.read`, `messages.send`, `ui.menu`, `internet`, `java.raw`, `ai.complete`, `chat.read`, plus `ai.memory`, `memory.style`, `inline.hook`, `media.send` on later branches). Source limit `MAX_SOURCE_BYTES = 2 * 1024 * 1024` since 0.2.5 (code 11) (`PluginManifest.java`, enforced in `PluginManifest.parse`, `PluginManager.java`, `PluginRepository.verifySignedIndex`/`download`, `zephyr_host.py`). Apps <= 0.2.4 are limited to 512 KB and reject a whole index containing a larger version, so the store publishes a second signed index `index-v2.json` (+ `.sig`, same key and schema) with versions up to 2 MiB, while `index.json` keeps only <= 512 KB versions. 0.2.5 fetches `index-v2.json` first (primary then mirror) and falls back to `index.json`; rollback state is kept per index (`highest_sequence_v2` / `highest_hash_v2`).

### Menu surfaces (`PluginMenus`, Python `zephyr.MenuType`, permission `ui.menu`)

Python side: `MenuType.DRAWER=0, MESSAGE_CONTEXT=1, CHAT_ACTION=2, PROFILE_ACTION=3` (`TMessagesProj/src/main/python/zephyr/__init__.py:41-45`), registered with `BasePlugin.add_menu_item` (`__init__.py:143`). Java mirrors the types in `PluginMenus.java:28-31`.

**Wired in the app: `MESSAGE_CONTEXT`, `CHAT_ACTION`, `PROFILE_ACTION` (0.2.5); `DRAWER` has no consumer yet.**

- `MESSAGE_CONTEXT`: `ChatActivity.java` builds the message long-press menu from `PluginMenus.getItems(TYPE_MESSAGE_CONTEXT, ...)` and routes clicks of plugin item ids to `PluginMenus.onClick` (`processSelectedOption`).
- `CHAT_ACTION`: rendered in the chat header "three dots" menu (private chats, groups, supergroups, channels; `chatMode == MODE_DEFAULT`). `ChatActivity` sub-menu delegate `onShowSubMenu` calls `PluginHeaderMenu.sync(headerItem, TYPE_CHAT_ACTION, ...)` every time the menu opens, so conditions and texts (for example an on/off toggle) are re-evaluated; clicks go through the header `onItemClick` to `PluginHeaderMenu.onClick`.
- `PROFILE_ACTION`: the same in `ProfileActivity` (`otherItem` sub-menu delegate and `onItemClick`).
- Shared helper `messenger/zephyr/plugins/PluginHeaderMenu.java`: adds a sub-item per item (ids from the reserved plugin range `PluginMenus.ID_BASE..ID_MAX`), hides items whose condition is false or whose plugin was removed/disabled, never throws.
- Context dict given to Python `condition(ctx)` / `on_click(ctx)` (built in `zephyr_host.py::_context`): `account` (int), `dialog_id` (int; user id for private chats, negative id for groups/channels; for profiles the profile's user id or `-chat_id`), `message` (`None` for CHAT_ACTION/PROFILE_ACTION, the `MessageObject` for MESSAGE_CONTEXT), `fragment`, `context`.
- `TYPE_DRAWER` still has no consumer: it appears only as a constant (`PluginMenus.java`) and in the Python enum. `PluginMenus.hasItems(type)` has no callers.
- Unrelated to `PluginMenus`: the chat header "Plugins" entry (`ChatActivity.java:4533`, handler `:3981`, `openZephyrPluginsMenu` `:46277`) opens `ZephyrPluginsQuickMenu`, which lists plugins from `PluginManager`, not menu items.

Other API (details in the 0.2.4 docs): `client.*` chat/AI calls, settings pages (`create_settings(page)`, `client.SETTINGS_PAGES`), `add_inline_result_hook` / `InlineAction` / `client.send_media` (0.2.4), `memory.style` client (0.2.4), `ai_memory_*` (0.2.2).

## Known gaps

- Message filters: README says "not implemented".
- "Local Premium" and "Disable ads" toggles exist but have no effect.
- App icon rebrand not done. FCM push disabled.
- README states features were not tested on a device; current device-test status **unknown**.
