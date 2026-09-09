package org.box44.kailink

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncSettingsV2
import org.matrix.rustcomponents.sdk.TextMessageContent

/**
 * Raw SDK client for Bob (counterpart in the two-account E2E test).
 *
 * Bob deliberately does NOT run through [org.box44.kailink.data.matrix.MatrixSdkChannelClient]
 * but directly against matrix-rust-sdk — this way the E2E test really tests two
 * independent clients (SUT = Alice via the adapter, counterpart = Bob raw).
 *
 * UniFFI convention (sdk-android 26.09.08, verified with javap against classes.jar):
 * Every Rust `async fn` appears as a Kotlin `suspend fun`
 * (`Continuation` parameter in the bytecode). Concretely suspend:
 * `ClientBuilder.build`, `Client.login`, `Client.syncOnceV2`,
 * `Client.createRoom`, `Client.joinRoomById`, `Room.timeline`,
 * `Encryption.waitForE2eeInitializationTasks`, `Timeline.send`.
 * Synchronous: `ClientBuilder.homeserverUrl/sqliteStore/...` — immutable
 * (Rust: self: Arc<Self> -> Arc<Self>), CHAIN the return values, otherwise the
 * config is lost (proven by ClientBuildError without homeserver_url).
 * Also synchronous: `Client.rooms/getRoom/session/encryption`,
 * `Timeline.createMessageContent`, `SyncSettingsV2()` constructor.
 * Note: `client.syncService().finish()` needs sliding sync on the
 * server (Conduit: VersionIsMissing) — therefore syncOnce in the E2E against Conduit.
 */
class RawBobClient private constructor(
    val client: Client,
    private val storeScope: CoroutineScope,
    val stateDir: File,
) {
    suspend fun e2eeInit() {
        client.encryption().waitForE2eeInitializationTasks()
    }

    suspend fun joinRoom(roomId: String): Room = client.joinRoomById(roomId)

    suspend fun sendText(roomId: String, body: String) {
        val room = client.getRoom(roomId) ?: client.joinRoomById(roomId)
        val timeline = room.timeline()
        try {
            timeline.send(timeline.createMessageContent(MessageType.Text(TextMessageContent(body, null)))!!)
            // Queue upload: syncOnce (syncOnceV2) after sending.
        } finally {
            runCatching { timeline.close() }
        }
    }

    suspend fun syncOnce() {
        client.syncOnceV2(SyncSettingsV2())
    }

    fun userId(): String = client.userId()

    fun close() {
        runCatching { client.close() }
        runCatching { storeScope.cancel() }
    }

    companion object {
        suspend fun login(
            homeserverUrl: String,
            username: String,
            password: String,
            root: File,
        ): RawBobClient {
            val state = File(root, "bob-state").apply { mkdirs() }
            val cache = File(root, "bob-cache").apply { mkdirs() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // UniFFI builders are immutable (self: Arc<Self> -> Arc<Self>):
            // chain the setter return values, otherwise the config is lost.
            val client = ClientBuilder()
                .homeserverUrl(homeserverUrl)
                .sqliteStore(
                    SqliteStoreBuilder(
                        state.resolve("state.sqlite").absolutePath,
                        cache.absolutePath,
                    ),
                )
                .build()
            try {
                client.login(username, password, "KaiLinkE2E-Bob", null)
            } catch (t: Throwable) {
                runCatching { client.close() }
                scope.cancel()
                throw t
            }
            return RawBobClient(client, scope, state.parentFile ?: root)
        }
    }
}
