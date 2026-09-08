import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "at.d71.kailink"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "at.d71.kailink"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.enabled = false }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

val phase1Checks = tasks.register<JavaExec>("phase1Checks") {
    group = "verification"
    description = "Fuehrt alle Phase-1-JVM-Pruefungen aus (JUnit ist im Offline-Cache nicht verfuegbar)."
    dependsOn("compileDebugUnitTestKotlin")
    mainClass.set("at.d71.kailink.testing.AllChecksKt")
    workingDir = projectDir
    classpath(
        files(layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")),
        configurations.getByName("debugUnitTestRuntimeClasspath"),
    )
    systemProperty("file.encoding", "UTF-8")
}

tasks.named("check") {
    dependsOn(phase1Checks)
}
