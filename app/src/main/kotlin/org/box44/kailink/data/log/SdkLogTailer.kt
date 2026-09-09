package org.box44.kailink.data.log

import java.io.File
import java.io.IOException

/**
 * Reads the SDK tracing log files written by `initPlatform`
 * (`writeToFiles`, see AGENTS.md) and feeds newly appended lines into
 * [SdkLogBridge] → [DebugLog].
 *
 * Bounded by construction:
 * - only *appended* bytes since the last poll are read (per-file offsets,
 *   reset when a file was truncated/rotated away),
 * - at most [maxLinesPerPoll] lines are ingested per [poll] and at most
 *   [MAX_BYTES_PER_FILE_PER_POLL] bytes are read per file (a partially
 *   read line is finished on the next poll),
 * - [SdkLogBridge] level/target-filters, and [DebugLog.CAPACITY] finally
 *   bounds what is retained (~1000 useful lines).
 *
 * Pure java.io (no Android types), therefore JVM-checkable.
 */
class SdkLogTailer(
    private val directory: File,
    private val filePrefix: String = DEFAULT_PREFIX,
    private val fileSuffix: String = DEFAULT_SUFFIX,
    private val maxLinesPerPoll: Int = DEFAULT_MAX_LINES_PER_POLL,
    private val sink: (String) -> Boolean = SdkLogBridge::ingest,
) {

    private val lock = Any()
    private val offsets = HashMap<String, Long>()

    /** Scans the directory and ingests new lines; returns lines fed to [sink]. */
    fun poll(): Int {
        var ingested = 0
        synchronized(lock) {
            for (file in matchingFiles()) {
                if (ingested >= maxLinesPerPoll) break
                ingested += pollFile(file)
            }
        }
        return ingested
    }

    private fun pollFile(file: File): Int {
        val size = try {
            file.length()
        } catch (_: IOException) {
            return 0
        }
        var previous = offsets[file.name] ?: 0L
        if (size < previous) {
            // Truncated/rotated away: restart from the beginning, within
            // this poll.
            offsets[file.name] = 0L
            previous = 0L
            if (size == 0L) return 0
        }
        if (size == previous) return 0
        val readLimit = minOf(size, previous + MAX_BYTES_PER_FILE_PER_POLL)
        val buffer = ByteArray((readLimit - previous).toInt())
        var total = 0
        try {
            file.inputStream().use { stream ->
                var toSkip = previous
                while (toSkip > 0) {
                    val skipped = stream.skip(toSkip)
                    if (skipped <= 0) return 0
                    toSkip -= skipped
                }
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
            }
        } catch (_: IOException) {
            return 0
        }
        // Only complete lines (terminated by '\n') are processed; a partial
        // trailing line is re-read on the next poll.
        var end = total
        while (end > 0 && buffer[end - 1] != NEWLINE) end--
        if (end == 0) return 0
        var ingested = 0
        var lineStart = 0
        var index = 0
        while (index < end && ingested < maxLinesPerPoll) {
            if (buffer[index] == NEWLINE) {
                var lineEnd = index
                if (lineEnd > lineStart && buffer[lineEnd - 1] == CARRIAGE_RETURN) lineEnd--
                if (sink(String(buffer, lineStart, lineEnd - lineStart, Charsets.UTF_8))) ingested++
                lineStart = index + 1
            }
            index++
        }
        offsets[file.name] = previous + lineStart
        return ingested
    }

    private fun matchingFiles(): List<File> {
        val files = directory.listFiles { f ->
            f.isFile && f.name.startsWith("$filePrefix.") && f.name.endsWith(fileSuffix)
        } ?: return emptyList()
        return files.sortedBy { it.name }
    }

    companion object {
        /** Must match `TracingFileConfiguration.filePrefix` in KaiLinkApp. */
        const val DEFAULT_PREFIX = "kailink-sdk"

        /** Must match `TracingFileConfiguration.fileSuffix` in KaiLinkApp. */
        const val DEFAULT_SUFFIX = ".log"

        /**
         * Subdirectory (relative to the app cache dir) the SDK writes its
         * tracing files into. Single source of truth: KaiLinkApp creates it
         * (`TracingFileConfiguration.path`), AppGraph tails it.
         */
        const val TRACE_DIRECTORY = "matrix/tracing"

        const val DEFAULT_MAX_LINES_PER_POLL = 50

        private const val MAX_BYTES_PER_FILE_PER_POLL = 128L * 1024L

        private val NEWLINE: Byte = '\n'.code.toByte()

        private val CARRIAGE_RETURN: Byte = '\r'.code.toByte()
    }
}
