plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.avatarreceiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.avatarreceiver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
