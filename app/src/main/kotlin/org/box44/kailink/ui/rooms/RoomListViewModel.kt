package org.box44.kailink.ui.rooms

import org.box44.kailink.data.log.DebugLog
import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.ChannelEvent
import org.box44.kailink.domain.ChannelException
import org.box44.kailink.domain.model.Room
import org.box44.kailink.domain.push.PushState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val sendingLog: Boolean = false,
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
    private val logSource: () -> String = { DebugLog.dump() },
    private val logSink: (String) -> Unit = { DebugLog.append(it) },
    private val clock: () -> Long = System::currentTimeMillis,
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
                        it.copy(
                            rooms = event.rooms,
                            refreshing = false,
                            error = null,
                            // Follow the session state: the "Send log"
                            // action is available whenever a session
                            // exists — independently of live sync.
                            userId = channelClient.activeSession?.userId,
                        )
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
                var liveSyncFailure: String? = null
                try {
                    channelClient.startLiveSync()
                } catch (t: Throwable) {
                    // Live sync is best-effort for the room list: servers
                    // without sliding sync (Conduit in the E2E gate) must
                    // not blank the room list or block the other actions.
                    // The failure is still surfaced, never silenced.
                    liveSyncFailure = t.message ?: "Live sync unavailable"
                }
                _ui.update {
                    it.copy(
                        rooms = channelClient.rooms(),
                        refreshing = false,
                        error = liveSyncFailure,
                        // Refreshed with every sync so the "Send log"
                        // action appears as soon as a session exists —
                        // independently of the live sync state.
                        userId = channelClient.activeSession?.userId,
                    )
                }
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

    /**
     * Sends the buffered debug log as a `.txt` file into the KaiL room
     * (the room where the app talks to KaiL). The room is resolved at send
     * time: the first room whose display name contains the standalone word
     * [KAIL_ROOM_MARKER] (case-insensitive word-boundary match, so rooms
     * like "kailink-e2e-…" do not match). Hidden while logged out (the room
     * list screen is only reachable after login; visibility also follows
     * [RoomListUiState.userId]).
     */
    fun sendDebugLog() {
        if (_ui.value.sendingLog) return
        if (channelClient.activeSession == null) {
            _ui.update { it.copy(error = NOT_LOGGED_IN_MESSAGE) }
            return
        }
        _ui.update { it.copy(sendingLog = true, error = null) }
        scope.launch {
            try {
                val roomId = channelClient.rooms()
                    .firstOrNull { isKaiLRoom(it.displayName) }
                    ?.id
                    ?: throw ChannelException(NO_KAIL_ROOM_MESSAGE)
                val fileName = "kailink-debug-log-${formatTimestamp(clock())}.txt"
                channelClient.sendFile(
                    roomId = roomId,
                    fileName = fileName,
                    mimeType = "text/plain",
                    content = logSource().toByteArray(Charsets.UTF_8),
                    caption = LOG_CAPTION,
                )
                logSink("Debug log sent to $roomId as $fileName")
                _ui.update { it.copy(sendingLog = false) }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(sendingLog = false, error = "Sending debug log failed: ${t.message ?: "unknown error"}")
                }
            }
        }
    }

    fun clear() {
        scope.cancel()
    }

    companion object {
        /** Marker for the KaiL room (standalone word, case-insensitive — see decisions.md). */
        const val KAIL_ROOM_MARKER = "kail"

        private val KAIL_ROOM_PATTERN = Regex("""\b${KAIL_ROOM_MARKER}\b""", RegexOption.IGNORE_CASE)

        /** True if the display name identifies the KaiL room. */
        fun isKaiLRoom(displayName: String): Boolean = KAIL_ROOM_PATTERN.containsMatchIn(displayName)

        internal const val NO_KAIL_ROOM_MESSAGE =
            "No KaiL room found (the room name must contain \"KaiL\")."
        internal const val NOT_LOGGED_IN_MESSAGE = "Not signed in."
        internal const val LOG_CAPTION = "KaiLink debug log"

        private fun formatTimestamp(millis: Long): String {
            val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            return format.format(Date(millis))
        }
    }
}
