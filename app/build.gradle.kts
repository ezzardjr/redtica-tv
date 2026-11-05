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
        versionCode = 3
        versionName = "0.3"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "modo"
    productFlavors {
        // Para clientes de REDTICA: directo por la red, sin VPN ni claves.
        create("cliente") {
            dimension = "modo"
            applicationId = "com.redtica.tv.cliente"
            buildConfigField("String", "PLAYLIST_URL", "\"http://10.1.1.10:8000/playlist.m3u8?ts\"")
            buildConfigField("boolean", "USA_TUNEL", "false")
            buildConfigField("boolean", "USA_MULTICAST", "true")
        }
        // Prueba fuera de la red: túnel WireGuard embebido (src/prueba/assets/redtica.conf).
        create("prueba") {
            dimension = "modo"
            applicationId = "com.redtica.tv"
            buildConfigField("String", "PLAYLIST_URL", "\"http://10.1.1.10:8000/playlist.m3u8?ts\"")
            buildConfigField("boolean", "USA_TUNEL", "true")
            buildConfigField("boolean", "USA_MULTICAST", "false")
        }
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
