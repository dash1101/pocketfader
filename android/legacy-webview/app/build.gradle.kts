plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.pocketfader.legacy"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.pocketfader.client"
        minSdk = 26
        targetSdk = 34
        versionCode = (findProperty("pfVersionCode") ?: "1").toString().toInt()
        versionName = (findProperty("pfVersionName") ?: "0.1.0").toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Pre-releases are signed with the auto-generated debug key. Proper release
            // signing (a persistent keystore) comes with the native app.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media:media:1.7.0")
}
