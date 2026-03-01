#!/bin/bash
set -e
export ANDROID_NDK_HOME=$HOME/android-ndk-r26d
export CARGO_NET_GIT_FETCH_WITH_CLI=true

# ¡ESTA ES LA CLAVE! Le dice a ort-sys que no intente descargar binarios
# y que asuma que la librería estará disponible en el sistema (Android).
export ORT_STRATEGY=system

echo "[*] Limpiando todo..."
cargo clean

echo "[*] Compilando DYNAMIC..."
cargo ndk -t arm64-v8a build --release

echo "[*] Exportando..."
JNI_LIBS_DIR="../jniLibs/arm64-v8a"
mkdir -p $JNI_LIBS_DIR
cp target/aarch64-linux-android/release/libjarvis_rust_engine.so $JNI_LIBS_DIR/