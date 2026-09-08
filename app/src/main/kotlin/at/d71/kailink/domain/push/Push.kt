package at.d71.kailink.domain.push

/** Zustand der UnifiedPush-Registrierung (für die UI-Anzeige). */
enum class PushState {
    /** Kein UnifiedPush-Distributor installiert. */
    NOT_AVAILABLE,

    /** Distributor vorhanden, Registrierung läuft. */
    READY,

    /** Endpoint empfangen und als Matrix-Pusher angemeldet. */
    REGISTERED,

    /** Registrierung fehlgeschlagen. */
    FAILED,
}

/**
 * Nahtstelle zum Anstoßen der Push-Registrierung, ohne Android-Typen in der
 * UI-Schicht (Implementierung: `UnifiedPushRegistrar`).
 */
interface PushRegistrationTrigger {
    fun tryRegister()
}
