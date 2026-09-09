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

    Checks.check("Login with empty fields is rejected") {
        val client = InMemoryChannelClient(FakeSessionStore())
        try {
            runBlocking { client.login("https://phase1.local", "", "secret") }
            throw CheckFailure("ChannelException expected")
        } catch (expected: ChannelException) {
            expectTrue(expected.message!!.contains("sign-in fields"), "error message")
        }
    }

    Checks.check("Login creates a session, stores it and reports rooms") {
        val store = FakeSessionStore()
        val client = InMemoryChannelClient(store)
        val recorder = EventRecorder(client)

        runBlocking { client.login("https://phase1.local", "alice", "secret") }

        expectNotNull(client.activeSession, "active session after login")
        expectTrue(client.activeSession!!.userId.startsWith("@alice:"), "user ID")
        expectEquals(1, store.saveCalls, "Session persisted")
        expectTrue(
            recorder.events.any { it is ChannelEvent.RoomsUpdated },
            "RoomsUpdated sent",
        )
        recorder.stop()
    }

    Checks.check("Send creates an outgoing message and timeline event") {
        val client = InMemoryChannelClient(FakeSessionStore())
        val recorder = EventRecorder(client)

        runBlocking {
            client.login("https://phase1.local", "alice", "secret")
            client.openTimeline("!phase1-project:phase1.local")
            client.sendMessage("!phase1-project:phase1.local", "Hello Phase 1")
        }

        val timeline = recorder.events.filterIsInstance<ChannelEvent.TimelineUpdated>().last()
        expectTrue(timeline.messages.isNotEmpty(), "Timeline not empty")
        val sent = timeline.messages.last()
        expectEquals("Hello Phase 1", sent.body, "Message text")
        expectEquals(MessageDirection.OUTGOING, sent.direction, "Direction")
        recorder.stop()
    }

    Checks.check("Unknown room is rejected") {
        val client = InMemoryChannelClient(FakeSessionStore())
        runBlocking { client.login("https://phase1.local", "alice", "secret") }
        try {
            runBlocking { client.openTimeline("!unknown:phase1.local") }
            throw CheckFailure("ChannelException expected")
        } catch (expected: ChannelException) {
            expectTrue(expected.message!!.contains("Unknown room"), "error message")
        }
    }

    Checks.check("Push endpoint registration without session is rejected") {
        val client = InMemoryChannelClient(FakeSessionStore())
        try {
            runBlocking { client.registerPushEndpoint("https://push.phase1.local/endpoint") }
            throw CheckFailure("ChannelException expected")
        } catch (expected: ChannelException) {
            expectTrue(expected.message!!.contains("No active session"), "error message")
        }
    }

    Checks.check("Logout clears session and store") {
        val store = FakeSessionStore()
        val client = InMemoryChannelClient(store)
        runBlocking {
            client.login("https://phase1.local", "alice", "secret")
            client.logout()
        }
        expectNull(client.activeSession, "Session ended")
        expectEquals(1, store.clearCalls, "Store cleared")
        expectNull(store.load(), "nothing loaded")
    }
}
