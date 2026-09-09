package org.box44.kailink.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.box44.kailink.domain.SessionStore
import org.box44.kailink.domain.model.Session
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-encrypted session persistence (Android only, production
 * wiring in `AppGraph`).
 *
 * - The AES-256/GCM key lives non-exportable in the AndroidKeyStore
 *   (alias [DEFAULT_KEY_ALIAS]); it is created on demand.
 * - The session is serialized as a `java.util.Properties` block (same
 *   keys as [FileSessionStore]) and written as `IV || ciphertext` to an
 *   app-private file; a random IV per `save()`.
 * - Read errors/missing files yield `null` (like [FileSessionStore]);
 *   the domain only sees the [SessionStore] contract (G4).
 *
 * JVM checks keep using [FileSessionStore]; the AndroidKeyStore is
 * not available on the JVM, therefore there is no JVM check for it
 * (documented limit, see docs/features/verification.md).
 */
class AndroidKeystoreSessionStore(
    private val file: File,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SessionStore {

    override fun load(): Session? {
        if (!file.exists()) return null
        val blob = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        if (blob.size <= IV_SIZE_BYTES) return null
        return try {
            val iv = blob.copyOfRange(0, IV_SIZE_BYTES)
            val ciphertext = blob.copyOfRange(IV_SIZE_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            toSession(cipher.doFinal(ciphertext))
        } catch (_: Exception) {
            null
        }
    }

    override fun save(session: Session) {
        file.parentFile?.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val ciphertext = cipher.doFinal(toProperties(session))
        file.outputStream().use { stream ->
            stream.write(cipher.iv)
            stream.write(ciphertext)
        }
    }

    override fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun toProperties(session: Session): ByteArray {
        val properties = Properties().apply {
            setProperty(FileSessionStore.KEY_USER_ID, session.userId)
            setProperty(FileSessionStore.KEY_DEVICE_ID, session.deviceId)
            setProperty(FileSessionStore.KEY_HOMESERVER_URL, session.homeserverUrl)
            setProperty(FileSessionStore.KEY_ACCESS_TOKEN, session.accessToken)
            session.refreshToken?.let { setProperty(FileSessionStore.KEY_REFRESH_TOKEN, it) }
        }
        val buffer = ByteArrayOutputStream()
        properties.store(buffer, null)
        return buffer.toByteArray()
    }

    private fun toSession(bytes: ByteArray): Session? {
        val properties = Properties().apply { load(bytes.inputStream()) }
        val userId = properties.getProperty(FileSessionStore.KEY_USER_ID) ?: return null
        val deviceId = properties.getProperty(FileSessionStore.KEY_DEVICE_ID) ?: return null
        val homeserverUrl = properties.getProperty(FileSessionStore.KEY_HOMESERVER_URL) ?: return null
        val accessToken = properties.getProperty(FileSessionStore.KEY_ACCESS_TOKEN) ?: return null
        return Session(
            userId = userId,
            deviceId = deviceId,
            homeserverUrl = homeserverUrl,
            accessToken = accessToken,
            refreshToken = properties.getProperty(FileSessionStore.KEY_REFRESH_TOKEN),
        )
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "kailink_session_key"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_SIZE_BYTES = 12
    }
}
