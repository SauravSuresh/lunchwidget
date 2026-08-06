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
        targetSdk = 35
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
