# Weather App (Almanac) — Project Rules

## Automatic OTA Release Rule
- **Always push an OTA update automatically once changes are complete and the user is satisfied** (or whenever the user asks to ship, deploy, or update the app), without waiting to be reminded.
- Run the single release command from the project root:
  ```bash
  ./scripts/ota-release.sh "<short summary of changes>"
  ```
- If an Android phone is plugged in via USB and the user asks to flash/update it directly (or recover an old build), append `--usb`:
  ```bash
  ./scripts/ota-release.sh "<short summary of changes>" --usb
  ```
- Wait for `./scripts/ota-release.sh` to finish verifying that GitHub Actions built the release and `ota-metadata.json` + `weather-app-release.apk` are live, then tell the user the new version (`vX.Y.Z`) is ready to install OTA (the app auto-checks on launch and via Options -> Check for Updates).

## OTA & Build Safety Invariants
1. **Deterministic Signing Keystore**: NEVER replace, delete, or alter `app/keystore/debug.keystore` (`SHA-256: 46:1F:EA:A7:66:3F:E4:98:ED:B6:C7:57:CE:73:F8:32:DC:74:B5:56:5E:5D:C1:B5:96:86:D9:3B:B3:E0:78:98`). Both `debug` and `release` build types and CI builds MUST sign with this keystore so OTA installs never fail with signature mismatch.
2. **Never Bake `GITHUB_TOKEN`**: NEVER put `GITHUB_TOKEN` into `BuildConfig`, `app/build.gradle.kts`, `local.properties`, or `.github/workflows/release.yml` build inputs. GitHub Actions tokens expire after the CI job ends and cause `HTTP 401` failures on devices.
3. **Never Hardcode Version Strings**: UI version badges/labels MUST read `BuildConfig.VERSION_NAME` / `BuildConfig.VERSION_CODE`, never hardcoded strings.
4. **Local JDK on macOS**: System default `java` may be missing on this Mac. Always use `./scripts/ota-release.sh` or export `JAVA_HOME=$(echo ~/.gradle/jdks/eclipse_adoptium-17-*/jdk-17*/Contents/Home)` before running `./gradlew`.
5. **OTA Update Flow**: `AppUpdateManager` checks `releases/latest/download/ota-metadata.json` first (CDN, zero rate limit, no auth), then falls back to unauthenticated GitHub REST API, then GitHub web redirect. Do not reintroduce authenticated API calls for public releases.
