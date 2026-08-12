plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "dev.rgkit.screenshotiq"
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
}

dependencies {
    // On-device OCR — bundled model, no API key, no network. Adds ~4 MB.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}

// Feeds <description> in the published POM.
description = "Detects screenshots and classifies them on-device (receipt, error, chat, ticket, map, product, code), extracts entities and suggests actions."
