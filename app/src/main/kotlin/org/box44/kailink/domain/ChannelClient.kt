package org.box44.kailink.domain

import org.box44.kailink.domain.model.Message
import org.box44.kailink.domain.model.Room
import org.box44.kailink.domain.model.Session
import kotlinx.coroutines.flow.Flow

/** Fehler mit deutscher Nutzerbotschaft aus der Kanalschicht. */
class ChannelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Ereignisse, die ein [ChannelClient] an die UI-Schicht meldet. */
sealed interface ChannelEvent {
    data class RoomsUpdated(val rooms: List<Room>) : ChannelEvent
    data class TimelineUpdated(val roomId: String, val messages: List<Message>) : ChannelEvent
    data class SyncStateChanged(val running: Boolean) : ChannelEvent
    data class ClientError(val message: String) : ChannelEvent
}

/**
 * Nahtstelle zwischen UI/Logik und einem Nachrichtendienst (Matrix).
 *
 * Implementierungen: `MatrixSdkChannelClient` (echtes matrix-rust-sdk,
 * produktionsnah) und `FakeChannelClient` (JVM-Tests).
 * Die Schnittstelle ist bewusst frei von Android- und SDK-Typen (G4).
 */
interface ChannelClient {
    /** Ereignisstrom; auf beliebigem Thread emittiert. */
    val events: Flow<ChannelEvent>

    /** Aktive Sitzung oder null, wenn nicht angemeldet. */
    val activeSession: Session?

    /** Neues Login (ersetzt eine vorhandene Sitzung). */
    suspend fun login(homeserverUrl: String, username: String, password: String): Session

    /** Wiederherstellung aus einer gespeicherten [Session]. */
    suspend fun restore(session: Session): Session

    /** Einmaliger Abgleich mit dem Server. */
    suspend fun syncOnce()

    /** Startet den Live-Sync im Vordergrund (idempotent). */
    suspend fun startLiveSync()

    /** Stoppt den Live-Sync (idempotent). */
    suspend fun stopLiveSync()

    /** Aktuelle Raumliste. */
    suspend fun rooms(): List<Room>

    /** Abonniert die Chronik eines Raums (idempotent). */
    suspend fun openTimeline(roomId: String)

    /** Gibt ein Chronik-Abo frei. */
    suspend fun closeTimeline(roomId: String)

    /** Sendet eine Textnachricht (Send-Queue des SDK). */
    suspend fun sendMessage(roomId: String, body: String)

    /** Meldet einen UnifiedPush-Endpoint als Matrix-Pusher an. */
    suspend fun registerPushEndpoint(endpointUrl: String)

    /** Lokale Abmeldung (PoC: Gerät bleibt serverseitig aktiv). */
    suspend fun logout()

    /** Gibt alle SDK-Ressourcen frei. */
    fun dispose()
}
