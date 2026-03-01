# Mantiene intacta la clase NativeBridge y sus métodos nativos
# para que el puente JNI (C++/Rust) pueda enlazarlos en tiempo de ejecución.
-keepclasseswithmembernames class jhonatan.s.rag_engine.NativeBridge {
    native <methods>;
}

# Mantiene el nombre de la clase para el System.loadLibrary
-keep class jhonatan.s.rag_engine.NativeBridge { *; }