package org.box44.kailink.testing

import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.push.PushState
import org.box44.kailink.ui.rooms.RoomListViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * JVM checks for the "Send debug log" action (RoomListViewModel).
 */
fun sendDebugLogChecks() {

    fun viewModelScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    fun viewModel(client: ChannelClient) = RoomListViewModel(
        client,
        MutableStateFlow(PushState.NOT_AVAILABLE),
        viewModelScope(),
    )

    Checks.check("sendDebugLog uploads the log into the KaiL room") {
        val client = FakeChannelClient()
        val kaiLRoom = TEST_ROOM.copy(id = "!kail-dm:example.org", displayName = "KaiL", isEncrypted = true)
        val otherRoom = TEST_ROOM.copy(id = "!other:example.org", displayName = "Other")
        client.roomsBehavior = { listOf(otherRoom, kaiLRoom) }
        val logLines = mutableListOf<String>()
        val viewModel = RoomListViewModel(
            client,
            MutableStateFlow(PushState.NOT_AVAILABLE),
            viewModelScope(),
            logSource = { "buffered log line" },
            logSink = { logLines.add(it) },
            clock = { 1_700_000_000_000L },
        )
        runBlocking { client.restore(TEST_SESSION) }

        viewModel.sendDebugLog()

        expectEquals(1, client.sendFileCalls.size, "one file sent")
        val sent = client.sendFileCalls.single()
        expectEquals(kaiLRoom.id, sent.roomId, "file sent to the KaiL room")
        expectTrue(sent.fileName.startsWith("kailink-debug-log-"), "file name prefix")
        expectTrue(sent.fileName.endsWith(".txt"), "file name extension")
        expectEquals("text/plain", sent.mimeType, "mime type")
        expectEquals("buffered log line", sent.content.toString(Charsets.UTF_8), "log content")
        expectEquals(RoomListViewModel.LOG_CAPTION, sent.caption, "caption")
        expectEquals(false, viewModel.ui.value.sendingLog, "busy flag reset")
        expectEquals(1, logLines.count { it.contains("Debug log sent") }, "send recorded in log")
        viewModel.clear()
    }

    Checks.check("sendDebugLog matches the KaiL marker case-insensitively") {
        val client = FakeChannelClient()
        val kaiLRoom = TEST_ROOM.copy(id = "!kail2:example.org", displayName = "chat with kail")
        client.roomsBehavior = { listOf(kaiLRoom) }
        val viewModel = viewModel(client)
        runBlocking { client.restore(TEST_SESSION) }

        viewModel.sendDebugLog()

        expectEquals(1, client.sendFileCalls.size, "file sent to lowercase kail room")
        expectEquals(kaiLRoom.id, client.sendFileCalls.single().roomId, "resolved room")
        viewModel.clear()
    }

    Checks.check("sendDebugLog does not match kailink-prefixed room names") {
        val client = FakeChannelClient()
        // "kailink-e2e-…" contains the letters "kail" but not the standalone
        // word — this was observed with the E2E gate's room names.
        client.roomsBehavior = { listOf(TEST_ROOM.copy(id = "!e2e:example.org", displayName = "kailink-e2e-a-123")) }
        val viewModel = viewModel(client)
        runBlocking { client.restore(TEST_SESSION) }

        viewModel.sendDebugLog()

        expectEquals(0, client.sendFileCalls.size, "no upload into a kailink-named room")
        expectTrue(
            viewModel.ui.value.error?.contains(RoomListViewModel.NO_KAIL_ROOM_MESSAGE) == true,
            "KaiL room error surfaced",
        )
        viewModel.clear()
    }

    Checks.check("isKaiLRoom accepts KaiL variants and rejects look-alikes") {
        expectTrue(RoomListViewModel.isKaiLRoom("KaiL"), "exact KaiL")
        expectTrue(RoomListViewModel.isKaiLRoom("chat with KAIL"), "uppercase, embedded")
        expectTrue(RoomListViewModel.isKaiLRoom("KaiL (debug)"), "with suffix")
        expectFalse(RoomListViewModel.isKaiLRoom("kailink-e2e-a-123"), "kailink prefix")
        expectFalse(RoomListViewModel.isKaiLRoom("KaiLink room"), "KaiLink word")
        expectFalse(RoomListViewModel.isKaiLRoom(""), "empty")
    }

    Checks.check("sendDebugLog fails with a readable error without a KaiL room") {
        val client = FakeChannelClient()
        client.roomsBehavior = { listOf(TEST_ROOM.copy(displayName = "Friends")) }
        val viewModel = viewModel(client)
        runBlocking { client.restore(TEST_SESSION) }

        viewModel.sendDebugLog()

        expectEquals(0, client.sendFileCalls.size, "no file sent")
        expectTrue(
            viewModel.ui.value.error?.contains(RoomListViewModel.NO_KAIL_ROOM_MESSAGE) == true,
            "KaiL room error surfaced",
        )
        expectEquals(false, viewModel.ui.value.sendingLog, "busy flag reset on error")
        viewModel.clear()
    }

    Checks.check("sendDebugLog is guarded while logged out") {
        val client = FakeChannelClient()
        client.roomsBehavior = { listOf(TEST_ROOM) }
        val viewModel = viewModel(client)

        viewModel.sendDebugLog()

        expectEquals(0, client.sendFileCalls.size, "no upload while logged out")
        expectTrue(
            viewModel.ui.value.error?.contains(RoomListViewModel.NOT_LOGGED_IN_MESSAGE) == true,
            "not-signed-in error surfaced",
        )
        viewModel.clear()
    }

    Checks.check("sendDebugLog works with a session while live sync is unavailable") {
        val client = FakeChannelClient()
        val kaiLRoom = TEST_ROOM.copy(id = "!kail-ls:example.org", displayName = "KaiL")
        client.roomsBehavior = { listOf(kaiLRoom) }
        // Live sync unavailable (server without sliding sync, e.g. Conduit
        // in the E2E gate, or the matrix.org failure fixed in
        // 0.2.5-phase1): the action needs only the session, never the
        // sync state.
        client.startLiveSyncBehavior = {
            throw org.box44.kailink.domain.ChannelException(
                "Could not start live sync: Sliding sync version is missing",
            )
        }
        val logLines = mutableListOf<String>()
        val viewModel = RoomListViewModel(
            client,
            MutableStateFlow(PushState.NOT_AVAILABLE),
            viewModelScope(),
            logSource = { "buffered log line" },
            logSink = { logLines.add(it) },
        )
        runBlocking { client.restore(TEST_SESSION) }

        viewModel.sendDebugLog()

        expectEquals(1, client.sendFileCalls.size, "file sent although live sync is unavailable")
        expectEquals(kaiLRoom.id, client.sendFileCalls.single().roomId, "KaiL room resolved")
        expectEquals(false, viewModel.ui.value.sendingLog, "busy flag reset")
        expectEquals(1, logLines.count { it.contains("Debug log sent") }, "send recorded in log")
        viewModel.clear()
    }

    Checks.check("sendDebugLog ignores a second call while busy") {
        val client = FakeChannelClient()
        val kaiLRoom = TEST_ROOM.copy(id = "!kail3:example.org", displayName = "KaiL")
        client.roomsBehavior = { listOf(kaiLRoom) }
        runBlocking { client.restore(TEST_SESSION) }
        // Blocking client: the first sendFile parks until released.
        val sent = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blockingClient = object : ChannelClient by client {
            override suspend fun sendFile(
                roomId: String,
                fileName: String,
                mimeType: String,
                content: ByteArray,
                caption: String?,
            ) {
                sent.complete(Unit)
                release.await()
                client.sendFile(roomId, fileName, mimeType, content, caption)
            }
        }
        val viewModel = viewModel(blockingClient)
        // Unconfined scope: runs synchronously until the parked sendFile.
        viewModel.sendDebugLog()
        expectTrue(sent.isCompleted, "sanity: first send parked in sendFile")

        viewModel.sendDebugLog()

        expectEquals(0, client.sendFileCalls.size, "second call ignored while busy")
        release.complete(Unit)
        expectEquals(1, client.sendFileCalls.size, "first send completed after release")
        expectEquals(false, viewModel.ui.value.sendingLog, "busy flag reset")
        viewModel.clear()
    }
}
