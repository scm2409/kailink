package org.box44.kailink.testing

import java.io.File

class CheckFailure(message: String) : AssertionError(message)

fun expectTrue(condition: Boolean, message: String) {
    if (!condition) throw CheckFailure(message)
}

fun expectFalse(condition: Boolean, message: String) {
    if (condition) throw CheckFailure(message)
}

fun <T> expectEquals(expected: T, actual: T, message: String) {
    if (expected != actual) {
        throw CheckFailure("$message — expected: $expected, actual: $actual")
    }
}

fun expectNull(value: Any?, message: String) {
    if (value != null) throw CheckFailure("$message — expected: null, actual: $value")
}

fun expectNotNull(value: Any?, message: String) {
    if (value == null) throw CheckFailure(message)
}

/**
 * Minimal check runner for the offline environment: JUnit is not available
 * in the local repository cache, therefore the JVM verification (V1) runs
 * via these checks as a dedicated Gradle task (`phase1Checks`).
 */
object Checks {

    private val lines = mutableListOf<String>()
    private var passedCount = 0
    private val failures = mutableListOf<String>()

    fun check(name: String, body: () -> Unit) {
        try {
            body()
            passedCount++
            lines += "[OK]        $name"
        } catch (t: Throwable) {
            val message = t.message ?: t::class.simpleName ?: "unknown error"
            failures += "$name — $message"
            lines += "[ERROR]  $name — $message"
        }
    }

    fun finish(reportPath: String) {
        val total = passedCount + failures.size
        val summary = "Checks: $total, passed: $passedCount, failed: ${failures.size}"
        lines += ""
        lines += summary
        failures.forEach { lines += "  AFFECTED: $it" }
        val report = File(reportPath)
        report.parentFile?.mkdirs()
        report.writeText(lines.joinToString(System.lineSeparator()) + System.lineSeparator())
        println()
        println(summary)
        println("Report: ${report.absolutePath}")
        if (failures.isNotEmpty()) {
            throw CheckFailure("Phase-1 checks failed (${failures.size} of $total). See report.")
        }
    }
}
