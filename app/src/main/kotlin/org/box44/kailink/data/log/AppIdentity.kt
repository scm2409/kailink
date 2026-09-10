package org.box44.kailink.data.log

/**
 * App self-identification (0.2.7-phase1): the very first [DebugLog] line
 * after app start identifies the app from the build configuration, e.g.
 * `KaiLink 0.2.7-phase1 (versionCode 5)`. [KaiLinkApp] appends it with the
 * `BuildConfig` values before anything else runs, so a "Send log" dump
 * always starts with the version. Pure Kotlin so the format is
 * JVM-checkable; no credentials ever flow into this line (G7).
 */
object AppIdentity {

    const val APP_NAME = "KaiLink"

    /** Self-identification line for the given build version/versionCode. */
    fun startupLine(versionName: String, versionCode: Int): String =
        "$APP_NAME $versionName (versionCode $versionCode)"
}
