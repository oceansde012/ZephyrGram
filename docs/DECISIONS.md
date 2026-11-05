# Decision log

Reconstructed from `git log` (branch `ccr-40da9ed7-07luj0` plus release branches). Dates are commit dates. Status "accepted" means the code and workflow still reflect it. The reasons are taken from commit messages; where a commit does not say why, the reason is marked **unknown**.

## ADR-001 Base on DrKLO/Telegram 13.1.1 and track upstream by hand (2026-10-30, 2026-10-31)
Import official Telegram for Android (`81820ef`), keep the base commit in `UPSTREAM_BASE`, and run a daily workflow that opens an `upstream` issue when a new version/API layer appears (`13930a6`). Rebasing is manual. Status: accepted.

## ADR-002 Own package id and own version counter (2026-10-30)
Package `org.zephyrgram.messenger`, API credentials from the environment (`1454939`); `ZEPHYR_VERSION_NAME/CODE` separate from Telegram's `APP_VERSION_*`, versionCode = `ZEPHYR_VERSION_CODE*100000 + APP_VERSION_CODE*10 + abi` so it grows on every release and rebase (`0234b49`). Status: accepted.

## ADR-003 No secrets or upstream keys in the repo; sign with the owner's keystore (2026-10-30)
Remove Telegram's Google/Firebase keys and release keystore (`e4eb451`); keystore and `TG_API_*` come from env/secrets only. Consequence: FCM cannot be used with a custom package. Status: accepted.

## ADR-004 Push without FCM (2026-10-30)
Notifications run without Firebase (`9bdac5d`, `1454939`). Mechanism details **unknown** from the log. Status: accepted.

## ADR-005 Re-implement AyuGram-style features from scratch; port exteraGram settings under GPLv2 (2026-10-30)
Ghost mode by intercepting requests in `ConnectionsManager.sendRequestInternal` (`1d51a22`); saved deleted/edited messages (`45d61b1`); exteraGram settings ported with credits (`0234b49`, `9bdac5d`). The whole app is GPLv2. Status: accepted.

## ADR-006 Trim the build to two modules (2026-10-30)
Drop Huawei, HockeyApp, Standalone and Tests modules; build only `TMessagesProj` and `TMessagesProj_App` (`afdc54a`). Reason in log: resolve offline-friendly. Status: accepted.

## ADR-007 Plugins in Python via Chaquopy, signed index, single file (2026-10-30)
Plugin system with a Chaquopy runtime, Ed25519-signed store index and manager UI (`0234b49`); manifest read from dunder constants without executing Python; one `.plugin` source of at most 512 KB. Status: accepted; the single-file limit is a known constraint (see BACKLOG).

## ADR-008 Release by tag, arm64-v8a only, on GitHub Actions (2026-10-30)
Tag `v*` builds and publishes a signed arm64 APK; the in-app updater reads `releases/latest` (`0234b49`). SDK taken from the runner image because `setup-android` runs a removed `sdkmanager tools` (`8476386`). Status: accepted.

## ADR-009 Manual runs default to preview artifacts, not public releases (2026-11-03)
`workflow_dispatch` has `publish=false` by default and uploads a 3-day artifact; a public release needs `publish=true` or a tag (`e24cb4e`). A branch-push preview build of the memory bridge was added (`04f9002`) and later removed from release branches (`09fdbfd`). Status: accepted.

## ADR-010 Plugins hold a wake lock while answering (2026-10-31)
Timers stopped when the CPU slept; plugin handlers now run under a wake lock, and a `START` event is sent after an update (`6f6d421`). Status: accepted.

## ADR-011 Plugins auto-update by default (2026-10-31)
On by default, every 3 hours and on start, with a toast and status on the plugins screen (`1481394`). Status: accepted. Threat-model review of this default: **unknown**.

## ADR-012 Settings structure: short ZephyrGram top level with category screens; plugins reachable from chat and main menus (2026-10-31)
ZephyrGram entry moved to the top, plugins/auto-reply/AI first (`8ef78a9`), category screens like exteraGram (`f7ba251`, `120469a` for plugin `ui.Page`), plugins list/toggle in the main-screen menu (`4ccae4e`) and a Plugins window in the chat menu (`f7ba251`). Status: accepted.

## ADR-013 Auto-reply state is shared with the plugin through one write lock (2026-10-31)
The native auto-reply screen follows plugin-side changes and shares one write lock (`5448211`); the store remembers chats removed in-app so "answer everyone" does not re-add them (`1253d28`) and clears lore/notes of removed chats (`d12c2f8`). Status: accepted.

## ADR-014 Memory bridge is account-bound, opt-in, and never exposes the token to Python (2026-11-03)
Server URL and token are read only in native code; HTTPS only, no redirects, no automatic retries, `X-Zephyr-Account-Id` = owner of the selected account; texts and error bodies not logged; operations invalidated when a plugin is auto-disabled (`e24cb4e`, `f4091f1`, and `d3d87f7`/`e263db4` for the style layer behind a separate `memory.style` permission). Status: accepted.

## ADR-015 Security hardening of the plugin API (2026-11-04, release 0.2.3)
Bind API calls to the native current-plugin identity instead of trusting a caller-supplied id (`673d77b`); `http_get` is https-only and refuses non-public targets, including NAT64 mapped addresses (`a63e5f5`, `f5b8a54`); AI client checks IPv6 local hosts and sends no bearer token across redirects (`818781f`). Status: accepted.

## ADR-016 Picker keeps source account and owner (2026-11-04)
Plugin chat picker preserves the source account and owner across settings navigation (`b0f2fa0`). Status: accepted; PR #4 still a draft.

## ADR-017 Inline-result hook and native send_media as permissioned additions to API v1 (2026-11-05, 0.2.4)
`inline.hook` and `media.send` permissions, feature-detected via `client.INLINE_RESULT_HOOK` / `client.SEND_MEDIA`, `api_version()` stays 1 (`fad6b9b`, `2270e24`, `6168456`, `d54422d`). Status: accepted, unreleased.

## ADR-018 Releases are cut from release branches; `main` is not used (2026-10-31 onward)
Observed practice: `main` stays at the initial commit, PR #1/#2 stay open, and 0.2.3/0.2.4 are built from `release/*` branches that are not merged back. Whether this is deliberate is **unknown**. Status: de facto.
