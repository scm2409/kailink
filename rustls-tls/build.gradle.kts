import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Single-class vendoring of the rustls-platform-verifier Android component,
// in the style of element-hq/element-x-android PR 6610: the native library
// (libmatrix_sdk_ffi.so, rustls-platform-verifier 0.6.2) looks up
// org.rustls.platformverifier.CertificateVerifier via JNI from the app class
// loader, so the class must be part of the build. The Kotlin file matches the
// vendored source except for the local port of matrix-rust-sdk PR #6323
// (fixes #6319: the network-based OCSP/CRL revocation fetch reported valid
// certificates as InvalidCertificate(Revoked)); see rustls-tls/README.md and
// docs/decisions.md. BuildConfig.TEST=false replaces the one the original AAR
// generated. Do not weaken TLS here.
android {
    namespace = "org.rustls.platformverifier"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        buildConfigField("boolean", "TEST", "false")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
