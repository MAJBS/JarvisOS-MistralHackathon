plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "jhonatan.s.slm_engine"
    compileSdk = 34

    defaultConfig {
        minSdk = 29

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-DNDEBUG")
                arguments += listOf(
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_VULKAN=OFF",
                    "-DGGML_OPENMP=ON",
                    // 🚀 MEGA PRO CORREGIDO: Fast-Math activado, pero respetando Infinitos (Softmax seguro)
                    "-DCMAKE_C_FLAGS=-march=armv8.2-a+dotprod -O3 -flto=thin -ffast-math -fno-finite-math-only",
                    "-DCMAKE_CXX_FLAGS=-march=armv8.2-a+dotprod -O3 -flto=thin -ffast-math -fno-finite-math-only"
                )
            }
        }

        // Bloqueo estricto a 64-bits.
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}