plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.libxposed.service"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        aidl = true
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
}
