# Manual Test Protocol (V4, Device/Emulator)

**App:** KaiLink Phase 1, debug build `app-debug.apk`
(`applicationId org.box44.kailink`, versionName `0.2.0-phase1`, minSdk 28).

**Preparation (once):**

1. Device/emulator with Android 9+ (API 28+); **no** Google account needed.
2. Installation: `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. **Phase-1 particularity:** the channel service is an in-memory simulation.
   Login credentials are **not** checked against a real homeserver;
   any non-empty combination is valid. No
   network connections are established.
4. The protocol leader records date, device ID, and observed deviations.
   **Every test case needs a result "passed/failed" +
   observation.**

> Everything that is only "described" here counts in
> [`features/verification.md`](features/verification.md) as
> **not observed**, until the protocol has been filled with results.

---

## MT-1 Login (channel simulation)

1. Start KaiLink → the login screen appears (title "KaiLink").
2. Leave all fields empty → "Sign in".
3. **Expectation:** error message "Please fill in all fields.",
   no login attempt, no crash.
4. Homeserver URL `https://phase1.local`, username `alice`, password
   `secret` → "Sign in".
5. **Expectation:** the button shows "Please wait …" with a progress indicator,
   then a switch to the room list; the header shows "Signed in:
   @alice:phase1.local"; two demo rooms appear ("Project Channel Phase 1"
   with 🔒, "Notes" without a badge).

## MT-2 Session Restoration

1. MT-1 successful; exit the app via system back; lock/unlock the device.
2. Start KaiLink again.
3. **Expectation:** no renewed password prompt — the login screen shows
   "Restoring session …" and jumps straight into the room list;
   the same user ID as in MT-1.
4. **Failure case (optional):** corrupt `session.properties` before the start
   (e.g. empty file under
   `/data/data/org.box44.kailink/files/kailink/`, checkable only with a debug build
   via `run-as`) → restoration fails, an error
   message appears, the session file is deleted, the login remains usable.

## MT-3 Room List & Opening the Timeline

1. In the room list tap "Project Channel Phase 1".
2. **Expectation:** the timeline opens; two welcome messages appear;
   the header area shows the room name and a "Back" button.
3. Return to the room list; enter the same room again: the timeline stays
   consistent (messages twice as many, no duplicates in content).

## MT-4 Sending

1. In the opened room type a text message and tap "Send".
2. **Expectation:** the message appears immediately as an outgoing bubble
   (green background), the send field is cleared.
3. **Failure case:** enter only whitespace → "Send" has no effect
   (no whitespace-only empty entry in the timeline).
4. Open the room list: the sent message appears as a preview under the
   room name.

## MT-5 Live-Sync Event

1. In the timeline "Refresh" is only visible in the room list; there
   tap "Refresh".
2. **Expectation:** no error message; the room list stays stable (the sync runs
   against the simulation; starting live sync does not change the list
   unexpectedly).

## MT-6 Push Chain (Simulator)

1. After login, observe the push row in the room list.
2. **Expectation:** "Push: registered (UnifiedPush)" — the
   simulated distributor delivers endpoint and registration
   (`PushState.REGISTERED`); no dialog, no Google service.
3. Without login (fresh installation, before MT-1): after a registration
   attempt the state "no distributor"/"failed" would be possible —
   the app remains usable in every case.

## MT-7 Rotation/Restart of the Activity

1. Rotate the device (force activity destruction).
2. **Expectation:** the app does not crash; the currently visible screen
   appears again; in Phase 1 the UI state of the timeline is loaded again after the
   rebuild (the simulation restarts, demo rooms present).

## MT-8 Negative Check: No Network Access

1. Enable airplane mode, restart the app completely, repeat MT-1 to MT-4.
2. **Expectation:** identical behavior as online (Phase 1 deliberately
   establishes no connections); no crashes.

---

## Result Note

| Test case | Date | Result | Observation |
| --- | --- | --- | --- |
| MT-1 | – | not observed | – |
| MT-2 | – | not observed | – |
| MT-3 | – | not observed | – |
| MT-4 | – | not observed | – |
| MT-5 | – | not observed | – |
| MT-6 | – | not observed | – |
| MT-7 | – | not observed | – |
| MT-8 | – | not observed | – |
