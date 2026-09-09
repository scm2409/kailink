package org.box44.kailink.testing

import org.junit.Test

/**
 * JUnit wrapper around the JVM checks: on failures [runAllChecks] throws
 * a [CheckFailure], so `testDebugUnitTest` turns red.
 */
class AllChecksTest {

    @Test
    fun runAllJvmChecks() {
        runAllChecks()
    }
}
