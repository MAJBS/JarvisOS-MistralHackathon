# 🛡️ PROTECCIÓN JNI (GRADO MILITAR)
# Evita que R8 ofusque o elimine las clases y métodos que se comunican con C++/Rust.

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class jhonatan.s.rag_engine.NativeBridge { *; }
-keep class jhonatan.s.slm_engine.jni.SlmNativeBridge { *; }
-keep class jhonatan.s.slm_engine.jni.TokenCallback { *; }

# Proteger las Data Classes de Kotlinx Serialization (Para que Rust pueda enviar JSON)
-keep class jhonatan.s.rag_engine.EngineStatus { *; }
-keep class jhonatan.s.rag_engine.IngestionMetrics { *; }
-keep class jhonatan.s.rag_engine.RagResultNode { *; }
-keep class jhonatan.s.rag_engine.RagResponse { *; }
-keep class jhonatan.s.rag_engine.SystemDiagnostics { *; }
-keep class jhonatan.s.rag_engine.Confidence { *; }