plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.core.audio"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":core-model"))
    testImplementation(libs.junit)
}
