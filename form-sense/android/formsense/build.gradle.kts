plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.x Compose compiler. On Kotlin 1.9.x, remove this line and set
    // composeOptions.kotlinCompilerExtensionVersion instead (see README).
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.rgkit.formsense"
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
}
