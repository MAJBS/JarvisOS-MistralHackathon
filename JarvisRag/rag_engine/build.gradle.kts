plugins {
    // Identificadores directos para máxima estabilidad en el sistema de construcción
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "jhonatan.s.rag_engine"
    compileSdk = 34

    ndkVersion = "26.1.10909125"

    defaultConfig {
        // Requerido para operaciones de mmap nativo y optimizaciones de memoria de Capa 2
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // FASE 1: REGLA DE ORO DE INFRAESTRUCTURA
        // Restricción absoluta a arquitectura de 64 bits para motores neuronales
        ndk {
            abiFilters.add("arm64-v8a")
        }

        // Configuración del NDK para forzar el uso de la STL compartida
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    // MAPEO ESTRICTO DE LIBRERÍAS NATIVAS
    // Garantiza que Gradle busque en la carpeta donde movimos los .so
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // RIGOR DE EMPAQUETADO (Zero-Copy Strategy)
    packaging {
        jniLibs {
            // useLegacyPackaging = true: Evita que las librerías se compriman dentro del APK.
            // Esto permite que 'dlopen' las cargue directamente vía mmap desde el almacenamiento,
            // reduciendo drásticamente el tiempo de carga y el uso de RAM.
            useLegacyPackaging = true

            // Si ONNX o Rust traen su propia versión de libc++, priorizamos la que movimos manualmente
            pickFirsts.add("**/*.so")
        }
    }

    buildTypes {
        release {
            // Minificación habilitada para reducir el tamaño del módulo RAG
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // FASE 6: Dependencias de orquestación reactiva
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
// INYECCIÓN: El motor de serialización nativo
    implementation(libs.kotlinx.serialization.json)

    // Testing (Rigor de validación)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}