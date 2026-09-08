package at.d71.kailink.domain

import at.d71.kailink.domain.model.Session

/** Persistenzvertrag für die Domänensitzung (Implementierung: data/session). */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}
