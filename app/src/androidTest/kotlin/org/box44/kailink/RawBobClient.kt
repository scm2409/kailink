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
 * Roher SDK-Client für Bob (Gegenstelle im Zwei-Konten-E2E-Test).
 *
 * Bob läuft bewusst NICHT über [org.box44.kailink.data.matrix.MatrixSdkChannelClient],
 * sondern direkt gegen matrix-rust-sdk — so testet der E2E-Test wirklich zwei
 * unabhängige Clients (SUT = Alice über den Adapter, Gegenstelle = Bob roh).
 *
 * UniFFI-Konvention (sdk-android 26.09.08, per javap gegen classes.jar verifiziert):
 * Jede Rust-`async fn` erscheint als Kotlin-`suspend fun`
 * (`Continuation`-Parameter im Bytecode). Konkret suspend:
 * `ClientBuilder.build`, `Client.login`, `Client.syncOnceV2`,
 * `Client.createRoom`, `Client.joinRoomById`, `Room.timeline`,
 * `Encryption.waitForE2eeInitializationTasks`, `Timeline.send`.
 * Synchron: `ClientBuilder.homeserverUrl/sqliteStore/...` — immutable
 * (Rust: self: Arc<Self> -> Arc<Self>), Rueckgaben VERKETTEN, sonst geht die
 * Config verloren (belegt durch ClientBuildError ohne homeserver_url).
 * Synchron weiter: `Client.rooms/getRoom/session/encryption`,
 * `Timeline.createMessageContent`, `SyncSettingsV2()`-Konstruktor.
 * Hinweis: `client.syncService().finish()` braucht Sliding Sync auf dem
 * Server (Conduit: VersionIsMissing) — im E2E gegen Conduit daher syncOnce.
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
            // Upload der Queue: syncOnce (syncOnceV2) nach dem Senden.
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
            // UniFFI-Builder sind immutable (self: Arc<Self> -> Arc<Self>):
            // Setter-Rueckgaben verketten, sonst geht die Config verloren.
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
