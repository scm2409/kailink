package org.box44.kailink.ui.rooms

import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.ChannelEvent
import org.box44.kailink.domain.model.Room
import org.box44.kailink.domain.push.PushState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomListUiState(
    val rooms: List<Room> = emptyList(),
    val refreshing: Boolean = true,
    val error: String? = null,
    val userId: String? = null,
)

/**
 * Room list; updates from [ChannelEvent.RoomsUpdated] and after
 * `syncOnce` (docs/features/raumliste-chronik.md). Pure Kotlin class;
 * the push state is injected as a StateFlow so that `ui/` does not know
 * data types.
 */
class RoomListViewModel(
    private val channelClient: ChannelClient,
    pushState: StateFlow<PushState>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _ui = MutableStateFlow(RoomListUiState())
    val ui: StateFlow<RoomListUiState> = _ui.asStateFlow()

    /** Push state of the push registration (display in the list). */
    val pushState: StateFlow<PushState> = pushState

    init {
        _ui.update { it.copy(userId = channelClient.activeSession?.userId) }
        scope.launch {
            channelClient.events.collect { event ->
                when (event) {
                    is ChannelEvent.RoomsUpdated -> _ui.update {
                        it.copy(rooms = event.rooms, refreshing = false, error = null)
                    }
                    is ChannelEvent.ClientError -> _ui.update {
                        it.copy(refreshing = false, error = event.message)
                    }
                    else -> Unit
                }
            }
        }
        refresh()
    }

    fun refresh() {
        _ui.update { it.copy(refreshing = true) }
        scope.launch {
            try {
                channelClient.syncOnce()
                channelClient.startLiveSync()
                _ui.update { it.copy(rooms = channelClient.rooms(), refreshing = false) }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(refreshing = false, error = t.message ?: "Refresh failed")
                }
            }
        }
    }

    fun logout() {
        scope.launch {
            try {
                channelClient.logout()
            } catch (t: Throwable) {
                _ui.update { it.copy(error = "Sign-out failed: ${t.message}") }
            }
        }
    }

    fun clear() {
        scope.cancel()
    }
}
