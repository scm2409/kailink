package org.box44.kailink.testing

/**
 * Runs all JVM checks (V1) and writes the report
 * (build/reports/phase1-checks.txt). Entry point for the ordinary
 * JUnit task (`testDebugUnitTest`, see AllChecksTest) as well as optionally
 * for `main()` via JavaExec.
 */
fun runAllChecks() {
    timelineReducerChecks()
    fileSessionStoreChecks()
    pushControllerChecks()
    pushNotificationPayloadChecks()
    pushPayloadChecks()
    pushPayloadDiagnosticsChecks()
    pushMessageHandlerChecks()
    pushModeDiagnosticsChecks()
    loginViewModelChecks()
    roomListViewModelChecks()
    timelineViewModelChecks()
    inMemoryChannelClientChecks()
    pushChainChecks()
    matrixTlsErrorMappingChecks()
    slidingSyncChecks()
    notificationClientVersionChecks()
    debugLogChecks()
    sdkLogChecks()
    sendDebugLogChecks()
    Checks.finish("build/reports/phase1-checks.txt")
}
