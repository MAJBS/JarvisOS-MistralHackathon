plugins {
    // Usamos identificadores directos para evitar fallos del Version Catalog
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jhonatan.s.rag_engine"
    compileSdk = 34

    defaultConfig {
        minSdk = 26 // Requerido para mmap nativo y NIO avanzado

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // FASE 1: REGLA DE ORO DE INFRAESTRUCTURA
        // Restricción absoluta a ARM64.
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
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
    // FASE 6: Dependencias estrictas directas para la orquestación reactiva (Corrutinas y ViewModel)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}