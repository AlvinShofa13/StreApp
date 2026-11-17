plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.stream"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }
}
