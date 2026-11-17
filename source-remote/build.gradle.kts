plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.source.remote"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":core-model"))
}
