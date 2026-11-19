plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.chat"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":stream"))
}
