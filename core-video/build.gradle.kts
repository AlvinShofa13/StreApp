plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.core.video"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":core-model"))
}
