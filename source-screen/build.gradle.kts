plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.source.screen"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":core-model"))
}
