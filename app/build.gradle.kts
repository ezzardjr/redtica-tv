plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.redtica.camarastv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.redtica.camarastv"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${System.getenv("GOOGLE_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${System.getenv("GOOGLE_CLIENT_SECRET") ?: ""}\"")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        create("fija") {
            storeFile = file("camarastv.jks")
            storePassword = "camarastv"
            keyAlias = "camarastv"
            keyPassword = "camarastv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
        debug { signingConfig = signingConfigs.getByName("fija") }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
}
