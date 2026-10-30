# ZephyrGram

**ZephyrGram is an unofficial Android client for Telegram.** It is built on the official [Telegram for Android](https://github.com/DrKLO/Telegram) source code (DrKLO/Telegram, imported at commit `3de37c8c`, version 13.1.1 / 7237). It is not affiliated with Telegram.

Features are inspired by [AyuGram](https://github.com/AyuGram), but they are re-implemented from scratch. AyuGram's Android sources are not used.

> **Status: early work.** The features below have never been compiled and have not been tested on a device.

Package id: `org.zephyrgram.messenger`

## Features (implemented, untested)

- **Ghost mode**: does not send read receipts, typing status or story views, and keeps the account always offline. Implemented by intercepting requests in `ConnectionsManager.sendRequestInternal`.
- **Deleted message retention**: messages deleted on the server are saved and stay visible in the open chat, marked with a broom mark.
- **Edit history recording**: the old text of edited messages is recorded. There is no UI for it yet.
- **ZephyrGram settings**: a "ZephyrGram" entry in Settings (`org.telegram.ui.ZephyrSettingsActivity`) with toggles. Config lives in `org.telegram.messenger.zephyr.ZephyrConfig`.

Telegram's own Google/Firebase API keys and release keystore were removed from this fork (SafetyNet and Maps keys are empty). The Huawei, HockeyApp, Standalone and Tests app modules were removed; only `TMessagesProj` and `TMessagesProj_App` are built.

Push notifications (FCM) are disabled. A custom package cannot use Telegram's Firebase project.

## Status / roadmap

Not done yet:

- Message filters
- Local Premium and ad removal toggles (they do not act yet)
- Edit-history viewer
- App name and icon rebrand
- Release signing

## Building

No API keys and no prebuilt binaries are in this repository.

You need your own Telegram API credentials from https://my.telegram.org. Pass them as environment variables `TG_API_ID` and `TG_API_HASH`. Never commit them. The build fails without them.

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

Check local prerequisites without showing secrets:

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
2. Push a tag: `git tag v0.1.0 && git push origin v0.1.0`. Tags with a suffix (`v0.2.0-beta1`) become pre-releases. The workflow can also be started by hand with a version.

Required repository secrets (Settings > Secrets and variables > Actions):

| Secret | Value |
| --- | --- |
| `ZEPHYR_KEYSTORE_BASE64` | release keystore as base64: `base64 -w0 zephyr.jks` |
| `ZEPHYR_KEYSTORE_PASSWORD` | keystore password |
| `ZEPHYR_KEY_ALIAS` | key alias |
| `ZEPHYR_KEY_PASSWORD` | key password |
| `TG_API_ID` | Telegram api_id |
| `TG_API_HASH` | Telegram api_hash |

Keep the keystore safe: an update must be signed with the same key, or Android refuses to install it over the old version.

Troubleshooting:

- **Maven Central rate limit (HTTP 429):** copy `scripts/gradle-central-mirror.gradle` to `~/.gradle/init.d/`. This routes Central through Google's mirror.
- **Android Studio on a PC:** run `scripts/init-submodules.sh` first, set the environment variables before launching the IDE, then open the folder.

## Upstream references

- Telegram API: https://core.telegram.org/api
- MTProto protocol: https://core.telegram.org/mtproto
- Telegram for Android: https://github.com/DrKLO/Telegram

## License

Based on Telegram for Android, which is licensed under GPLv2. See [LICENSE](LICENSE).
