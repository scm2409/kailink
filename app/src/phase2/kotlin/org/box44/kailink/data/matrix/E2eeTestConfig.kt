package org.box44.kailink.data.matrix

/** Test-only crypto policy; null keeps the production defaults. */
data class E2eeTestConfig(val allowUntrustedDevices: Boolean = true)
