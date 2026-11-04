plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.redtica.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.redtica.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-prueba"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Versión de prueba: se firma con la llave debug para instalar fácil.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // requerido por la librería WireGuard
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
