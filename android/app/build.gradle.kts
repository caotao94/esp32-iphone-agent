plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Set your own id before publishing anything (the iPhone app uses IA_BUNDLE_ID in ios/Config/).
    namespace = "com.example.esp32agent.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.esp32agent.android"
        // 29: MediaProjection + the runtime Bluetooth permissions this app needs behave sanely from here;
        // 34 is where FOREGROUND_SERVICE_MEDIA_PROJECTION becomes mandatory (see AndroidManifest.xml).
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // The planner/actor prompts are plain text, shared with the iPhone app (prompts/ in the repo root).
    sourceSets["main"].assets.srcDirs("src/main/assets")

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
