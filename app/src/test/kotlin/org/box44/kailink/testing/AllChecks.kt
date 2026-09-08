package org.box44.kailink.testing

/**
 * Führt sämtliche JVM-Prüfungen (V1) aus und schreibt den Bericht
 * (build/reports/phase1-checks.txt). Einstiegspunkt für die gewöhnliche
 * JUnit-Aufgabe (`testDebugUnitTest`, siehe AllChecksTest) sowie optional
 * für `main()` über JavaExec.
 */
fun runAllChecks() {
    timelineReducerChecks()
    fileSessionStoreChecks()
    pushControllerChecks()
    loginViewModelChecks()
    roomListViewModelChecks()
    timelineViewModelChecks()
    inMemoryChannelClientChecks()
    pushChainChecks()
    Checks.finish("build/reports/phase1-checks.txt")
}
