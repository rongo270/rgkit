plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.x Compose compiler. On Kotlin 1.9.x, remove this line and set
    // composeOptions.kotlinCompilerExtensionVersion instead (see README).
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "dev.rgkit.gripsense"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the android.jar copy is a stub).
    testImplementation("org.json:json:20240303")
}

// Feeds <description> in the published POM.
description = "Detects handedness and thumb reach, heatmaps taps and flags controls users strain to reach, with a Compose debug overlay."
