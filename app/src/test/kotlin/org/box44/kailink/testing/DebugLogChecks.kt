package org.box44.kailink.testing

import org.box44.kailink.BuildConfig
import org.box44.kailink.data.log.AppIdentity
import org.box44.kailink.data.log.DebugLog
import java.util.concurrent.CountDownLatch

fun debugLogChecks() {

    Checks.check("startup identity line is built from versionName and versionCode") {
        val line = AppIdentity.startupLine(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        expectTrue(
            Regex("""KaiLink \S+ \(versionCode \d+\)""").matches(line),
            "line matches the self-identification format",
        )
        expectTrue(line.contains(BuildConfig.VERSION_NAME), "versionName contained")
        expectTrue(line.contains("versionCode ${BuildConfig.VERSION_CODE}"), "versionCode contained")
    }

    Checks.check("first DebugLog line after app start contains the version string") {
        DebugLog.clear()
        // Simulates the app-start order: the identity line is appended before
        // anything else (KaiLinkApp.onCreate does this with BuildConfig).
        DebugLog.append(AppIdentity.startupLine(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        DebugLog.append("later line")
        val firstLine = DebugLog.dump().lineSequence().first()
        expectTrue(
            firstLine.contains("KaiLink ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})"),
            "first DebugLog line contains the version string",
        )
    }

    Checks.check("ring buffer retains appended lines in order") {
        DebugLog.clear()
        DebugLog.append("line-1")
        DebugLog.append("line-2")
        val dump = DebugLog.dump()
        expectTrue(dump.contains("line-1"), "first line retained")
        expectTrue(dump.contains("line-2"), "second line retained")
        expectTrue(dump.indexOf("line-1") < dump.indexOf("line-2"), "oldest first ordering")
    }

    Checks.check("ring buffer evicts oldest beyond capacity") {
        DebugLog.clear()
        val beyond = DebugLog.CAPACITY + 50
        repeat(beyond) { DebugLog.append("overflow-$it") }
        expectEquals(DebugLog.CAPACITY, DebugLog.size(), "size capped at capacity")
        val dump = DebugLog.dump()
        expectFalse(dump.contains("overflow-0"), "oldest line evicted")
        expectTrue(dump.contains("overflow-${beyond - 1}"), "newest line retained")
        expectFalse(dump.contains("overflow-40\n"), "only the newest CAPACITY lines remain")
    }

    Checks.check("dump prepends header lines") {
        DebugLog.clear()
        DebugLog.append("payload")
        val dump = DebugLog.dump(header = listOf("KaiLink debug log", "version-under-test"))
        expectTrue(dump.startsWith("KaiLink debug log\nversion-under-test\n"), "header prepended")
    }

    Checks.check("append is thread-safe under concurrent writers") {
        DebugLog.clear()
        val writers = 4
        val perWriter = 100
        val ready = CountDownLatch(writers)
        val done = CountDownLatch(writers)
        repeat(writers) { w ->
            Thread {
                ready.countDown()
                ready.await()
                repeat(perWriter) { i -> DebugLog.append("w$w-$i") }
                done.countDown()
            }.apply {
                start()
            }
        }
        done.await()
        expectEquals(writers * perWriter, DebugLog.size(), "all concurrent lines retained")
    }
}
