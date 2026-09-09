package org.box44.kailink.data.log

/**
 * Bridge from the matrix-rust-sdk tracing output into the app [DebugLog]
 * ring buffer (0.2.5-phase1 convention, see AGENTS.md).
 *
 * The SDK writes its Rust `tracing` events (including the HTTP client and
 * sync machinery) through the `initPlatform` tracing setup — for KaiLink
 * both to logcat and to rotating files under `cacheDir/matrix/tracing`
 * (file name pattern `{prefix}.{timestamp}{suffix}`; line format
 * `{timestamp} {LEVEL} {target}: {message} | {file}:{line}`, see
 * `bindings/matrix-sdk-ffi/src/platform/mod.rs` in the pinned SDK).
 *
 * [ingest] parses such lines and forwards only *useful diagnostics*:
 * - `ERROR` and `WARN` lines always,
 * - `INFO` lines only for sync-relevant targets ([SYNC_INFO_TARGETS]),
 * - `DEBUG`/`TRACE` lines never (they would flush the buffer with spam).
 *
 * Lines are truncated to [MAX_LINE_LENGTH] and the caller bounds the
 * number of ingested lines per poll ([SdkLogTailer]); the ring buffer
 * itself caps the retained history at [DebugLog.CAPACITY] (~1000 lines).
 * SDK tracing never carries credentials/tokens (the upstream filter
 * deliberately excludes third-party targets); KaiLink additionally never
 * appends credentials to [DebugLog] (G7).
 */
object SdkLogBridge {

    /** Longest forwarded part of an SDK line (prevents oversized dumps). */
    const val MAX_LINE_LENGTH = 400

    /** Sync-relevant targets whose INFO lines are forwarded. */
    val SYNC_INFO_TARGETS = setOf(
        "matrix_sdk::client",
        "matrix_sdk::sliding_sync",
        "matrix_sdk::http_client",
    )

    private val LINE = Regex("""^(\S+)\s+(ERROR|WARN|INFO|DEBUG|TRACE)\s+(\S+):\s(.*)$""")

    /**
     * Ingests one SDK tracing line; returns true if it reached [DebugLog].
     * Lines that do not match the SDK tracing format are ignored.
     */
    fun ingest(line: String): Boolean {
        val match = LINE.find(line.trim()) ?: return false
        val level = match.groupValues[2]
        val target = match.groupValues[3]
        val message = match.groupValues[4]
        if (!isEligible(level, target)) return false
        DebugLog.append("sdk $level $target: ${message.take(MAX_LINE_LENGTH)}")
        return true
    }

    /**
     * Ingests a block of SDK tracing lines (oldest first); returns the
     * number of lines that reached [DebugLog].
     */
    fun ingestAll(lines: List<String>): Int = lines.count { ingest(it) }

    /** Level/target filter of the bridge (visible for checks). */
    fun isEligible(level: String, target: String): Boolean = when (level) {
        "ERROR", "WARN" -> true
        "INFO" -> target in SYNC_INFO_TARGETS
        else -> false
    }
}
