plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.x Compose compiler. On Kotlin 1.9.x, remove this line and set
    // composeOptions.kotlinCompilerExtensionVersion instead (see README).
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.rgkit.featureusage"
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
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the android.jar copy is a stub).
    testImplementation("org.json:json:20240303")
}
