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
        minSdk = 28
        targetSdk = 36
        versionCode = 11
        versionName = "1.5.0"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += "arm64-v8a" }
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

    // Networking for source fetching and resumable model downloads.
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // Local document extraction.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Markdown rendering for summary output.
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.27.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")

    // Latest Llamatik/llama.cpp Android runtime for Qwen3, Gemma 3n and LFM2 GGUFs.
    implementation("com.llamatik:library-android:1.8.1")
}
