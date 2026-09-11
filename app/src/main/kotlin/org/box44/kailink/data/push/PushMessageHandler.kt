package org.box44.kailink.data.push

import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.SessionStore
import org.box44.kailink.domain.model.MessageDirection
import org.box44.kailink.domain.model.Room
import org.box44.kailink.domain.model.SlidingSyncMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Real push→notification path of the UnifiedPush chain (JVM-testable;
 * Android rendering stays in [PushNotifier], the SDK resolution in
 * `MatrixSdkChannelClient.fetchNotification`).
 *
 * Called by `KaiLinkPushReceiver.onMessage` for every UnifiedPush MESSAGE
 * broadcast (Element X pattern: parse → session → sync → resolve →
 * render). Unlike the simulated path ([PushController.onMessage]) it also
 * covers the cold-start case: a push normally wakes a dead process, so
 * the handler restores the persisted session before syncing.
 *
 * Steps (serialized by a mutex — pushes can arrive in bursts):
 * 1. Parse the raw message ([PushPayload]); `null` (foreign/invalid
 *    payload) only downgrades to wake-up semantics, never drops the sync —
 *    since 0.2.10 the failure line carries the WHY plus a redacted shape
 *    summary ([PushPayload.parseFailureSummary], no content/tokens/URLs).
 * 2. Ensure the channel has a session (restore from the [SessionStore]).
 *    Since 0.2.10 the handler logs the slidingSyncMode it will operate
 *    with and its source: `persisted store` (read from the persisted
 *    session before the restore) vs `in-memory session` (already active,
 *    e.g. a legacy/default mode); a persisted-vs-in-memory mismatch and a
 *    restore-time reconciliation are logged explicitly.
 * 3. `syncOnce()` — the push-triggered catch-up.
 * 4. Resolve the notification: the SDK `NotificationClient` for the parsed
 *    room/event; without ids or on failure the room-list fallback
 *    ([PushNotificationPayload.fromRoom]). Since 0.2.10 the fallback
 *    outcome is visible: pushed room ID only, unread count, selected room,
 *    encrypted flag, render/suppress and the suppression reason.
 *
 * Returns the payload to render, or `null` (no session, sync failed,
 * nothing notifiable).
 */
class PushMessageHandler(
    private val channelClient: ChannelClient,
    private val sessionStore: SessionStore,
    private val resolveNotification: suspend (PushPayload?) -> PushNotificationPayload?,
    private val onLog: (String) -> Unit = {},
) {

    private val mutex = Mutex()

    suspend fun handle(rawMessage: ByteArray?): PushNotificationPayload? = mutex.withLock {
        val payload = PushPayload.parse(rawMessage)
        if (payload == null) {
            val summary = PushPayload.parseFailureSummary(rawMessage)
            onLog(
                if (summary == null) {
                    "Push: not a Matrix push payload — wake-up sync only"
                } else {
                    "Push: not a Matrix push payload — wake-up sync only ($summary)"
                },
            )
        }
        if (!ensureSession()) return null
        try {
            channelClient.syncOnce()
        } catch (t: Throwable) {
            onLog("Push sync failed: ${t.message}")
            return null
        }
        val resolved = runCatching { resolveNotification(payload) }
            .onFailure { onLog("Notification resolution failed: ${it.message}") }
            .getOrNull()
        resolved ?: fallbackFromRooms(payload)
    }

    private suspend fun ensureSession(): Boolean {
        channelClient.activeSession?.let { active ->
            onLog(
                "Push: session active; slidingSyncMode=${active.slidingSyncMode} " +
                    "(source: in-memory session)",
            )
            logPersistedMismatch(active.slidingSyncMode)
            return true
        }
        val stored = sessionStore.load()
        if (stored == null) {
            onLog("Push dropped: no active or stored session")
            return false
        }
        // 0.2.10: read (and log) the PERSISTED mode before the restore — the
        // restore itself reconciles a stale record with the re-detected mode
        // and persists it synchronously (MatrixSdkChannelClient.restore),
        // so the notification resolution below runs on a persisted, current
        // mode (docs/decisions.md, 0.2.10).
        onLog("Push cold start: persisted slidingSyncMode=${stored.slidingSyncMode} (source: persisted store)")
        return try {
            channelClient.restore(stored)
            onLog("Push cold start: session restored")
            channelClient.activeSession?.let { active ->
                if (active.slidingSyncMode != stored.slidingSyncMode) {
                    onLog(
                        "Push: slidingSyncMode reconciled at restore: " +
                            "persisted=${stored.slidingSyncMode}, active=${active.slidingSyncMode}",
                    )
                }
            }
            true
        } catch (t: Throwable) {
            onLog("Push session restore failed")
            false
        }
    }

    /** Warm-start diagnostic: persisted record vs the in-memory session mode. */
    private fun logPersistedMismatch(activeMode: SlidingSyncMode) {
        val persisted = runCatching { sessionStore.load() }.getOrNull()?.slidingSyncMode ?: return
        if (persisted != activeMode) {
            onLog("Push: slidingSyncMode mismatch persisted=$persisted in-memory=$activeMode")
        }
    }

    private suspend fun fallbackFromRooms(payload: PushPayload?): PushNotificationPayload? {
        val rooms = runCatching { channelClient.rooms() }
            .onFailure { onLog("Push room list failed: ${it.message}") }
            .getOrDefault(emptyList())
        val outcome = fallbackOutcome(rooms, payload, channelClient.activeSession?.slidingSyncMode)
        outcome.lines.forEach(onLog)
        return outcome.payload
    }

    companion object {

        /**
         * Pure room-list fallback outcome (JVM-testable): the payload is
         * exactly [PushNotificationPayload.fromRoom]'s result; the line
         * makes the outcome visible with room IDs only — never display
         * names or message bodies (G7-safe for the shared debug log).
         */
        internal fun fallbackOutcome(
            rooms: List<Room>,
            payload: PushPayload?,
            mode: SlidingSyncMode?,
        ): FallbackOutcome {
            val pushedRoomId = payload?.roomId?.takeIf { it.isNotBlank() }
            val target = pushedRoomId?.let { id -> rooms.firstOrNull { it.id == id } }
            val rendered = PushNotificationPayload.fromRoom(rooms, pushedRoomId)
            val selectedRoom: Room? = when {
                target != null -> target
                rendered != null -> rooms.firstOrNull { it.id == rendered.roomId }
                else -> latestIncomingRoom(rooms)
            }
            val (reason, suppress) = when {
                rooms.isEmpty() -> "room list empty" to true
                pushedRoomId == null ->
                    if (rendered == null) "no incoming message in any synced room" to true else null to false
                target == null ->
                    if (rendered == null) {
                        "pushed room not in synced room list; no incoming message in any synced room" to true
                    } else {
                        null to false
                    }
                target.lastMessage == null ->
                    "pushed room has no last message in the synced room list" to true
                target.lastMessage?.direction != MessageDirection.INCOMING ->
                    "pushed room's last message is not incoming" to true
                else -> null to false
            }
            val encrypted = selectedRoom?.isEncrypted?.toString() ?: "unknown"
            val base = "Push room-list fallback (mode=${mode?.name ?: "unknown"}): " +
                "pushed room=${pushedRoomId ?: "none"}, " +
                "unread=${payload?.unread?.toString() ?: "unknown"}, " +
                "selected room=${selectedRoom?.id ?: "none"}, encrypted=$encrypted"
            val line = if (suppress) "$base, outcome=suppress, reason=$reason" else "$base, outcome=render"
            return FallbackOutcome(rendered, listOf(line))
        }

        /** Mirrors [PushNotificationPayload.fromLatest]'s room selection. */
        private fun latestIncomingRoom(rooms: List<Room>): Room? =
            rooms.filter { it.lastMessage?.direction == MessageDirection.INCOMING }
                .maxByOrNull { it.lastMessage?.timestampMillis ?: 0L }
    }
}

/** Room-list fallback outcome: payload to render (or `null`) plus its visible log lines. */
internal data class FallbackOutcome(
    val payload: PushNotificationPayload?,
    val lines: List<String>,
)
