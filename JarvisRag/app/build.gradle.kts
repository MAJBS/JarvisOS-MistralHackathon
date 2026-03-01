plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "jhonatan.s.jarvisrag"

    // 🛡️ 1. DECLARAMOS LA FIRMA COMPARTIDA EXPLÍCITA
    signingConfigs {
        create("sharedSignature") {
            storeFile = file("debug.keystore") // El archivo que acabas de pegar
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // CONFIGURACIÓN DE EMPAQUETADO NATIVO (FASE 1: INFRAESTRUCTURA)
    packaging {
        jniLibs {
            // useLegacyPackaging = true: EVITA que las librerías .so se compriman dentro del APK.
            // Esto es obligatorio para que el motor Rust pueda hacer mmap y carga directa
            // desde el almacenamiento, reduciendo el consumo de RAM.
            useLegacyPackaging = true
        }
    }

    compileSdk = 36 // Ajustado según requerimiento de tus .aar externos

    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "jhonatan.s.jarvisrag"
        minSdk = 29 // Mínimo para mmap y NIO avanzado
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Restricción absoluta a 64 bits para motores neuronales
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // 🛡️ 2. APLICAMOS LA FIRMA AL MODO DEBUG
            signingConfig = signingConfigs.getByName("sharedSignature")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // En el futuro, aquí pondrás tu llave de Release de la Play Store
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

    // 🛡️ CORRECCIÓN 2: Resolución de la Colisión de Librerías Nativas
    packaging {
        jniLibs {
            // Fuerza a Gradle a fusionar la librería estándar de C++ compartida
            // entre Rust y llama.cpp en lugar de entrar en pánico.
            pickFirsts.add("**/libc++_shared.so")
        }
    }

}

dependencies {
    // CAPA DE INTERFAZ Y CICLO DE VIDA
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
// 🧠 Conexión con los Hemisferios Cognitivos
    implementation(project(":rag_engine"))
    implementation(project(":slm_engine"))
    // SISTEMA COMPOSE (BOM maneja las versiones de forma automática)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("com.google.code.gson:gson:2.11.0")
    // ICONOS EXTENDIDOS (Corrección: Sin versión manual, usa la del BOM)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    // ENLACE ESTRICTO AL MOTOR RAG (NATIVO)
    // El módulo app es el consumidor, rag_engine es el proveedor de lógica
    implementation(project(":rag_engine"))
    // Red y APIs (Mistral & ElevenLabs)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0") // <-- CORREGIDO AQUÍ
    // TESTING (Rigor de validación)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

}