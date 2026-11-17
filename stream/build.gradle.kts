plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.stream"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
}
