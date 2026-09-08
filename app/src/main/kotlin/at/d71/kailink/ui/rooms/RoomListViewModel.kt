package at.d71.kailink.ui.rooms

import at.d71.kailink.data.push.PushController
import at.d71.kailink.domain.ChannelClient
import at.d71.kailink.domain.ChannelEvent
import at.d71.kailink.domain.model.Room
import at.d71.kailink.domain.push.PushState
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
 * Raumliste; aktualisiert sich aus [ChannelEvent.RoomsUpdated] und nach
 * `syncOnce` (docs/features/raumliste-chronik.md). Reine Kotlin-Klasse ohne
 * Android-/Lifecycle-Abhängigkeit; der CoroutineScope ist injizierbar,
 * damit JVM-Prüfungen deterministisch laufen.
 */
class RoomListViewModel(
    private val channelClient: ChannelClient,
    private val pushController: PushController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _ui = MutableStateFlow(RoomListUiState())
    val ui: StateFlow<RoomListUiState> = _ui.asStateFlow()

    /** Push-Zustand der Push-Registrierung (Anzeige in der Liste). */
    val pushState: StateFlow<PushState> = pushController.state

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
                    it.copy(refreshing = false, error = t.message ?: "Aktualisierung fehlgeschlagen")
                }
            }
        }
    }

    fun logout() {
        scope.launch {
            try {
                channelClient.logout()
            } catch (t: Throwable) {
                _ui.update { it.copy(error = "Abmeldung fehlgeschlagen: ${t.message}") }
            }
        }
    }

    fun clear() {
        scope.cancel()
    }
}
