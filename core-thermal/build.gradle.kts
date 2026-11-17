plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.core.thermal"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
}
