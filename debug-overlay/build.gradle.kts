plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.debug"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
}
