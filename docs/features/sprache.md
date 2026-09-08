# Feature-Nahtstellen: Sprache (STT/TTS)

**Status: bewusst nur Schnittstellen + No-Op-Implementierung (Grundsatz G5).**
KaiLink ist als sprachnares Gerät gedacht; der PoC definiert die Nahtstellen,
damit spätere Implementierungen ohne Umbau der UI/Logik einsetzbar sind.

## Nahtstellen (Paket `at.d71.kailink.domain.speech`)

```kotlin
/** Spracheingabe: liefern Deutsche Utterances mit Finalitätsmarkierung. */
interface SpeechTranscriber {
    fun start(): Flow<SpeechUtterance>
}
data class SpeechUtterance(val text: String, val isFinal: Boolean)

/** Sprachausgabe: kurze Ansagen (neue Nachricht, Fehler). */
interface SpeechSpeaker {
    suspend fun speak(announcement: SpeechAnnouncement)
}
data class SpeechAnnouncement(val text: String)
```

## Regeln

1. `ui` und `domain` dürfen `SpeechTranscriber`/`SpeechSpeaker` benutzen,
   aber niemals konkrete Speech-SDKs importieren.
2. Konkrete Implementierungen (z. B. On-Device-ASR, TTS-Engine) kommen **nur**
   in `data/` und werden in `AppGraph` verdrahtet.
3. Standardverdrahtung im PoC: `NoOpSpeechTranscriber` (leerer Flow) und
   `NoOpSpeechSpeaker` (No-Op). Beide sind JVM-testbar.
4. Sprachsteuerung ersetzt keine Plattform-Sanctioned-Mechanismen
   (z. B. keine Emulation von UI-Tasten über Sprachkanäle).

## Verifikation

- V1: JVM-Tests (No-Op-Verhalten, Verdrahtung über `AppGraph`-Vertrag).
- V4 entfällt bis eine echte Implementierung existiert — hier gibt es nichts
  auf einem Gerät zu beobachten.
