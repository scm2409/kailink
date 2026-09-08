package at.d71.kailink.testing

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
        throw CheckFailure("$message — erwartet: $expected, ist: $actual")
    }
}

fun expectNull(value: Any?, message: String) {
    if (value != null) throw CheckFailure("$message — erwartet: null, ist: $value")
}

fun expectNotNull(value: Any?, message: String) {
    if (value == null) throw CheckFailure(message)
}

/**
 * Minimaler Prüf-Runner für die Offline-Umgebung: JUnit ist im lokalen
 * Repository-Cache nicht vorhanden, daher läuft die JVM-Verifikation (V1)
 * über diese Prüfungen als eigene Gradle-Aufgabe (`phase1Checks`).
 */
object Checks {

    private val lines = mutableListOf<String>()
    private var passedCount = 0
    private val failures = mutableListOf<String>()

    fun check(name: String, body: () -> Unit) {
        try {
            body()
            passedCount++
            lines += "[OK]      $name"
        } catch (t: Throwable) {
            val message = t.message ?: t::class.simpleName ?: "unbekannter Fehler"
            failures += "$name — $message"
            lines += "[FEHLER]  $name — $message"
        }
    }

    fun finish(reportPath: String) {
        val total = passedCount + failures.size
        val summary = "Prüfungen: $total, bestanden: $passedCount, fehlgeschlagen: ${failures.size}"
        lines += ""
        lines += summary
        failures.forEach { lines += "  BETROFFEN: $it" }
        val report = File(reportPath)
        report.parentFile?.mkdirs()
        report.writeText(lines.joinToString(System.lineSeparator()) + System.lineSeparator())
        println()
        println(summary)
        println("Bericht: ${report.absolutePath}")
        if (failures.isNotEmpty()) {
            throw CheckFailure("Phase-1-Prüfungen fehlgeschlagen (${failures.size} von $total). Siehe Bericht.")
        }
    }
}
