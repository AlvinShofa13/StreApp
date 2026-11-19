plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.studiopocket.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.studiopocket.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
}
