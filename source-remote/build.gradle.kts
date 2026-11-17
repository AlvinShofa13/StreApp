plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.source.remote"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":core-model"))
}
