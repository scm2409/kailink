package at.d71.kailink.domain.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Nahtstelle Spracheingabe (STT) — siehe docs/features/sprache.md.
 * Konkrete Implementierungen kommen in `data/`, niemals in UI/Logik (G5).
 */
interface SpeechTranscriber {
    fun start(): Flow<SpeechUtterance>
}

data class SpeechUtterance(
    val text: String,
    /** true = endgültiges Erkennungsergebnis */
    val isFinal: Boolean,
)

/** Nahtstelle Sprachausgabe (TTS) — siehe docs/features/sprache.md. */
interface SpeechSpeaker {
    suspend fun speak(announcement: SpeechAnnouncement)
}

data class SpeechAnnouncement(val text: String)

/** Dokumentierte No-Op-Implementierung (PoC-Standardverdrahtung). */
class NoOpSpeechTranscriber : SpeechTranscriber {
    override fun start(): Flow<SpeechUtterance> = emptyFlow()
}

/** Dokumentierte No-Op-Implementierung (PoC-Standardverdrahtung). */
class NoOpSpeechSpeaker : SpeechSpeaker {
    override suspend fun speak(announcement: SpeechAnnouncement) = Unit
}
