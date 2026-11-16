#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

EXPECTED_CERT_SHA256="46:1F:EA:A7:66:3F:E4:98:ED:B6:C7:57:CE:73:F8:32:DC:74:B5:56:5E:5D:C1:B5:96:86:D9:3B:B3:E0:78:98"
EXPECTED_CERT_SHA256_HEX="461feaa7663fe498edb6c757ce73f832dc74b5565e5dc1b59686d93bb3e07898"
REPO_OWNER="tobiweber25-netizen"
REPO_NAME="Weather-App"
PACKAGE_NAME="com.google.weatherapp"

INSTALL_USB="${INSTALL_USB:-0}"
MESSAGE=""

for arg in "$@"; do
  case "$arg" in
    --usb)
      INSTALL_USB=1
      ;;
    --help|-h)
      echo "Usage: ./scripts/ota-release.sh [\"Release description\"] [--usb]"
      echo "  --usb    Also build release APK locally and install via adb on attached device"
      exit 0
      ;;
    *)
      if [ -z "$MESSAGE" ]; then
        MESSAGE="$arg"
      else
        MESSAGE="$MESSAGE $arg"
      fi
      ;;
  esac
done

if [ -z "$MESSAGE" ]; then
  MESSAGE="OTA update"
fi

# Resolve JAVA_HOME
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$HOME"/.gradle/jdks/eclipse_adoptium-17-*/jdk-17*/Contents/Home \
    "$HOME"/.gradle/jdks/eclipse_adoptium-*/jdk-*/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME_CANDIDATE="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [ -n "$JAVA_HOME_CANDIDATE" ] && [ -x "$JAVA_HOME_CANDIDATE/bin/java" ]; then
      export JAVA_HOME="$JAVA_HOME_CANDIDATE"
    fi
  fi
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "ERROR: Could not locate a valid JAVA_HOME."
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

# Resolve ANDROID_HOME
if [ -z "${ANDROID_HOME:-}" ] && [ -f "$ROOT_DIR/local.properties" ]; then
  SDK_FROM_PROPS="$(grep '^sdk.dir=' "$ROOT_DIR/local.properties" | cut -d= -f2- | tr -d '\r' || true)"
  if [ -n "$SDK_FROM_PROPS" ] && [ -d "$SDK_FROM_PROPS" ]; then
    export ANDROID_HOME="$SDK_FROM_PROPS"
  fi
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"

# Verify keystore integrity before building or releasing
KEYSTORE_PATH="$ROOT_DIR/app/keystore/debug.keystore"
if [ ! -f "$KEYSTORE_PATH" ]; then
  echo "ERROR: Missing deterministic keystore at $KEYSTORE_PATH"
  exit 1
fi

ACTUAL_CERT="$("$JAVA_HOME/bin/keytool" -list -v -keystore "$KEYSTORE_PATH" -storepass android -alias androiddebugkey | grep -m1 "SHA256:" | awk '{print $2}')"
if [ "$ACTUAL_CERT" != "$EXPECTED_CERT_SHA256" ]; then
  echo "ERROR: Keystore SHA-256 mismatch!"
  echo "  Expected: $EXPECTED_CERT_SHA256"
  echo "  Actual:   $ACTUAL_CERT"
  exit 1
fi

# Verify git branch
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$CURRENT_BRANCH" != "main" ]; then
  echo "ERROR: Release script must run on branch 'main' (currently '$CURRENT_BRANCH')."
  exit 1
fi

git fetch --tags origin --quiet || true

# Bump versionCode and patch versionName in app/build.gradle.kts
GRADLE_FILE="$ROOT_DIR/app/build.gradle.kts"
GRADLE_BACKUP="$(mktemp)"
cp "$GRADLE_FILE" "$GRADLE_BACKUP"

restore_gradle_on_fail() {
  cp "$GRADLE_BACKUP" "$GRADLE_FILE"
  rm -f "$GRADLE_BACKUP"
}
trap restore_gradle_on_fail ERR

VERSION_INFO="$(python3 - "$GRADLE_FILE" << 'EOF'
import re, subprocess, sys

gradle_path = sys.argv[1]
with open(gradle_path, "r", encoding="utf-8") as f:
    text = f.read()

vc_match = re.search(r"versionCode\s*=\s*(\d+)", text)
vn_match = re.search(r'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"', text)
if not vc_match or not vn_match:
    sys.stderr.write("ERROR: Could not parse versionCode / versionName in app/build.gradle.kts\n")
    sys.exit(1)

old_code = int(vc_match.group(1))
major, minor, patch = int(vn_match.group(1)), int(vn_match.group(2)), int(vn_match.group(3))
new_code = old_code + 1

existing_tags = set(
    subprocess.check_output(["git", "tag", "-l"], text=True).splitlines()
)
new_patch = patch + 1
while f"v{major}.{minor}.{new_patch}" in existing_tags:
    new_patch += 1

new_name = f"{major}.{minor}.{new_patch}"

text = re.sub(r"(versionCode\s*=\s*)\d+", rf"\g<1>{new_code}", text, count=1)
text = re.sub(r'(versionName\s*=\s*")[^"]+(")', rf'\g<1>{new_name}\g<2>', text, count=1)

with open(gradle_path, "w", encoding="utf-8") as f:
    f.write(text)

print(f"{new_code} {new_name}")
EOF
)"

NEW_CODE="$(echo "$VERSION_INFO" | awk '{print $1}')"
NEW_NAME="$(echo "$VERSION_INFO" | awk '{print $2}')"
NEW_TAG="v${NEW_NAME}"

echo "==> Bumped version to ${NEW_TAG} (versionCode=${NEW_CODE})"

echo "==> Running unit tests..."
./gradlew testDebugUnitTest --console=plain

if [ "$INSTALL_USB" = "1" ]; then
  echo "==> Building release APK locally for USB install..."
  ./gradlew assembleRelease --console=plain

  APK_PATH="$(find app/build/outputs/apk/release -name "*release.apk" ! -name "*unsigned*" | head -n 1)"
  if [ -z "$APK_PATH" ]; then
    echo "ERROR: Release APK not found after assembleRelease."
    exit 1
  fi

  APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)"
  if [ -n "$APKSIGNER" ]; then
    APK_CERT="$("$APKSIGNER" verify --print-certs "$APK_PATH" | grep "SHA-256 digest" | awk '{print $NF}')"
    if [ "$APK_CERT" != "$EXPECTED_CERT_SHA256_HEX" ]; then
      echo "ERROR: Built APK certificate mismatch ($APK_CERT != $EXPECTED_CERT_SHA256_HEX)"
      exit 1
    fi
  fi

  if [ -x "$ADB" ]; then
    CONNECTED_DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
    if [ -n "$CONNECTED_DEVICES" ]; then
      while IFS= read -r SERIAL; do
        echo "==> Installing $APK_PATH onto USB device $SERIAL..."
        "$ADB" -s "$SERIAL" install -r "$APK_PATH"
        "$ADB" -s "$SERIAL" shell am start -n "$PACKAGE_NAME/.MainActivity" >/dev/null || true
      done <<< "$CONNECTED_DEVICES"
    else
      echo "WARN: --usb specified, but no authorized ADB device found."
    fi
  else
    echo "WARN: adb not found at $ADB"
  fi
fi

# Version bump + build succeeded; keep updated gradle file
trap - ERR
rm -f "$GRADLE_BACKUP"

echo "==> Committing, tagging $NEW_TAG, and pushing to origin..."
git add -A
git commit -m "chore(release): ${NEW_TAG} - ${MESSAGE}"
git tag "$NEW_TAG"
git push origin main
git push origin "$NEW_TAG"

echo "==> Waiting for GitHub Actions workflow 'Build & Release OTA Update' for $NEW_TAG..."
MAX_ATTEMPTS=24
SLEEP_SECONDS=15
WORKFLOW_DONE=0

for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  sleep "$SLEEP_SECONDS"

  # Check published metadata first (zero GitHub API rate-limit usage)
  META_HTTP="$(curl -s -o /dev/null -w "%{http_code}" -L "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${NEW_TAG}/ota-metadata.json" || echo "000")"
  APK_HTTP="$(curl -sI -o /dev/null -w "%{http_code}" -L "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${NEW_TAG}/weather-app-release.apk" || echo "000")"

  if [ "$META_HTTP" = "200" ] && [ "$APK_HTTP" = "200" ]; then
    WORKFLOW_DONE=1
    break
  fi

  RUN_STATUS="$(curl -s "https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/actions/runs?per_page=10" | python3 -c "
import json, sys
tag = '${NEW_TAG}'
try:
    data = json.load(sys.stdin)
except Exception:
    print('api_error')
    sys.exit(0)
for r in data.get('workflow_runs', []):
    if r.get('head_branch') == tag:
        print(f\"{r.get('status')}:{r.get('conclusion')}:{r.get('html_url')}\")
        sys.exit(0)
print('waiting')
")"

  echo "   [$attempt/$MAX_ATTEMPTS] status=${RUN_STATUS} meta_http=${META_HTTP} apk_http=${APK_HTTP}"

  case "$RUN_STATUS" in
    completed:success:*)
      # Give CDN a moment if assets are just finishing upload
      sleep 3
      META_HTTP="$(curl -s -o /dev/null -w "%{http_code}" -L "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${NEW_TAG}/ota-metadata.json" || echo "000")"
      APK_HTTP="$(curl -sI -o /dev/null -w "%{http_code}" -L "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${NEW_TAG}/weather-app-release.apk" || echo "000")"
      if [ "$META_HTTP" = "200" ] && [ "$APK_HTTP" = "200" ]; then
        WORKFLOW_DONE=1
        break
      fi
      ;;
    completed:failure:*|completed:cancelled:*|completed:timed_out:*)
      echo "ERROR: GitHub Actions release failed: $RUN_STATUS"
      exit 1
      ;;
  esac
done

if [ "$WORKFLOW_DONE" != "1" ]; then
  echo "ERROR: Timed out waiting for GitHub Release ${NEW_TAG} assets."
  exit 1
fi

LATEST_META="$(curl -fsSL "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/latest/download/ota-metadata.json")"
echo "==> OTA Release ${NEW_TAG} is LIVE!"
echo "$LATEST_META"
