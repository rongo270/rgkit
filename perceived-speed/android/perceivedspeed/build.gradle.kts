plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "dev.rgkit.perceivedspeed"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // Lets unit tests run on the JVM: android.util.Log calls no-op
        // instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the android.jar copy is a stub).
    testImplementation("org.json:json:20240303")
}

// Feeds <description> in the published POM.
description = "User-felt performance: cold start, per-screen TTI, jank, tap latency and main-thread stalls with guilty stacks - one felt-score per screen."
