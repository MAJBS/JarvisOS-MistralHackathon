plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "jhonatan.s.app_demo"
    compileSdk = 36

    // 🛡️ 1. DECLARAMOS LA FIRMA COMPARTIDA EXPLÍCITA
    signingConfigs {
        create("sharedSignature") {
            storeFile = file("debug.keystore") // El archivo que acabas de pegar
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "jhonatan.s.app_demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // 🛡️ 2. APLICAMOS LA FIRMA AL MODO DEBUG
            signingConfig = signingConfigs.getByName("sharedSignature")
        }
    } 

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        aidl = true  // <--- 🚀 AÑADE ESTA LÍNEA MÁGICA
    }
}

dependencies {
    // AQUÍ OCURRE LA MAGIA: Inyectamos tu motor como una pieza de Lego
    implementation(project(":voice_engine"))
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2") // O la versión que utilices
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}