// rag_engine/src/main/java/jhonatan/s/rag_engine/NativeBridge.kt
package jhonatan.s.rag_engine

/**
 * ============================================================================
 * CONTRATO JNI ZERO-COPY (Grado Militar)
 * ============================================================================
 */
object NativeBridge {

    init {
        System.loadLibrary("jarvis_rust_engine")
    }

    external fun initGraphRepository(
        storagePath: String,
        libraryPath: String
    ): String

    /**
     * Ingesta estándar (Documentos externos).
     * Etiqueta los datos en CozoDB como 'external_doc'.
     */
    external fun ingestDataViaMmap(
        filePath: String
    ): String

    /**
     * 🚀 NUEVO: Sincronización de Memoria Viva.
     * 1. Ejecuta Datalog para purgar todo lo etiquetado como 'live_audio'.
     * 2. Re-ingesta el archivo JSONL etiquetándolo como 'live_audio'.
     */
    external fun syncLiveMemory(
        filePath: String
    ): String

    external fun queryGraphRag(
        query: String,
        maxResults: Int
    ): String

    external fun getSystemDiagnostics(): String

    external fun runRawDatalog(
        query: String
    ): String

    external fun setSystemRule(
        key: String,
        value: String
    ): Boolean
}