package org.box44.kailink.domain.push

/** State of the UnifiedPush registration (for UI display). */
enum class PushState {
    /** No UnifiedPush distributor installed. */
    NOT_AVAILABLE,

    /** Distributor available, registration in progress. */
    READY,

    /** Endpoint received and registered as a Matrix pusher. */
    REGISTERED,

    /** Registration failed. */
    FAILED,
}

/**
 * Seam for triggering push registration without Android types in the
 * UI layer (implementation: `UnifiedPushRegistrar`).
 */
interface PushRegistrationTrigger {
    fun tryRegister()
}
