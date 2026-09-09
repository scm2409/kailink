# Feature Seams: Speech (STT/TTS)

**Status: deliberately interfaces + no-op implementation only (principle G5).**
KaiLink is conceived as a voice-oriented device; the PoC defines the seams
so that later implementations can be plugged in without rebuilding the UI/logic.

## Seams (package `org.box44.kailink.domain.speech`)

```kotlin
/** Speech input: delivers German utterances with finality marking. */
interface SpeechTranscriber {
    fun start(): Flow<SpeechUtterance>
}
data class SpeechUtterance(val text: String, val isFinal: Boolean)

/** Speech output: short announcements (new message, error). */
interface SpeechSpeaker {
    suspend fun speak(announcement: SpeechAnnouncement)
}
data class SpeechAnnouncement(val text: String)
```

## Rules

1. `ui` and `domain` may use `SpeechTranscriber`/`SpeechSpeaker`,
   but must never import concrete speech SDKs.
2. Concrete implementations (e.g. on-device ASR, TTS engine) go **only**
   in `data/` and are wired in `AppGraph`.
3. Default wiring in the PoC: `NoOpSpeechTranscriber` (empty flow) and
   `NoOpSpeechSpeaker` (no-op). Both are JVM-testable.
4. Voice control does not replace platform-sanctioned mechanisms
   (e.g. no emulation of UI buttons via voice channels).

## Verification

- V1: JVM tests (no-op behavior, wiring via the `AppGraph` contract).
- V4 is omitted until a real implementation exists — there is nothing
  to observe on a device here.
