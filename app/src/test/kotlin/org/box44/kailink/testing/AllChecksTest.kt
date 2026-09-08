package org.box44.kailink.testing

import org.junit.Test

/**
 * JUnit-Hülle um die JVM-Prüfungen: [runAllChecks] wirft bei Fehlschlägen
 * eine [CheckFailure], sodass `testDebugUnitTest` rot färbt.
 */
class AllChecksTest {

    @Test
    fun runAllJvmChecks() {
        runAllChecks()
    }
}
