# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Hablá siempre en castellano con el usuario.**

## Project

Magis Launcher: Android TV app (Kotlin, single screen, no settings) that on launch brings up an
embedded WireGuard VPN (routing only MagisTV and itself), opens MagisTV / Xuper TV, and tears the
VPN down a few seconds after MagisTV reaches its home screen, or after
`LauncherConfig.VPN_DURATION_SECONDS` (20) as a cap. Build tooling, signing, and Surge publishing are
copied from the sibling project `../nahuelito-tv` and work the same way.

## Build / deploy

```bash
# JDK 17 and Android SDK via mise (mise.toml); local.properties points at the mise SDK.
./gradlew assembleDebug     # app/build/outputs/apk/debug/magis-launcher-debug-<version>.apk
./gradlew assembleRelease   # signed with magis-launcher.keystore via .env (KEYSTORE_*)
scripts/publish_release.sh [--dry-run] "changelog"   # versionCode+1, release, dist/ + version.json, surge
```

- `app/src/main/assets/wireguard.conf` is **required and git-ignored** (it holds the private key);
  `checkWireguardConfig` (hooked to `preBuild`) fails the build without it. Format:
  `wireguard.conf.example` (a ProtonVPN WireGuard download).
- No tests/lint; verification is on-device. Default device is TV1 `192.168.8.202:5555` (has
  MagisTV as `com.android.mgstv`); never touch TV2 `192.168.8.134:5555` unless asked.
  adb: `~/.local/share/mise/installs/android-sdk/latest/platform-tools/adb`.
- The system VPN consent dialog (`com.android.vpndialogs`) ignores adb-injected key events; it has
  to be accepted with the real remote.
- **Never grant permissions via adb** (`appops`, `pm grant`): the user wants every permission
  granted by hand through the app's own flow.
- Versioning: single integer `versionCode`, `versionName = versionCode.toString()`. The publish
  script doesn't commit — commit the `app/build.gradle` bump afterwards (or `git checkout` it after
  a dry run). Surge creds come from `.env`; `surge` falls back to `npx --yes surge`.

## Architecture

- **`MainActivity`**: runs the whole flow in `onCreate`: update check and usage-access prompt
  (both skipped if a session is already `Connected`) → `TargetApp.find()` → `VpnService.prepare()`
  consent → `VpnSessionService.start(targetPackage)`. Leaving before MagisTV is started (BACK, or
  `onStop` from HOME while no own-requested external screen — Settings, VPN consent — is up)
  calls `VpnSessionService.stop()`, which waits out any in-flight `setState` and brings the tunnel down.
  Renders `VpnSessionService.state`; on the first `Connected` it launches the target app and
  `finish()`es. Choosing "Instalar" on an update skips connecting.
- **`VpnSessionService`** (foreground service, type `specialUse`): owns the session and exposes a
  process-wide `StateFlow<VpnState>` (`Idle`/`Connecting`/`Connected`/`Failed`). Parses the asset
  with `com.wireguard.config.Config`, brings the tunnel up with `GoBackend` (library
  `com.wireguard.android:tunnel`, which declares its own `VpnService`; needs core library
  desugaring), then polls `IP_CHECK_URL` until the tunnel has egress (`CONNECT_TIMEOUT_MS`) and
  counts down. A new `start()` while `Connected` just restarts the countdown. `onDestroy` always
  brings the tunnel down. `loadConfig()` injects `IncludedApplications = <target>, <self>` into the
  `[Interface]` section, so only MagisTV (and our own egress check) use the tunnel.
- **`TargetReadyWatcher`**: polls `UsageStatsManager` events during the countdown; once the target
  package shows an activity ending in `TARGET_READY_ACTIVITY` (`.HomeActivity`: MagisTV goes
  `WelcomeActivity` → `HomeActivity`, and only the welcome needs the VPN), the deadline shrinks to
  `READY_GRACE_SECONDS`. Needs "usage access" (`PACKAGE_USAGE_STATS` app-op): `MainActivity.askForUsageAccess()`
  sends the user to `Settings.ACTION_USAGE_ACCESS_SETTINGS` (exists on TV1) with a "don't ask again"
  option. Without it, the session is purely time-based.
- **`TargetApp.find()`**: no fixed package name (clones differ); first LEANBACK_LAUNCHER/LAUNCHER
  activity whose label or package contains `TARGET_APP_PATTERNS` (`magis`, `xuper`). Relies on the
  `<queries>` block in the manifest for Android 11+ package visibility.
- **Updates**: `UpdateChecker` is nahuelito-tv's, pointed at `https://magis-launcher.surge.sh/version.json`
  (`versionCode` compared with `BuildConfig.VERSION_CODE`, DownloadManager + `FileProvider` install).
  Before installing, `ensureInstallPermission()` checks `canRequestPackageInstalls()` and, if missing,
  sends the user to `ACTION_MANAGE_UNKNOWN_APP_SOURCES` ("Fuentes desconocidas" → "Autorizadas" on
  TV1); without it the system installer silently refuses the update.
  APK filename, `versionCode` and `version.json` must agree — use the publish script.
- All tunables live in `LauncherConfig.kt`. Icon/banner were cut from the orange tile of the design
  image (`res/drawable/app_logo.png`, `app_banner.png`, `mipmap-*/ic_launcher.png`).
