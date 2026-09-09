package org.box44.kailink.data.push

import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.SessionStore
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
 *    payload) only downgrades to wake-up semantics, never drops the sync.
 * 2. Ensure the channel has a session (restore from the [SessionStore]).
 * 3. `syncOnce()` — the push-triggered catch-up.
 * 4. Resolve the notification: the SDK `NotificationClient` for the parsed
 *    room/event; without ids or on failure the room-list fallback
 *    ([PushNotificationPayload.fromRoom]).
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
            onLog("Push: not a Matrix push payload — wake-up sync only")
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
        if (channelClient.activeSession != null) return true
        val stored = sessionStore.load()
        if (stored == null) {
            onLog("Push dropped: no active or stored session")
            return false
        }
        return try {
            channelClient.restore(stored)
            onLog("Push cold start: session restored (${stored.userId})")
            true
        } catch (t: Throwable) {
            onLog("Push session restore failed: ${t.message}")
            false
        }
    }

    private suspend fun fallbackFromRooms(payload: PushPayload?): PushNotificationPayload? =
        runCatching { channelClient.rooms() }
            .onFailure { onLog("Push room list failed: ${it.message}") }
            .getOrDefault(emptyList())
            .let { PushNotificationPayload.fromRoom(it, payload?.roomId) }
}
