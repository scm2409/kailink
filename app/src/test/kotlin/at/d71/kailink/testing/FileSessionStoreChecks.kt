package at.d71.kailink.testing

import at.d71.kailink.data.session.FileSessionStore
import at.d71.kailink.domain.model.Session
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

    Checks.check("Speichern und Laden ergibt dieselbe Sitzung") {
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val store = FileSessionStore(File(root, "kailink/session.properties"))
        store.save(session)
        expectEquals(session, store.load(), "Round-Trip")
        root.deleteRecursively()
    }

    Checks.check("Laden ohne Datei ergibt null") {
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val store = FileSessionStore(File(root, "nicht/vorhanden/session.properties"))
        expectNull(store.load(), "Fehlende Datei")
        root.deleteRecursively()
    }

    Checks.check("Clear entfernt die Sitzung") {
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val store = FileSessionStore(File(root, "session.properties"))
        store.save(session)
        store.clear()
        expectNull(store.load(), "Nach clear()")
        root.deleteRecursively()
    }

    Checks.check("Refresh-Token darf fehlen") {
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val withoutRefresh = session.copy(refreshToken = null)
        val store = FileSessionStore(File(root, "session.properties"))
        store.save(withoutRefresh)
        expectEquals(withoutRefresh, store.load(), "Round-Trip ohne Refresh-Token")
        root.deleteRecursively()
    }
}
