package org.box44.kailink.data.session

import org.box44.kailink.domain.SessionStore
import org.box44.kailink.domain.model.Session
import java.io.File
import java.io.FileNotFoundException
import java.util.Properties

/**
 * Dateibasierte Sitzungspersistenz (App-privates Verzeichnis).
 *
 * PoC-Bewusst ohne Keystore-Verschlüsselung; für Produktion ist
 * EncryptedFile/Keystore Pflicht (offener Punkt in docs/features/verification.md).
 */
class FileSessionStore(
    private val file: File,
) : SessionStore {

    override fun load(): Session? {
        if (!file.exists()) return null
        val properties = try {
            Properties().apply {
                file.inputStream().use(::load)
            }
        } catch (_: FileNotFoundException) {
            return null
        } catch (_: Exception) {
            return null
        }
        val userId = properties.getProperty(KEY_USER_ID) ?: return null
        val deviceId = properties.getProperty(KEY_DEVICE_ID) ?: return null
        val homeserverUrl = properties.getProperty(KEY_HOMESERVER_URL) ?: return null
        val accessToken = properties.getProperty(KEY_ACCESS_TOKEN) ?: return null
        return Session(
            userId = userId,
            deviceId = deviceId,
            homeserverUrl = homeserverUrl,
            accessToken = accessToken,
            refreshToken = properties.getProperty(KEY_REFRESH_TOKEN),
        )
    }

    override fun save(session: Session) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty(KEY_USER_ID, session.userId)
            setProperty(KEY_DEVICE_ID, session.deviceId)
            setProperty(KEY_HOMESERVER_URL, session.homeserverUrl)
            setProperty(KEY_ACCESS_TOKEN, session.accessToken)
            session.refreshToken?.let { setProperty(KEY_REFRESH_TOKEN, it) }
        }
        file.outputStream().use { stream ->
            properties.store(stream, "KaiLink session (app-private, unencrypted PoC)")
        }
    }

    override fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }

    private companion object {
        const val KEY_USER_ID = "userId"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_HOMESERVER_URL = "homeserverUrl"
        const val KEY_ACCESS_TOKEN = "accessToken"
        const val KEY_REFRESH_TOKEN = "refreshToken"
    }
}
