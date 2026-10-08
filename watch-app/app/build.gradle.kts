plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lidarscan.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lidarscan.watch"
        minSdk = 30          // Wear OS 3 and later (Galaxy Watch 4 onwards, Watch Ultra)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // The GitHub build signs with a key it keeps between builds (SIGNING_STORE),
    // so a new version installs over the old one; elsewhere the debug key
    signingConfigs {
        create("sideload") {
            val store = System.getenv("SIGNING_STORE")
            if (store != null && file(store).exists()) {
                storeFile = file(store)
                storePassword = System.getenv("SIGNING_PASSWORD") ?: "lidarguide"
                keyAlias = "lidarguide"
                keyPassword = System.getenv("SIGNING_PASSWORD") ?: "lidarguide"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("SIGNING_STORE") != null) signingConfigs.getByName("sideload") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { compose = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.7.5")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // the real org.json for JVM tests (Android's is a stub there)
}
