plugins {
    id("com.android.library")
}

android {
    namespace = "com.akari.ppx.xp.modern.api101"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    compileOnly(Libs.libxposed_api)
}
