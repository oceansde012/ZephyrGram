# ZephyrGram

**ZephyrGram is an unofficial Android client for Telegram.** It is built on the official [Telegram for Android](https://github.com/DrKLO/Telegram) source code (DrKLO/Telegram, imported at commit `3de37c8c`, version 13.1.1 / 7237). It is not affiliated with Telegram.

Features are inspired by [AyuGram](https://github.com/AyuGram), but they are re-implemented from scratch. AyuGram's Android sources are not used.

> **Status: early work.** All features below compile, and a debug APK has been built. **None of it has been tested on a device yet**. Expect bugs.

Package id: `org.zephyrgram.messenger`

## Features

### Ghost mode

A ghost icon on the main (chat list) screen toggles ghost mode; long-press it to fine-tune. There is also a switch in the main-screen "⋮" menu. When on, it applies all functions at once: no read receipts, no typing status, no story views, and the account stays offline. Story viewing without leaving a trace is also offered from the story context menu ("View invisibly"). Implemented by intercepting requests in `ConnectionsManager.sendRequestInternal` (`org.telegram.messenger.zephyr.ZephyrGhost`). The individual options are also in ZephyrGram settings.

### Saved history and edit-history viewer

- Messages deleted on the server are saved locally and stay visible in the open chat.
- The old text of edited messages is recorded (setting "Save edited messages"). Open a message's edit history in `ZephyrEditHistoryActivity` to see the original, each version and the current one, and copy any text.
- Settings > Other shows how much is stored and can clear it.

### In-app updater

`ZephyrUpdater` reads the latest GitHub release (`releases/latest` of `oceansde012/ZephyrGram`), shows an update dialog and downloads the APK. It checks automatically at most once a day (can be turned off), and there is a "Check for updates" button in ZephyrGram settings. Updates install over the old version only if signed with the same key (see Releasing).

### Plugins

Plugins are single Python files run by an embedded Python runtime ([Chaquopy](https://chaquo.com/chaquopy/), arm64-v8a and x86_64 only). The plugin store UI (`ZephyrPluginsActivity`, details and per-plugin settings screens) downloads an `index.json` that is signed with Ed25519 and verified in the app against built-in public keys. The store content lives in a separate repository, [`ZephyrGram-Plugins`](https://github.com/oceansde012/ZephyrGram-Plugins) (local checkout `D:\ZephyrGram-Plugins`): one folder per plugin, CI validates pull requests, and a publish workflow builds and signs the index. The plugin API and publishing rules are in that repository's `docs/API.md` and `README.md`. The app-side code is in `org.telegram.messenger.zephyr.plugins`.

### Settings derived from exteraGram

The ZephyrGram entry is the first item on the Telegram settings screen, right under the profile header. Settings > ZephyrGram starts with shortcuts to plugins, AI auto-reply and the AI assistant, followed by a Ghost mode block, message-saving switches, updates and four sub-screens. Several options are ported from [exteraGram](https://github.com/exteraSquad/exteraGram) (GPLv2) and modified. What is actually there:

- **General:** disable number rounding, message time with seconds, hide phone number.
- **Appearance:** main-screen title (app name / username / name), center title, unread counter on folder tabs, avatar corners, folder tab style (pill, rounded square, underline, none, filled pill), bottom-right button shape, holiday decorations (by date / always / never), always show snowfall, force blur.
- **Chats:** disable jump to next channel, hide keyboard on scroll, hide share button, photo quality, hide time on stickers, unlimited recent stickers, sticker size, player rewind step, pause video on minimize, double tap on incoming/outgoing messages (off, reaction, reply, copy, forward, edit, delete).
- **Other:** save deleted / edited messages, saved-history storage and clear, reset settings, credits.
- **Message context menu additions** (`ZephyrMessageMenu`): forward without author, copy photo, save to Saved Messages, message details.

Not implemented: message filters. The "Local Premium" and "Disable ads" toggles exist in the settings, but nothing reads them yet, so they have no effect. App name and icon rebrand is not done. Settings screens, strings and `ZephyrConfig` are in English, Ukrainian and Russian.

Telegram's own Google/Firebase API keys and release keystore were removed from this fork (SafetyNet and Maps keys are empty). The Huawei, HockeyApp, Standalone and Tests app modules were removed; only `TMessagesProj` and `TMessagesProj_App` are built. Push notifications (FCM) are disabled, because a custom package cannot use Telegram's Firebase project.

## Building

No API keys and no prebuilt binaries are in this repository.

You need your own Telegram API credentials from https://my.telegram.org. Pass them as environment variables `TG_API_ID` and `TG_API_HASH`, as Gradle properties, or in `local.properties` (git-ignored; the Windows helper writes it). Never commit them. The build fails without them.

Requirements: JDK 17+ and Android SDK packages `platforms;android-36`, `build-tools;36.0.0`, `ndk;27.2.12479018` and `cmake;3.22.1`. Linux, or any machine with JDK 17+.

1. Fetch the submodules at their pinned commits. libyuv falls back to a GitHub mirror.

   ```bash
   scripts/init-submodules.sh
   ```

2. Install the Android SDK packages listed above and set `ANDROID_HOME`.

3. Export your credentials.

   ```bash
   export TG_API_ID=<your api_id>
   export TG_API_HASH=<your api_hash>
   ```

4. Build. Debug builds are signed with the default Android debug keystore. For `assembleRelease`, point the build at your own keystore through `ZEPHYR_KEYSTORE` (path), `ZEPHYR_KEYSTORE_PASSWORD`, `ZEPHYR_KEY_ALIAS` and `ZEPHYR_KEY_PASSWORD`; without them the release APK is unsigned. Never commit the keystore.

   ```bash
   scripts/build-android.sh . assembleDebug
   ```

### Windows setup

From PowerShell in the project folder, open the local setup window:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -File .\scripts\configure-windows.ps1
```

The execution-policy option applies only to this PowerShell process; it does not change the machine policy.

Enter your own Telegram API ID and API Hash. The helper finds the Android SDK,
including an SDK installed by the Claude Windows app, and stores all three values
in `local.properties`, which is excluded from Git. It preserves other properties
and keeps previous local settings in the ignored `.gradle/zephyr-setup-backups/`
folder. Do not share either file or backup. Values are never printed or uploaded.

`scripts/configure-windows.ps1` and `scripts/build-windows.ps1` are the Windows entry points; the Bash scripts above are for Linux/CI. Check local prerequisites without showing secrets:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-windows.ps1 -CheckOnly
```

Build a debug APK using the Gradle wrapper JAR (no `gradlew.bat` is required):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1
```

Use `-Task help` for configuration diagnostics or `-Offline` to use only cached
dependencies. Release signing and store-index keys are separate from Telegram API
credentials and are not needed for a debug APK. API credentials supplied through
Gradle properties or environment variables take priority over `local.properties`.

### Versioning

ZephyrGram has its own version, separate from Telegram's (`APP_VERSION_NAME` / `APP_VERSION_CODE` stay at upstream values). It is set in `gradle.properties`:

- `ZEPHYR_VERSION_NAME` (plain semver, e.g. `0.1.0`) is exposed to code as `BuildConfig.ZEPHYR_VERSION`. Override it with `-PZEPHYR_VERSION_NAME=X.Y.Z`; CI takes it from the tag.
- `ZEPHYR_VERSION_CODE` must be increased by hand for every release. The APK versionCode is `ZEPHYR_VERSION_CODE * 100000 + APP_VERSION_CODE * 10 + abi`, so it grows with every ZephyrGram release and every upstream rebase.

### Releasing

`.github/workflows/release.yml` builds a signed arm64-v8a release APK and publishes it as a GitHub Release named `ZephyrGram-X.Y.Z-arm64-v8a.apk`. The in-app updater reads the latest release.

1. Bump `ZEPHYR_VERSION_CODE` (and `ZEPHYR_VERSION_NAME`) in `gradle.properties` and commit.
2. Push a tag `vX.Y.Z`: `git tag v0.1.0 && git push origin v0.1.0`. Tags with a suffix (`v0.2.0-beta1`) become pre-releases. The workflow can also be started by hand with a version.

Required repository secrets (Settings > Secrets and variables > Actions):

| Secret | Value |
| --- | --- |
| `ZEPHYR_KEYSTORE_BASE64` | release keystore as base64: `base64 -w0 zephyr.jks` |
| `ZEPHYR_KEYSTORE_PASSWORD` | keystore password |
| `ZEPHYR_KEY_ALIAS` | key alias |
| `ZEPHYR_KEY_PASSWORD` | key password |
| `TG_API_ID` | Telegram api_id |
| `TG_API_HASH` | Telegram api_hash |

Set every secret **without a trailing newline**, otherwise the value is corrupted (a bad keystore or API credential). Set from a file or pipe with the exact bytes, for example `gh secret set ZEPHYR_KEYSTORE_PASSWORD < password.txt` (make sure the file has no final newline, e.g. write it with `printf %s`), and `base64 -w0 zephyr.jks | gh secret set ZEPHYR_KEYSTORE_BASE64`. Do not use `echo`, which appends a newline. Check with `gh secret list`.

Keep the keystore safe: an update must be signed with the same key, or Android refuses to install it over the old version.

Troubleshooting:

- **Maven Central rate limit (HTTP 429):** copy `scripts/gradle-central-mirror.gradle` to `~/.gradle/init.d/`. This routes Central through Google's mirror.
- **Android Studio on a PC:** run `scripts/init-submodules.sh` first, set the environment variables before launching the IDE, then open the folder.

## Credits and notices

- **Telegram for Android** ([DrKLO/Telegram](https://github.com/DrKLO/Telegram), GPLv2) is the base of this app. Telegram is a trademark of its owners; ZephyrGram is unofficial.
- **exteraGram** ([exteraSquad](https://github.com/exteraSquad/exteraGram), GPLv2): some settings and behaviour are ported from it and modified. The in-app credits entry reads "Contains code from exteraGram (exteraSquad), GPLv2."
- **AyuGram**: only an inspiration for the ghost mode and saved-history features. They are re-implemented from scratch and no AyuGram Android source is used.
- **Chaquopy** provides the Python runtime for plugins; see its own license terms.

## Upstream references

- Telegram API: https://core.telegram.org/api
- MTProto protocol: https://core.telegram.org/mtproto
- Telegram for Android: https://github.com/DrKLO/Telegram

## License

Based on Telegram for Android and containing exteraGram-derived code, both licensed under GPLv2, so ZephyrGram is distributed under GPLv2 as well. See [LICENSE](LICENSE).
