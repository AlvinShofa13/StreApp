plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.source.uvc"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(libs.uvc.android)
    testImplementation(libs.junit)
}
