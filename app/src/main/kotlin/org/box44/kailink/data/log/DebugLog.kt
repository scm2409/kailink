package org.box44.kailink.data.log

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-app debug log: a thread-safe ring buffer retaining approximately the
 * last [CAPACITY] log lines. All app log output is routed here
 * (see `AppGraph.log`); the "Send log" action uploads the dumped buffer
 * as a `.txt` file into the KaiL room.
 *
 * Pure Kotlin/JVM (no Android types) so the retention behavior is
 * JVM-checkable. Never append credentials or tokens (G7).
 */
object DebugLog {

    /** Maximum number of retained lines (approximately 1000). */
    const val CAPACITY = 1000

    private val lock = Any()
    private val buffer = ArrayDeque<String>(CAPACITY)

    /** Appends one line to the buffer, dropping the oldest line beyond [CAPACITY]. */
    fun append(message: String) {
        val line = "${formatTimestamp(System.currentTimeMillis())} $message"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > CAPACITY) {
                buffer.removeFirst()
            }
        }
    }

    /**
     * Snapshot of the buffered lines (oldest first). [header] lines are
     * prepended to the dump (e.g. version and capture time).
     */
    fun dump(header: List<String> = emptyList()): String {
        val lines = synchronized(lock) { buffer.toList() }
        return (header + lines).joinToString(separator = "\n")
    }

    /** Current number of buffered lines (diagnostics/checks). */
    fun size(): Int = synchronized(lock) { buffer.size }

    /** Removes all buffered lines (checks only). */
    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    private fun formatTimestamp(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))
}
