#!/usr/bin/env bash
# Zwei-Konten-Matrix-E2E auf dem Emulator (Chunk A + B).
# EIN Befehl = reproduzierbarer Lauf: Conduit+ntfy, adb reverse, Konten,
# beide APKs, am instrument mit dem Zwei-Konten-Test.
# Voraussetzung: Emulator kailink-atd35 laeuft (emulator-5554).
set -euo pipefail
cd "$(dirname "$0")/.."
DEV_ANDROID_HOME="/home/dev/android-sdk"
ADB="${ADB:-$DEV_ANDROID_HOME/platform-tools/adb}"
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
CONDUIT_PORT="${E2E_CONDUIT_PORT:-6167}"
NTFY_PORT="${E2E_NTFY_PORT:-8090}"
CONDUIT_IMAGE="docker.io/matrixconduit/matrix-conduit:latest"
NTFY_IMAGE="docker.io/binwiederhier/ntfy:latest"
ALICE_USER="${E2E_ALICE_USER:-kailink_alice}"
ALICE_PASS="${E2E_ALICE_PASS:-phase1-e2e-alice}"
BOB_USER="${E2E_BOB_USER:-kailink_bob}"
BOB_PASS="${E2E_BOB_PASS:-phase1-e2e-bob}"

[[ -x "$ADB" ]] || { echo "ADB fehlt: $ADB" >&2; exit 2; }
"$ADB" -s "$DEVICE" get-state >/dev/null || { echo "Emulator nicht erreichbar: $DEVICE" >&2; exit 2; }

echo "[1/6] Container-Images (Digest ins Log)"
podman pull "$CONDUIT_IMAGE" >/dev/null
podman pull "$NTFY_IMAGE" >/dev/null
podman images --digests 2>/dev/null | grep -E "conduit|ntfy" || podman images 2>/dev/null | grep -E "conduit|ntfy" || true

echo "[2/6] Conduit + ntfy starten"
podman rm -f kailink-e2e-conduit kailink-e2e-ntfy >/dev/null 2>&1 || true
CONFIG_FILE="$(mktemp)"
cleanup_cfg() { rm -f "$CONFIG_FILE"; }
trap cleanup_cfg EXIT
printf '%s\n' '[global]' 'server_name = "localhost"' 'database_path = "/var/lib/conduit"' 'database_backend = "rocksdb"' 'address = "0.0.0.0"' 'port = 6167' 'allow_registration = true' > "$CONFIG_FILE"
podman run -d --name kailink-e2e-conduit -e CONDUIT_CONFIG=/etc/conduit.toml -v "$CONFIG_FILE:/etc/conduit.toml:ro" -p "$CONDUIT_PORT:6167" "$CONDUIT_IMAGE" >/dev/null
podman run -d --name kailink-e2e-ntfy -p "$NTFY_PORT:80" "$NTFY_IMAGE" serve >/dev/null
READY=false
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$CONDUIT_PORT/_matrix/client/versions" >/dev/null 2>&1; then READY=true; break; fi
  sleep 2
done
if [[ "$READY" != true ]]; then echo "Conduit wurde nicht bereit." >&2; podman logs kailink-e2e-conduit >&2; exit 1; fi
curl -fsS "http://127.0.0.1:$NTFY_PORT/" >/dev/null
echo "Conduit + ntfy bereit (Ports $CONDUIT_PORT/$NTFY_PORT)."

echo "[3/6] adb reverse (Emulator-Netz defekt; Tunnel statt 10.0.2.2)"
"$ADB" -s "$DEVICE" reverse "tcp:6167" "tcp:$CONDUIT_PORT"
"$ADB" -s "$DEVICE" reverse "tcp:8090" "tcp:$NTFY_PORT"
"$ADB" -s "$DEVICE" reverse --list

echo "[4/6] Wegwerf-Konten registrieren (bereits vorhandene sind ok)"
register_user() { curl -fsS -X POST "http://127.0.0.1:$CONDUIT_PORT/_matrix/client/v3/register" -H 'content-type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\",\"auth\":{\"type\":\"m.login.dummy\"}}" >/dev/null 2>&1 || true; }
register_user "$ALICE_USER" "$ALICE_PASS"
register_user "$BOB_USER" "$BOB_PASS"
echo "Konten bereit: $ALICE_USER, $BOB_USER."

echo "[5/6] APKs bauen + installieren"
./gradlew :app:assembleEmulatorDebug :app:assembleDebugAndroidTest --offline
"$ADB" -s "$DEVICE" install -r app/build/outputs/apk/emulatorDebug/app-emulatorDebug.apk
"$ADB" -s "$DEVICE" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "[6/6] Zwei-Konten-E2E (Chunk A + Chunk B + Restore-Leg)"
"$ADB" -s "$DEVICE" shell am instrument -w -r \
  -e debug false \
  -e class 'org.box44.kailink.MatrixE2eTest#twoAccountTimelineDeliveryUnencrypted' \
  -e e2e.homeserver http://127.0.0.1:6167 \
  -e e2e.alice.username "$ALICE_USER" -e e2e.alice.password "$ALICE_PASS" \
  -e e2e.bob.username "$BOB_USER" -e e2e.bob.password "$BOB_PASS" \
  org.box44.kailink.test/androidx.test.runner.AndroidJUnitRunner
