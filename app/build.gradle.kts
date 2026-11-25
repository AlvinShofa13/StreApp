plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.studiopocket.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.studiopocket.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.2"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-video"))
    implementation(project(":core-audio"))
    implementation(project(":core-thermal"))
    implementation(project(":source-uvc"))
    implementation(project(":source-camera"))
    implementation(project(":source-screen"))
    implementation(project(":source-remote"))
    implementation(project(":stream"))
    implementation(project(":chat"))
    implementation(project(":debug-overlay"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
