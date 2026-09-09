package org.box44.kailink.data.session

import org.box44.kailink.domain.model.SlidingSyncMode

/**
 * Shared (de)serialization helpers for the property-based session
 * stores ([FileSessionStore], [AndroidKeystoreSessionStore]).
 */
internal object SessionStoreSupport {

    /**
     * Parses the persisted [SlidingSyncMode] name; unknown or missing
     * values (sessions persisted before 0.2.5-phase1) degrade to NONE.
     */
    fun parseMode(name: String?): SlidingSyncMode =
        name?.let { runCatching { SlidingSyncMode.valueOf(it) }.getOrNull() } ?: SlidingSyncMode.NONE
}
