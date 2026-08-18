plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.lunchwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.lunchwidget"
        minSdk = 26
        // ponytail: targetSdk 34 opts out of Android 15 forced edge-to-edge; bump + handle insets if ever needed
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Bundled on-device Latin OCR for receipt scanning (spec-receipt-ocr.md).
    // Bundled, not unbundled: works sideloaded/offline, no Play services fetch.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
