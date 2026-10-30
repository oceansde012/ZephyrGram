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

Troubleshooting:

- **Maven Central rate limit (HTTP 429):** copy `scripts/gradle-central-mirror.gradle` to `~/.gradle/init.d/`. This routes Central through Google's mirror.
- **Android Studio on a PC:** run `scripts/init-submodules.sh` first, set the environment variables before launching the IDE, then open the folder.

## Upstream references

- Telegram API: https://core.telegram.org/api
- MTProto protocol: https://core.telegram.org/mtproto
- Telegram for Android: https://github.com/DrKLO/Telegram

## License

Based on Telegram for Android, which is licensed under GPLv2. See [LICENSE](LICENSE).
