#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v podman >/dev/null || { echo "Podman fehlt; E2E nicht ausgeführt." >&2; exit 2; }
project=kailink-e2e
compose=(podman compose -p "$project" -f scripts/compose.yaml)
cleanup() { "${compose[@]}" down -v >/dev/null 2>&1 || true; }
case "${1:-run}" in down|clean) cleanup; exit 0;; esac
trap cleanup EXIT
"${compose[@]}" up -d
for i in $(seq 1 60); do
  curl -fsS http://127.0.0.1:6167/_matrix/client/versions >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS http://127.0.0.1:6167/_matrix/client/versions >/dev/null
curl -fsS http://127.0.0.1:8090/ >/dev/null
printf '%s\n' 'E2E-Smoke bestanden: Homeserver und lokales ntfy erreichbar.'
printf '%s\n' 'SDK-Login/Restore, E2EE-Entschlüsselung und Android-Benachrichtigung sind ohne Android-Runtime nicht ausgeführt.'
