plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.sonario.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.sonario.app"
        minSdk = 28          // Android 9. 8 Elite phones are far above this.
        targetSdk = 36
        versionCode = 10
        versionName = "1.4.0"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += "arm64-v8a" }  // modern phones; keeps APK lean
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // Networking for source fetching (YouTube captions, web articles)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // HTML parsing for web-article extraction (the Jsoup analogue of BeautifulSoup)
    implementation("org.jsoup:jsoup:1.18.1")

    // PDF text extraction for the local-file picker.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Markdown rendering for the summary view (core + Material 3 module).
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.27.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")

    // On-device LLM via llama.cpp, through the Llamatik Maven library.
    // No NDK, no native build: it ships prebuilt arm64 binaries.
    implementation("com.llamatik:library-android:1.7.0")

    // Secure API key storage (hardware-backed encryption for BYOK credentials).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
