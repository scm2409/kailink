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

    Checks.check("Timeline events are filtered by room ID") {
        val client = FakeChannelClient()
        val viewModel = TimelineViewModel(ROOM_ID, client, viewModelScope())

        runBlocking {
            client.emit(ChannelEvent.TimelineUpdated(ROOM_ID, listOf(testMessage("m1"), testMessage("m2"))))
            client.emit(ChannelEvent.TimelineUpdated("!other:room", listOf(testMessage("foreign", "!other:room"))))
        }

        expectEquals(
            listOf("m1", "m2"),
            viewModel.ui.value.messages.map { it.id },
            "only messages of the own room",
        )
        expectTrue(
            viewModel.ui.value.messages.none { it.id == "foreign" },
            "foreign room message discarded",
        )
        viewModel.clear()
    }

    Checks.check("Opening the timeline subscribes and syncs") {
        val client = FakeChannelClient()

        val viewModel = TimelineViewModel(ROOM_ID, client, viewModelScope())

        expectEquals(listOf(ROOM_ID), client.openTimelineCalls, "openTimeline")
        expectTrue(client.syncOnceCalls >= 1, "at least one syncOnce")
        viewModel.clear()
    }

    Checks.check("Send clears the draft and delegates with trimmed text") {
        val client = FakeChannelClient()
        val viewModel = TimelineViewModel(ROOM_ID, client, viewModelScope())

        viewModel.onDraftChange("  hello world  ")
        viewModel.send()

        expectEquals(listOf(ROOM_ID to "hello world"), client.sendMessageCalls, "Send delegates")
        expectEquals("", viewModel.ui.value.draft, "Draft cleared")
        expectNull(viewModel.ui.value.error, "no error")
        viewModel.clear()
    }

    Checks.check("Empty draft is not sent") {
        val client = FakeChannelClient()
        val viewModel = TimelineViewModel(ROOM_ID, client, viewModelScope())

        viewModel.onDraftChange("   ")
        viewModel.send()

        expectTrue(client.sendMessageCalls.isEmpty(), "no send on empty text")
        viewModel.clear()
    }

    Checks.check("Send error shows error and restores the draft") {
        val client = FakeChannelClient().apply {
            sendMessageBehavior = { _, _ ->
                throw org.box44.kailink.domain.ChannelException("Send failed: offline")
            }
        }
        val viewModel = TimelineViewModel(ROOM_ID, client, viewModelScope())

        viewModel.onDraftChange("important")
        viewModel.send()

        expectNotNull(viewModel.ui.value.error, "error message present")
        expectEquals("important", viewModel.ui.value.draft, "Draft restored")
        expectEquals(1, client.sendMessageCalls.size, "exactly one send attempt")
        viewModel.clear()
    }
}
