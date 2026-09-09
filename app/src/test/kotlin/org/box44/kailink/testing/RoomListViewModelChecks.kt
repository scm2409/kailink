package org.box44.kailink.testing

import org.box44.kailink.data.push.PushController
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
