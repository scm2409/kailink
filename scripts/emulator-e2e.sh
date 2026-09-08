#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-/home/dev/android-sdk/platform-tools/adb}"
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
[[ -x "$ADB" ]] || { echo "ADB fehlt: $ADB" >&2; exit 2; }
"$ADB" -s "$DEVICE" get-state >/dev/null
echo "[1/4] Lokaler Matrix-/ntfy-HTTP-Smoke"
./scripts/e2e-local.sh
echo "[2/4] Phone-/Emulator-Builds"
./gradlew testDebugUnitTest assembleDebug assembleEmulatorDebug assembleDebugAndroidTest
echo "[3/4] Android-Instrumentierungs-Smoke"
"$ADB" -s "$DEVICE" install -r app/build/outputs/apk/emulatorDebug/app-emulatorDebug.apk >/dev/null
"$ADB" -s "$DEVICE" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
"$ADB" -s "$DEVICE" shell am instrument -w -r -e debug false org.box44.kailink.test/androidx.test.runner.AndroidJUnitRunner
echo "[4/4] Vollständige Matrix-/E2EE-/UnifiedPush-Kette"
echo "NICHT AUSGEFÜHRT: kontrollierte Testkonfiguration, E2EE-Schlüsselaustausch und automatisierbarer UnifiedPush-Distributor fehlen." >&2
exit 3
