// AGP über buildscript-classpath (statt Plugin-Marker): der Marker-Artefakt
// für com.android.library ist im Offline-Cache nicht vorhanden, das eigentliche
// AGP-Jar (com.android.tools.build:gradle:8.13.2) schon.
buildscript {
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}
