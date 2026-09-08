package org.box44.kailink

import org.junit.Test

class MatrixE2eTest {
    @Test
    fun gatewayHealthProbe() {
        val harness = E2eHarness()
        val gateway = harness.probeGateway()
        harness.report("Gateway probe reachable=${gateway ?: "none"}")
    }
}
