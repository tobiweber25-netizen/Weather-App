---
name: ota-update
description: >-
  Build, test, tag, push, and verify wireless OTA releases (and optional USB installs)
  for the Weather App using ./scripts/ota-release.sh. Use whenever finishing feature
  changes, shipping an update, or recovering a USB-attached Android phone.
---

# OTA Update Workflow

## Standard Wireless Release (Every Completed Change)
Once code/UI edits are done and the user is happy with the changes, run:

```bash
./scripts/ota-release.sh "<concise summary of changes>"
```

What the script does automatically:
1. Resolves `JAVA_HOME` (JDK 17) and `ANDROID_HOME`, and verifies `app/keystore/debug.keystore` SHA-256 integrity.
2. Increments `versionCode` (+1) and patch `versionName` (`X.Y.Z` -> `X.Y.(Z+1)`) in `app/build.gradle.kts`.
3. Runs `./gradlew testDebugUnitTest`.
4. Commits all changes, creates tag `vX.Y.Z`, and pushes `main` + tag to `origin`.
5. Polls GitHub Actions (`Build & Release OTA Update`) until `weather-app-release.apk` and `ota-metadata.json` are live on GitHub Releases (`releases/latest/download/ota-metadata.json`).

## USB Direct Install + OTA Release
When an Android phone is connected via USB and needs immediate installation (or recovery from a broken older build):

```bash
./scripts/ota-release.sh "<concise summary of changes>" --usb
```

This additionally runs `./gradlew assembleRelease`, verifies the APK signing certificate, installs via `adb install -r`, and launches `com.google.weatherapp/.MainActivity` before pushing the release tag.

## How In-App OTA Works (`AppUpdateManager.kt`)
1. **Launch & Manual Checks**: `WeatherScreen` runs a silent check in `LaunchedEffect(Unit)` on app open and supports manual checks via Options -> `Check for Updates` (plus 5-tap Easter egg on the `Almanac` title).
2. **3-Tier Check (No Tokens)**:
   - Tier 1: `https://github.com/tobiweber25-netizen/Weather-App/releases/latest/download/ota-metadata.json` (CDN, zero API rate limit).
   - Tier 2: Unauthenticated `https://api.github.com/repos/tobiweber25-netizen/Weather-App/releases/latest`.
   - Tier 3: Web redirect `https://github.com/tobiweber25-netizen/Weather-App/releases/latest`.
3. **Verification**: After `./scripts/ota-release.sh` finishes, confirm the printed `ota-metadata.json` matches the new `tag_name` and `version_code`.
