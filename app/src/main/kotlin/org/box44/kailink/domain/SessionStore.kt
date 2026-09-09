package org.box44.kailink.domain

import org.box44.kailink.domain.model.Session

/** Persistence contract for the domain session (implementation: data/session). */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}
