// AGP via buildscript classpath (instead of the plugin marker): the marker artifact
// for com.android.library is not present in the offline cache, but the actual
// AGP jar (com.android.tools.build:gradle:8.13.2) is.
buildscript {
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}
