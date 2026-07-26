#!/usr/bin/env bash
# Build signed release APK using Docker (no local Android Studio / SDK required after first SDK cache).
# Usage (from repo root):
#   ./android/build-apk.sh
#   ./android/build-apk.sh --publish   # also scp to VPS as app.apk
#
# Requires android/signing/keystore.properties + keystore file (gitignored).
# Generate once: ./android/signing/create-keystore.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
SDK_CACHE="${ANDROID_SDK_CACHE:-$HOME/.android-sdk-docker}"
SIGNING_DIR="$ANDROID_DIR/signing"
OUT_APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
PUBLISH=0

for arg in "$@"; do
  case "$arg" in
    --publish) PUBLISH=1 ;;
    -h|--help)
      echo "Usage: $0 [--publish]"
      exit 0
      ;;
  esac
done

if [[ ! -f "$SIGNING_DIR/keystore.properties" ]]; then
  echo "ERROR: Missing $SIGNING_DIR/keystore.properties" >&2
  echo "Run: ./android/signing/create-keystore.sh" >&2
  exit 1
fi

STORE_FILE="$(grep -E '^storeFile=' "$SIGNING_DIR/keystore.properties" | cut -d= -f2-)"
if [[ ! -f "$SIGNING_DIR/$STORE_FILE" ]]; then
  echo "ERROR: Keystore not found: $SIGNING_DIR/$STORE_FILE" >&2
  echo "Run: ./android/signing/create-keystore.sh" >&2
  exit 1
fi

mkdir -p "$SDK_CACHE"

echo "==> Building signed release APK (Docker + cached SDK at $SDK_CACHE)"
docker run --rm \
  -v "$ANDROID_DIR:/project" \
  -v "$SDK_CACHE:/opt/android-sdk" \
  -w /project \
  eclipse-temurin:17-jdk \
  bash -lc '
set -e
export ANDROID_HOME=/opt/android-sdk
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

if [[ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]]; then
  echo "Downloading Android cmdline-tools…"
  apt-get update -qq
  apt-get install -y -qq wget unzip >/dev/null
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  cd /tmp
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdtools.zip
  unzip -q cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

yes | sdkmanager --licenses >/tmp/sdk-lic.txt 2>/dev/null || true
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"

cd /project
chmod +x ./gradlew
./gradlew assembleRelease --no-daemon
ls -la app/build/outputs/apk/release/
'

if [[ ! -f "$OUT_APK" ]]; then
  echo "ERROR: APK not found at $OUT_APK" >&2
  exit 1
fi

mkdir -p "$ROOT/deploy/public"
cp -f "$OUT_APK" "$ROOT/deploy/public/app.apk"
echo "==> Copied to deploy/public/app.apk ($(wc -c < "$ROOT/deploy/public/app.apk") bytes)"

GRADLE="$ANDROID_DIR/app/build.gradle.kts"
VERSION_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$GRADLE" | head -1)"
VERSION_NAME="$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE" | head -1)"
APK_URL="https://waze-issues.ster.by/app.apk"
printf '{"versionCode":%s,"versionName":"%s","apkUrl":"%s"}\n' \
  "$VERSION_CODE" "$VERSION_NAME" "$APK_URL" > "$ROOT/deploy/public/version.json"
echo "==> Wrote version.json (code=$VERSION_CODE name=$VERSION_NAME)"

if [[ "$PUBLISH" -eq 1 ]]; then
  SSH_HOST="${WAZE_ISSUES_SSH:-myvps}"
  echo "==> Publishing to VPS ($SSH_HOST:~/waze-issues/deploy/public/)"
  scp "$ROOT/deploy/public/app.apk" "${SSH_HOST}:~/waze-issues/deploy/public/app.apk"
  scp "$ROOT/deploy/public/version.json" "${SSH_HOST}:~/waze-issues/deploy/public/version.json"
  echo "==> Live at $APK_URL (version.json alongside)"
fi

echo "BUILD_OK: $OUT_APK"
