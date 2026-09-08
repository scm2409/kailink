package org.box44.kailink.testing

import org.box44.kailink.data.channel.InMemoryChannelClient
import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.ChannelEvent
import org.box44.kailink.domain.ChannelException
import org.box44.kailink.domain.model.MessageDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private class EventRecorder(client: ChannelClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val events = mutableListOf<ChannelEvent>()
    private val job: Job = scope.launch {
        client.events.collect { events.add(it) }
    }

    fun stop() {
        job.cancel()
        scope.cancel()
    }
}

fun inMemoryChannelClientChecks() {

    Checks.check("Anmeldung mit leeren Feldern wird abgelehnt") {
        val client = InMemoryChannelClient(FakeSessionStore())
        try {
            runBlocking { client.login("https://phase1.local", "", "geheim") }
            throw CheckFailure("ChannelException erwartet")
        } catch (expected: ChannelException) {
            expectTrue(expected.message!!.contains("Anmeldefelder"), "deutsche Fehlermeldung")
        }
    }

    Checks.check("Anmeldung erzeugt Sitzung, speichert sie und meldet Räume") {
        val store = FakeSessionStore()
        val client = InMemoryChannelClient(store)
        val recorder = EventRecorder(client)

        runBlocking { client.login("https://phase1.local", "alice", "geheim") }

        expectNotNull(client.activeSession, "aktive Sitzung nach Login")
        expectTrue(client.activeSession!!.userId.startsWith("@alice:"), "Nutzerkennung")
        expectEquals(1, store.saveCalls, "Sitzung persistiert")
        expectTrue(
            recorder.events.any { it is ChannelEvent.RoomsUpdated },
            "RoomsUpdated gesendet",
        )
        recorder.stop()
    }

    Checks.check("Senden erzeugt ausgehende Nachricht und Chronik-Ereignis") {
        val client = InMemoryChannelClient(FakeSessionStore())
        val recorder = EventRecorder(client)

        runBlocking {
            client.login("https://phase1.local", "alice", "geheim")
            client.openTimeline("!phase1-projekt:phase1.local")
            client.sendMessage("!phase1-projekt:phase1.local", "Hallo Phase 1")
        }

        val timeline = recorder.events.filterIsInstance<ChannelEvent.TimelineUpdated>().last()
        expectTrue(timeline.messages.isNotEmpty(), "Chronik nicht leer")
        val sent = timeline.messages.last()
        expectEquals("Hallo Phase 1", sent.body, "Nachrichtentext")
        expectEquals(MessageDirection.OUTGOING, sent.direction, "Richtung")
        recorder.stop()
    }

    Checks.check("Unbekannter Raum wird abgelehnt") {
        val client = InMemoryChannelClient(FakeSessionStore())
        runBlocking { client.login("https://phase1.local", "alice", "geheim") }
        try {
            runBlocking { client.openTimeline("!unbekannt:phase1.local") }
            throw CheckFailure("ChannelException erwartet")
        } catch (expected: ChannelException) {
            expectTrue(expected.message!!.contains("Unbekannter Raum"), "Fehlermeldung")
        }
    }

    Checks.check("Push-Endpoint-Registrierung ohne Sitzung wird abgelehnt") {
        val client = InMemoryChannelClient(FakeSessionStore())
        try {
            runBlocking { client.registerPushEndpoint("https://push.phase1.local/endpoint") }
            throw CheckFailure("ChannelException erwartet")
        } catch (expected: ChannelException) {
            expectTrue(expected.message!!.contains("Keine aktive Sitzung"), "Fehlermeldung")
        }
    }

    Checks.check("Abmeldung löscht Sitzung und Speicher") {
        val store = FakeSessionStore()
        val client = InMemoryChannelClient(store)
        runBlocking {
            client.login("https://phase1.local", "alice", "geheim")
            client.logout()
        }
        expectNull(client.activeSession, "Sitzung beendet")
        expectEquals(1, store.clearCalls, "Speicher geleert")
        expectNull(store.load(), "nichts geladen")
    }
}
