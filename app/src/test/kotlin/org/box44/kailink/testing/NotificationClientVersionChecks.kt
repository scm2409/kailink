package org.box44.kailink.testing

import org.box44.kailink.data.matrix.MatrixSdkChannelClient
import org.box44.kailink.data.session.FileSessionStore
import org.box44.kailink.domain.model.Session
import org.box44.kailink.domain.model.SlidingSyncMode
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Checks (0.2.8 bug, strict TDD — written RED against the 0.2.8 wiring,
 * green since the 0.2.9 forwarding fix) for the construction/wiring seam
 * of the push-path notification fetch.
 *
 * Confirmed bug: `MatrixSdkChannelClient.fetchNotification` constructs the
 * Rust SDK `NotificationClient` (`Client.notificationClient(
 * NotificationProcessSetup.MultipleProcesses)`) WITHOUT carrying the
 * detected/persisted `SlidingSyncMode`/version of the main client — on
 * matrix.org the main client detects NATIVE via
 * `SlidingSyncVersionBuilder.DISCOVER_NATIVE`, while the notification fetch
 * runs version-less and fails with `VersionIsMissing`.
 *
 * Target seam contract (asserted here):
 * - The notification-fetch construction takes the sliding sync version as an
 *   EXPLICIT input (`notificationClientFactory` seam: `(Client, version)`).
 * - The version flows from the main client's active session
 *   (`Session.slidingSyncMode`, mapped with
 *   `MatrixSdkChannelClient.domainModeToSdkVersion`) into that construction
 *   seam when `fetchNotification` runs — for NATIVE and for NONE (URL and
 *   mode are deliberately decorrelated across the two checks), and a
 *   session-less defensive state maps to `null` (SDK default semantics,
 *   no crash).
 *
 * Written RED first against the 0.2.8 wiring (version-less construction);
 * the 0.2.9 forwarding fix (`fetchNotification` maps the active session's
 * mode via `domainModeToSdkVersion` and passes it to
 * `obtainNotificationClient` → `notificationClientFactory`) makes all
 * three checks green.
 *
 * JVM-testability notes (no mock library in the offline repository):
 * - The Rust FFI `Client` cannot be built on the JVM; `Client(NoHandle)` is
 *   the UniFFI handle-less instance — a non-null construction token that
 *   never reaches an FFI call here because the injected recording factory
 *   does not touch it.
 * - The active-session state (normally established by `login`/`restore`
 *   against the network) is set via reflection on the private fields —
 *   test-only, no production hook is added.
 */
fun notificationClientVersionChecks() {

    Checks.check(
        "notification fetch: main client's detected NATIVE mode flows into the construction seam",
    ) {
        val recordedVersions = mutableListOf<SlidingSyncVersion?>()
        val recordedClients = mutableListOf<Client?>()
        val sut = MatrixSdkChannelClient(
            sessionStore = FileSessionStore(File(newTempRoot(), "session.properties")),
            storeDir = newTempRoot(),
            cacheDir = newTempRoot(),
            scope = CoroutineScope(Dispatchers.Default),
            notificationClientFactory = { client, version ->
                recordedClients.add(client)
                recordedVersions.add(version)
                null
            },
        )
        establishActiveState(
            sut,
            session = Session(
                userId = "@alice:example.org",
                deviceId = "DEVICE1",
                homeserverUrl = "https://matrix.org",
                accessToken = "token-123",
                refreshToken = null,
                slidingSyncMode = SlidingSyncMode.NATIVE,
            ),
        )
        val payload = runBlocking { sut.fetchNotification("!room:matrix.org", "\$evt123") }
        expectNull(payload, "recording factory returns null, so no payload (sanity)")
        expectEquals(1, recordedVersions.size, "construction seam invoked exactly once")
        expectNotNull(recordedClients[0], "construction seam receives the main client as construction token")
        expectEquals(
            SlidingSyncVersion.NATIVE,
            recordedVersions[0],
            "construction seam must receive the NATIVE version detected by the main client",
        )
    }

    Checks.check(
        "notification fetch: main client's NONE mode flows into the construction seam as NONE",
    ) {
        val recordedVersions = mutableListOf<SlidingSyncVersion?>()
        val sut = MatrixSdkChannelClient(
            sessionStore = FileSessionStore(File(newTempRoot(), "session.properties")),
            storeDir = newTempRoot(),
            cacheDir = newTempRoot(),
            scope = CoroutineScope(Dispatchers.Default),
            notificationClientFactory = { _, version ->
                recordedVersions.add(version)
                null
            },
        )
        establishActiveState(
            sut,
            session = Session(
                userId = "@alice:example.org",
                deviceId = "DEVICE1",
                // Deliberately matrix.org (the NATIVE-capable server of the
                // first check) with a NONE session: the URL and the mode are
                // decorrelated, so the pair of checks proves the version
                // comes from the session's mode, never from the homeserver
                // URL.
                homeserverUrl = "https://matrix.org",
                accessToken = "token-123",
                refreshToken = null,
                slidingSyncMode = SlidingSyncMode.NONE,
            ),
        )
        runBlocking { sut.fetchNotification("!room1:example.org", "\$evt456") }
        expectEquals(1, recordedVersions.size, "construction seam invoked exactly once")
        expectEquals(
            SlidingSyncVersion.NONE,
            recordedVersions[0],
            "construction seam must receive NONE for a classic-/sync session (no drop to version-less)",
        )
    }

    Checks.check(
        "notification fetch: without a session mode the seam receives null (SDK default semantics)",
    ) {
        val recordedVersions = mutableListOf<SlidingSyncVersion?>()
        val recordedClients = mutableListOf<Client?>()
        val sut = MatrixSdkChannelClient(
            sessionStore = FileSessionStore(File(newTempRoot(), "session.properties")),
            storeDir = newTempRoot(),
            cacheDir = newTempRoot(),
            scope = CoroutineScope(Dispatchers.Default),
            notificationClientFactory = { client, version ->
                recordedClients.add(client)
                recordedVersions.add(version)
                null
            },
        )
        // Defensive branch: a client without an active session (cannot occur
        // through the production login/restore paths, which set both fields
        // together) must map to a null version — never a crash (a `!!`
        // mapping would pass the NATIVE/NONE checks yet fail here).
        klassSetActiveClientOnly(sut)
        runBlocking { sut.fetchNotification("!room1:example.org", "\$evt789") }
        expectEquals(1, recordedVersions.size, "construction seam invoked exactly once")
        expectNotNull(recordedClients[0], "construction seam receives the client token")
        expectNull(recordedVersions[0], "null session mode maps to a null version (SDK default semantics)")
    }
}

/** Establishes the active main-client state on the JVM (test-only seam via reflection). */
private fun establishActiveState(sut: MatrixSdkChannelClient, session: Session) {
    klassSetActiveClientOnly(sut)
    MatrixSdkChannelClient::class.java.getDeclaredField("activeSession")
        .apply { isAccessible = true }.set(sut, session)
}

/** Sets only the private `client` field; `activeSession` stays null. */
private fun klassSetActiveClientOnly(sut: MatrixSdkChannelClient) {
    MatrixSdkChannelClient::class.java.getDeclaredField("client")
        .apply { isAccessible = true }
        .set(sut, Client(noHandleInstance()))
}

/**
 * `NoHandle` is a Kotlin `object` in the pinned SDK: its Java-level
 * `INSTANCE` static is hidden from Kotlin member resolution, so it is read
 * via reflection (still a pure JVM object — no native call involved).
 */
private fun noHandleInstance(): NoHandle =
    NoHandle::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null) as NoHandle

private fun newTempRoot(): File = Files.createTempDirectory("kailink-notification-checks").toFile()
