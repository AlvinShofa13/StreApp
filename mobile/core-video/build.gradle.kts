plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.studiopocket.core.video"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-audio"))
    testImplementation(libs.junit)
}
