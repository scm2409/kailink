package at.d71.kailink.testing

fun main() {
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
