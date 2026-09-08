#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v podman >/dev/null || { echo "Podman fehlt; E2E nicht ausgeführt." >&2; exit 2; }
command -v curl >/dev/null || { echo "curl fehlt; E2E nicht ausgeführt." >&2; exit 2; }
conduit_container="kailink-e2e-conduit"
ntfy_container="kailink-e2e-ntfy"
conduit_image="docker.io/matrixconduit/matrix-conduit:latest"
ntfy_image="docker.io/binwiederhier/ntfy:latest"
base_url="http://127.0.0.1:6167"
ntfy_url="http://127.0.0.1:8090"
config_file="$(mktemp)"
cleanup() { podman rm -f "$conduit_container" "$ntfy_container" >/dev/null 2>&1 || true; rm -f "$config_file"; }
case "${1:-run}" in down|clean) podman rm -f "$conduit_container" "$ntfy_container" >/dev/null 2>&1 || true; exit 0;; esac
trap cleanup EXIT
cleanup
cat > "$config_file" <<'CONFIG'
[global]
server_name = "localhost"
database_path = "/var/lib/conduit"
database_backend = "rocksdb"
address = "0.0.0.0"
port = 6167
allow_registration = true
CONFIG
podman pull "$conduit_image"
podman pull "$ntfy_image"
podman run -d --name "$conduit_container" -e CONDUIT_CONFIG=/etc/conduit.toml -v "$config_file:/etc/conduit.toml:ro" -p 6167:6167 "$conduit_image" >/dev/null
podman run -d --name "$ntfy_container" -p 8090:80 "$ntfy_image" serve >/dev/null
ready=false
for _ in $(seq 1 60); do
  if curl -fsS "$base_url/_matrix/client/versions" >/dev/null 2>&1; then ready=true; break; fi
  sleep 2
done
$ready || { echo "Conduit wurde nicht bereit." >&2; podman logs "$conduit_container" >&2; exit 1; }
curl -fsS "$ntfy_url/" >/dev/null
printf '%s\n' 'E2E-Smoke bestanden: Matrix-Homeserver und lokales ntfy erreichbar.'
register_user() { curl -fsS -X POST "$base_url/_matrix/client/v3/register" -H 'content-type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\",\"auth\":{\"type\":\"m.login.dummy\"}}" >/dev/null; }
register_user kailink_alice 'phase1-e2e-alice'
register_user kailink_bob 'phase1-e2e-bob'
printf '%s\n' 'E2E-Registrierung bestanden: zwei disposable Testkonten angelegt.'
printf '%s\n' 'SDK-Login/Restore, E2EE-Entschlüsselung, Matrix-Pusher, Push-Sync und Android-Benachrichtigung bleiben ohne Android-Runtime ungetestet.'
