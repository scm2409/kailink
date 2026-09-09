# KaiLink — Agent Rules

## Toolchain
- JDK 21 is pinned in `mise.toml` (no separate install needed).
- Android SDK lives at `/home/dev/android-sdk`, referenced via `local.properties`.
- `adb` is not on PATH. Use `/home/dev/android-sdk/platform-tools/adb`.
- AVD `kailink-atd35` is API 35 with `atd` image. Boot headless (may be backgrounded):
  `/home/dev/android-sdk/emulator/emulator -avd kailink-atd35 -no-window -no-audio -no-boot-anim`
  then wait for `sys.boot_completed=1` before use.
- Rootless Podman 5.x supplies the E2E containers.

## Build & Test
- Debug APK: `./gradlew assembleDebug` — `--offline` is sufficient.
- JVM checks: `./gradlew testDebugUnitTest assembleDebug --offline`.

## E2E Gate
- Run `scripts/emulator-e2e.sh` only when `emulator-5554` is running.
- The script starts the Conduit homeserver and ntfy gateway as rootless containers, performs `adb reverse` for `tcp:6167` and `tcp:8090`, registers throwaway accounts, builds both APKs offline, and runs the two-account instrumented test; do not duplicate any of these steps.
- Credentials are supplied with `-e` arguments or `E2E_ALICE_*` / `E2E_BOB_*` environment variables.
- If the gate fails, diagnose the failure rather than masking it.

## Permissions & External Paths
- Permission allowlist: `/home/dev/.config/opencode/opencode.jsonc` (edit/bash permissions and `external_directory` records for SDK/toolchain paths).
- If a needed external path is blocked, add a record there rather than working around it.

## Hard Rules
- Never weaken gate checks or test assertions.
- Never edit `local.properties`.
- Never commit build outputs or `oc-proof*` throwaway files.
- Report observed exit codes and output; never claim results from commands that were not run.
