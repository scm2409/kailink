package org.box44.kailink.domain.model

/**
 * Persistente Matrix-Sitzung (Domänenmodell).
 * Die 1:1-Übersetzung zur SDK-Session passiert im Adapter (data/matrix).
 */
data class Session(
    val userId: String,
    val deviceId: String,
    val homeserverUrl: String,
    val accessToken: String,
    val refreshToken: String?,
)

/** Zusammenfassung eines Raums für die Raumliste. */
data class Room(
    val id: String,
    val displayName: String,
    val isEncrypted: Boolean,
    val lastMessage: Message?,
)

/** Richtung einer Nachricht relativ zur eigenen Sitzung. */
enum class MessageDirection { INCOMING, OUTGOING }

/** Zustand einer Nachricht aus App-Sicht. */
enum class DeliveryState {
    /** erfolgreich gesendet bzw. empfangen */
    SENT,

    /** empfangen, aber nicht entschlüsselbar (E2EE) */
    UNDECRYPTABLE,
}

/** Eine Chronik-Nachricht in Domänendarstellung. */
data class Message(
    /** Ereignis- oder Transaktions-ID (lokal stabiler Identifikator) */
    val id: String,
    val roomId: String,
    val sender: String,
    val body: String,
    val direction: MessageDirection,
    val state: DeliveryState,
    val timestampMillis: Long,
)
