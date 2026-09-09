package org.box44.kailink.testing

import org.box44.kailink.data.log.DebugLog
import org.box44.kailink.data.log.SdkLogBridge
import org.box44.kailink.data.log.SdkLogTailer
import java.io.File
import java.nio.file.Files

/**
 * JVM checks for the SDK/HTTP client diagnostic logging route
 * (0.2.5-phase1, AGENTS.md): Rust SDK tracing is written to rotating
 * files; [SdkLogTailer] feeds appended lines into [SdkLogBridge], which
 * level/target-filters before reaching the [DebugLog] ring buffer.
 */
fun sdkLogChecks() {

    Checks.check("simulated SDK error line reaches the DebugLog buffer") {
        DebugLog.clear()
        val ingested = SdkLogBridge.ingest(
            "2026-09-09T18:12:33.456789Z ERROR matrix_sdk::client: Sync loop ended with error: " +
                "Sliding sync version is missing | crates/matrix-sdk/src/client/mod.rs:1812",
        )
        expectTrue(ingested, "error line ingested")
        val dump = DebugLog.dump()
        expectTrue(
            dump.contains("sdk ERROR matrix_sdk::client: Sync loop ended with error"),
            "SDK error line in the buffer",
        )
    }

    Checks.check("SDK warn lines and sync INFO lines are forwarded") {
        DebugLog.clear()
        expectTrue(
            SdkLogBridge.ingest(
                "2026-09-09T18:12:33.456789Z  WARN matrix_sdk::http_client: request failed " +
                    "(retry 2/5) | crates/matrix-sdk/src/http_client/native.rs:83",
            ),
            "warn line ingested",
        )
        expectTrue(
            SdkLogBridge.ingest(
                "2026-09-09T18:12:33.456789Z  INFO matrix_sdk::client: selected sliding sync " +
                    "version version=Native | crates/matrix-sdk/src/client/builder/mod.rs:715",
            ),
            "sync INFO line ingested",
        )
        expectTrue(DebugLog.dump().contains("sdk WARN matrix_sdk::http_client"), "warn in buffer")
        expectTrue(
            DebugLog.dump().contains("sdk INFO matrix_sdk::client: selected sliding sync version"),
            "sync INFO in buffer",
        )
    }

    Checks.check("DEBUG/TRACE lines and foreign targets do not reach the buffer") {
        DebugLog.clear()
        expectFalse(
            SdkLogBridge.ingest(
                "2026-09-09T18:12:33.456789Z DEBUG matrix_sdk::event_cache: linked chunk update " +
                    "items=[..] | crates/matrix-sdk/src/event_cache/mod.rs:691",
            ),
            "debug line dropped",
        )
        expectFalse(
            SdkLogBridge.ingest(
                "2026-09-09T18:12:33.456789Z TRACE matrix_sdk::client: some span event | file.rs:1",
            ),
            "trace line dropped",
        )
        expectFalse(
            SdkLogBridge.ingest(
                "2026-09-09T18:12:33.456789Z  INFO hyper::client::conn: send request | hyper.rs:12",
            ),
            "foreign INFO target dropped",
        )
        expectFalse(
            SdkLogBridge.ingest("not an sdk tracing line at all"),
            "unparseable line dropped",
        )
        expectEquals(0, DebugLog.size(), "buffer stayed empty")
    }

    Checks.check("oversized SDK lines are truncated") {
        DebugLog.clear()
        val longMessage = "x".repeat(SdkLogBridge.MAX_LINE_LENGTH + 500)
        val ingested = SdkLogBridge.ingest(
            "2026-09-09T18:12:33.456789Z ERROR matrix_sdk::client: $longMessage()",
        )
        expectTrue(ingested, "long line ingested")
        val line = DebugLog.dump().lineSequence().first { it.contains("sdk ERROR") }
        expectTrue(
            line.endsWith("x".repeat(SdkLogBridge.MAX_LINE_LENGTH)),
            "forwarded message truncated to MAX_LINE_LENGTH",
        )
        expectFalse(
            line.contains("x".repeat(SdkLogBridge.MAX_LINE_LENGTH + 1)),
            "no content beyond MAX_LINE_LENGTH",
        )
    }

    Checks.check("tailer feeds appended SDK lines into the buffer exactly once") {
        DebugLog.clear()
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val logFile = File(root, "kailink-sdk.2026-09-09-18.log")
        logFile.writeText(
            "2026-09-09T18:00:00.000000Z ERROR matrix_sdk::client: first error | a.rs:1\n" +
                "2026-09-09T18:12:33.456789Z DEBUG matrix_sdk::event_cache: noisy spam | a.rs:2\n",
        )
        val tailer = SdkLogTailer(root)
        expectEquals(1, tailer.poll(), "only the eligible line ingested")
        val firstDump = DebugLog.dump()
        expectTrue(
            firstDump.contains("sdk ERROR matrix_sdk::client: first error"),
            "error line in buffer",
        )

        // Repeated poll must not duplicate (offset bookkeeping).
        expectEquals(0, tailer.poll(), "no duplicate ingest on re-poll")

        // Appended lines are picked up on the next poll.
        logFile.appendText(
            "2026-09-09T18:20:00.000000Z  WARN matrix_sdk::sliding_sync: sync restart needed | b.rs:2\n",
        )
        expectEquals(1, tailer.poll(), "appended line ingested")
        expectTrue(DebugLog.dump().contains("sdk WARN matrix_sdk::sliding_sync"), "appended warn in buffer")
        root.deleteRecursively()
    }

    Checks.check("tailer is bounded per poll and resumes without loss") {
        DebugLog.clear()
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val logFile = File(root, "kailink-sdk.2026-09-09-19.log")
        val burst = (1..120).joinToString("") {
            "2026-09-09T19:00:00.000000Z ERROR matrix_sdk::client: burst-$it | c.rs:$it\n"
        }
        logFile.writeText(burst)
        val tailer = SdkLogTailer(root, maxLinesPerPoll = 10)
        var total = 0
        repeat(13) { total += tailer.poll() }
        expectEquals(120, total, "all error lines ingested across bounded polls")
        expectEquals(0, tailer.poll(), "tail is drained")
        root.deleteRecursively()
    }

    Checks.check("tailer recovers when the SDK rotates/truncates the file") {
        DebugLog.clear()
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val logFile = File(root, "kailink-sdk.2026-09-09-20.log")
        logFile.writeText("2026-09-09T20:00:00.000000Z ERROR matrix_sdk::client: before rotation | d.rs:1\n")
        val tailer = SdkLogTailer(root)
        expectEquals(1, tailer.poll(), "initial line ingested")
        // Rotation rewrites a smaller file with the same name prefix.
        logFile.writeText("2026-09-09T20:05:00.000000Z ERROR matrix_sdk::client: after rotation | e.rs:1\n")
        expectEquals(1, tailer.poll(), "line after truncation ingested")
        expectTrue(DebugLog.dump().contains("after rotation"), "post-rotation line in buffer")
        root.deleteRecursively()
    }
}
