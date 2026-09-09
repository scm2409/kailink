package org.box44.kailink.testing

import org.box44.kailink.data.push.PushController
import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.ChannelEvent
import org.box44.kailink.ui.rooms.RoomListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

fun roomListViewModelChecks() {

    fun viewModelScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    Checks.check("refresh triggers sync and room list") {
        val client = FakeChannelClient()
        val viewModel = RoomListViewModel(
            client,
            PushController(client, viewModelScope()).state,
            viewModelScope(),
        )
        val syncBefore = client.syncOnceCalls
        val liveSyncBefore = client.startLiveSyncCalls

        viewModel.refresh()

        expectTrue(client.syncOnceCalls > syncBefore, "syncOnce triggered again")
        expectEquals(liveSyncBefore + 1, client.startLiveSyncCalls, "startLiveSync")
        expectEquals(
            listOf(TEST_ROOM.id),
            viewModel.ui.value.rooms.map { it.id },
            "Room list",
        )
        expectTrue(viewModel.ui.value.rooms.first().isEncrypted, "encrypted marker")
        viewModel.clear()
    }

    Checks.check("RoomsUpdated event updates the state") {
        val client = FakeChannelClient()
        val viewModel = RoomListViewModel(
            client,
            MutableStateFlow(org.box44.kailink.domain.push.PushState.NOT_AVAILABLE),
            viewModelScope(),
        )

        runBlocking { client.emit(ChannelEvent.RoomsUpdated(listOf(TEST_ROOM))) }

        expectEquals(
            listOf(TEST_ROOM.id),
            viewModel.ui.value.rooms.map { it.id },
            "Room list after event",
        )
        expectEquals(false, viewModel.ui.value.refreshing, "refreshing reset")
        viewModel.clear()
    }

    Checks.check("refresh keeps the room list usable when live sync is unavailable") {
        val client = FakeChannelClient()
        client.startLiveSyncBehavior = {
            throw org.box44.kailink.domain.ChannelException(
                "Could not start live sync: Sliding sync version is missing",
            )
        }
        val viewModel = RoomListViewModel(
            client,
            MutableStateFlow(org.box44.kailink.domain.push.PushState.NOT_AVAILABLE),
            viewModelScope(),
        )
        // The constructor already kicked off one refresh; count relative.
        val liveSyncBefore = client.startLiveSyncCalls
        runBlocking { client.restore(TEST_SESSION) }

        viewModel.refresh()

        expectEquals(liveSyncBefore + 1, client.startLiveSyncCalls, "startLiveSync attempted")
        expectEquals(
            listOf(TEST_ROOM.id),
            viewModel.ui.value.rooms.map { it.id },
            "Room list still loaded after the live-sync failure",
        )
        expectEquals(false, viewModel.ui.value.refreshing, "refreshing reset")
        expectTrue(
            viewModel.ui.value.error?.contains("Sliding sync version is missing") == true,
            "live-sync failure surfaced, not silenced",
        )
        // The "Send log" action stays available (session exists).
        expectNotNull(viewModel.ui.value.userId, "send-log availability follows the session")
        viewModel.clear()
    }

    Checks.check("Logout is delegated to the client") {
        val client = FakeChannelClient()
        val viewModel = RoomListViewModel(
            client,
            MutableStateFlow(org.box44.kailink.domain.push.PushState.NOT_AVAILABLE),
            viewModelScope(),
        )

        viewModel.logout()

        expectEquals(1, client.logoutCalls, "logout calls")
        viewModel.clear()
    }
}
