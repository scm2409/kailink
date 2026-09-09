import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.box44.kailink"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "org.box44.kailink"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.2-phase1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Martin's phone: arm64-v8a (policy unchanged).
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
        release {
            isMinifyEnabled = false
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
        create("emulatorDebug") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            ndk {
                abiFilters.clear()
                abiFilters += listOf("x86_64")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // Phase-2 adapter (matrix-rust-sdk) is part of the production build.
            kotlin.srcDir("src/phase2/kotlin")
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
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
    implementation("org.matrix.rustcomponents:sdk-android:26.09.08")
    implementation("org.unifiedpush.android:connector:3.3.5")
    implementation(project(":rustls-tls"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
