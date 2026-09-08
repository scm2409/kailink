package at.d71.kailink.testing

import at.d71.kailink.data.push.PushController
import at.d71.kailink.domain.ChannelEvent
import at.d71.kailink.ui.rooms.RoomListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

fun roomListViewModelChecks() {

    fun viewModelScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    Checks.check("refresh stößt Sync und Raumliste an") {
        val client = FakeChannelClient()
        val viewModel = RoomListViewModel(
            client,
            PushController(client, viewModelScope()).state,
            viewModelScope(),
        )

        viewModel.refresh()

        expectTrue(client.syncOnceCalls >= 1, "mindestens ein syncOnce")
        expectEquals(1, client.startLiveSyncCalls, "startLiveSync")
        expectEquals(
            listOf(TEST_ROOM.id),
            viewModel.ui.value.rooms.map { it.id },
            "Raumliste",
        )
        expectTrue(viewModel.ui.value.rooms.first().isEncrypted, "verschlüsselt-Markierung")
        viewModel.clear()
    }

    Checks.check("RoomsUpdated-Ereignis aktualisiert den Zustand") {
        val client = FakeChannelClient()
        val viewModel = RoomListViewModel(
            client,
            MutableStateFlow(at.d71.kailink.domain.push.PushState.NOT_AVAILABLE),
            viewModelScope(),
        )

        runBlocking { client.emit(ChannelEvent.RoomsUpdated(listOf(TEST_ROOM))) }

        expectEquals(
            listOf(TEST_ROOM.id),
            viewModel.ui.value.rooms.map { it.id },
            "Raumliste nach Ereignis",
        )
        expectEquals(false, viewModel.ui.value.refreshing, "refreshing zurückgesetzt")
        viewModel.clear()
    }

    Checks.check("Abmeldung wird an den Client delegiert") {
        val client = FakeChannelClient()
        val viewModel = RoomListViewModel(
            client,
            MutableStateFlow(at.d71.kailink.domain.push.PushState.NOT_AVAILABLE),
            viewModelScope(),
        )

        viewModel.logout()

        expectEquals(1, client.logoutCalls, "logout-Aufrufe")
        viewModel.clear()
    }
}
