package jhonatan.s.rag_engine

/**
 * FRONTERA ZERO-COPY (JNI)
 * Kotlin = Logística. Rust = Motor Pesado.
 * Los vectores JAMÁS cruzan esta frontera.
 */
object NativeBridge {

    init {
        // El nombre exacto de la librería compartida (.so) que generará Rust
        System.loadLibrary("jarvis_rust_engine")
    }

    /**
     * FASE 2: Mapeo de Memoria (mmap).
     * @param absoluteFilePath Ruta absoluta del JSONL en el almacenamiento del dispositivo.
     * @return true si la ingesta en CozoDB fue exitosa, false en caso de error.
     */
    external fun ingestDataViaMmap(absoluteFilePath: String): Boolean

    /**
     * FASE 5: Aislamiento del Hiperespacio.
     * @param query La pregunta en texto plano.
     * @return Un String (JSON ligero) con los chunks recuperados y el contexto Datalog.
     */
    external fun queryGraphRag(query: String): String
}