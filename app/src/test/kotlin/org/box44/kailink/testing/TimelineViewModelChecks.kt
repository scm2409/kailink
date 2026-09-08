package org.box44.kailink.testing

import org.box44.kailink.domain.ChannelEvent
import org.box44.kailink.ui.timeline.TimelineViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

private const val ROOM_ID = "!room1:example.org"

fun timelineViewModelChecks() {

    fun viewModelScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    Checks.check("Chronik-Ereignisse filtern nach Raum-ID") {
        val client = FakeChannelClient()
        val viewModel = TimelineViewModel(ROOM_ID, client, viewModelScope())

        runBlocking {
            client.emit(ChannelEvent.TimelineUpdated(ROOM_ID, listOf(testMessage("m1"), testMessage("m2"))))
            client.emit(ChannelEvent.TimelineUpdated("!anderer:raum", listOf(testMessage("fremd", "!anderer:raum"))))
        }

        expectEquals(
            listOf("m1", "m2"),
            viewModel.ui.value.messages.map { it.id },
            "nur Nachrichten des eigenen Raums",
        )
        expectTrue(
            viewModel.ui.value.messages.none { it.id == "fremd" },
            "fremde Raum-Nachricht verworfen",
        )
        viewModel.clear()
    }

    Checks.check("Öffnen der Chronik abonniert und synchronisiert") {
        val client = FakeChannelClient()

        val viewModel = TimelineViewModel(ROOM_ID, client, viewModelScope())

        expectEquals(listOf(ROOM_ID), client.openTimelineCalls, "openTimeline")
        expectTrue(client.syncOnceCalls >= 1, "mindestens ein syncOnce")
        viewModel.clear()
    }

    Checks.check("Senden leert Entwurf und delegiert mit getrimmtem Text") {
        val client = FakeChannelClient()
        val viewModel = TimelineViewModel(ROOM_ID, client, viewModelScope())

        viewModel.onDraftChange("  hallo welt  ")
        viewModel.send()

        expectEquals(listOf(ROOM_ID to "hallo welt"), client.sendMessageCalls, "Senden delegiert")
        expectEquals("", viewModel.ui.value.draft, "Entwurf geleert")
        expectNull(viewModel.ui.value.error, "kein Fehler")
        viewModel.clear()
    }

    Checks.check("Leerer Entwurf wird nicht gesendet") {
        val client = FakeChannelClient()
        val viewModel = TimelineViewModel(ROOM_ID, client, viewModelScope())

        viewModel.onDraftChange("   ")
        viewModel.send()

        expectTrue(client.sendMessageCalls.isEmpty(), "kein Senden bei Leertext")
        viewModel.clear()
    }

    Checks.check("Sendefehler zeigt Fehler und stellt Entwurf wieder her") {
        val client = FakeChannelClient().apply {
            sendMessageBehavior = { _, _ ->
                throw org.box44.kailink.domain.ChannelException("Senden fehlgeschlagen: offline")
            }
        }
        val viewModel = TimelineViewModel(ROOM_ID, client, viewModelScope())

        viewModel.onDraftChange("wichtig")
        viewModel.send()

        expectNotNull(viewModel.ui.value.error, "Fehlermeldung vorhanden")
        expectEquals("wichtig", viewModel.ui.value.draft, "Entwurf wiederhergestellt")
        expectEquals(1, client.sendMessageCalls.size, "genau ein Sendeversuch")
        viewModel.clear()
    }
}
