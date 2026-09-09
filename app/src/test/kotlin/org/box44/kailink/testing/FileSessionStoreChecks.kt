package org.box44.kailink.testing

import org.box44.kailink.data.session.FileSessionStore
import org.box44.kailink.domain.model.Session
import java.io.File
import java.nio.file.Files

fun fileSessionStoreChecks() {
    val session = Session(
        userId = "@alice:example.org",
        deviceId = "DEVICE1",
        homeserverUrl = "https://matrix.example.org",
        accessToken = "token-123",
        refreshToken = "refresh-456",
    )

    Checks.check("Save and load yields the same session") {
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val store = FileSessionStore(File(root, "kailink/session.properties"))
        store.save(session)
        expectEquals(session, store.load(), "Round trip")
        root.deleteRecursively()
    }

    Checks.check("Load without a file yields null") {
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val store = FileSessionStore(File(root, "does/not/exist/session.properties"))
        expectNull(store.load(), "Missing file")
        root.deleteRecursively()
    }

    Checks.check("Clear removes the session") {
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val store = FileSessionStore(File(root, "session.properties"))
        store.save(session)
        store.clear()
        expectNull(store.load(), "After clear()")
        root.deleteRecursively()
    }

    Checks.check("Refresh token may be absent") {
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val withoutRefresh = session.copy(refreshToken = null)
        val store = FileSessionStore(File(root, "session.properties"))
        store.save(withoutRefresh)
        expectEquals(withoutRefresh, store.load(), "Round trip without refresh token")
        root.deleteRecursively()
    }
}
