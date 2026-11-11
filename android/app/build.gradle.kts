plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.pocketfader.client"
    compileSdk = 34

    defaultConfig {
        // the app's identity on the phone. It keeps its first name although the app is now YTM Starcast: changing it would
        // install a second app instead of updating this one. The code keeps its own package name (namespace above).
        applicationId = "app.novalabs.pocketfader"
        // Android 5.0 (Lollipop): the oldest release Jetpack Compose supports.
        minSdk = 21
        targetSdk = 34
        versionCode = (findProperty("pfVersionCode") ?: "1").toString().toInt()
        versionName = (findProperty("pfVersionName") ?: "0.1.0").toString()
        vectorDrawables { useSupportLibrary = true }
    }

    // A persistent release key, so each new version installs over the last one. CI provides it through
    // environment variables; without them builds fall back to the throwaway debug key.
    val releaseKey = System.getenv("PF_KEYSTORE")?.takeIf { file(it).exists() }
    signingConfigs {
        if (releaseKey != null) {
            create("starcast") {
                storeFile = file(releaseKey)
                storePassword = System.getenv("PF_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PF_KEY_ALIAS")
                keyPassword = System.getenv("PF_KEYSTORE_PASSWORD")
            }
        }
    }
    val appSigning = if (releaseKey != null) signingConfigs.getByName("starcast") else signingConfigs.getByName("debug")

    buildTypes {
        release {
            // R8 shrinks the code and resources: a smaller APK that starts faster on older phones.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = appSigning
        }
        // The same app without shrinking, signed with the same key: a fallback if the shrunk build misbehaves.
        create("unshrunk") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = appSigning
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        // NewPipe Extractor uses newer Java library APIs (java.time, java.nio) that old Android versions lack
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/LICENSE*", "/META-INF/NOTICE*",
                "/META-INF/*.md", "/META-INF/INDEX.LIST", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.palette:palette:1.0.0")
    // Playing on the phone itself: ExoPlayer with a media session (notification, lock screen, headset buttons)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    // YouTube Music search and audio streams on the phone (GPL-3.0; this is why the Android app is GPL-3.0)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}
