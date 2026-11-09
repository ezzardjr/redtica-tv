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
        versionCode = 3
        versionName = "1.2"
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
}
