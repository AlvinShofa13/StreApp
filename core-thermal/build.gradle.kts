plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.core.thermal"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }
}
