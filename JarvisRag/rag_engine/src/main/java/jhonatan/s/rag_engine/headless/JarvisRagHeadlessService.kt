// rag_engine/src/main/java/jhonatan/s/rag_engine/headless/JarvisRagHeadlessService.kt
package jhonatan.s.rag_engine.headless

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import jhonatan.s.rag_engine.IngestionMetrics
import jhonatan.s.rag_engine.JarvisBootstrapper
import jhonatan.s.rag_engine.JarvisRagEngine
import jhonatan.s.rag_engine.ai.GemmaPromptOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * ============================================================================
 * NODO COGNITIVO HEADLESS (Grado Militar)
 * ============================================================================
 * Este servicio opera independientemente de la interfaz gráfica. Su único
 * propósito es servir como el hipocampo (memoria) de la IA Mayor (Gemma/Qwen).
 * Utiliza su propio CoroutineScope con un HILO ÚNICO DEDICADO para garantizar
 * que las transacciones ACID y la búsqueda ONNX no causen estrangulamiento
 * térmico (Thermal Throttling) al colisionar con los hilos internos de ONNX.
 */
class JarvisRagHeadlessService : Service() {

    companion object {
        private const val TAG = "JarvisRagHeadless"
    }

    // SupervisorJob garantiza que si una consulta falla, el servicio entero no colapsa.
    private val serviceJob = SupervisorJob()

    // 🛡️ CORRECCIÓN APLICADA: Hilo único dedicado para el motor RAG.
    // ONNX ya paraleliza internamente usando todos los núcleos disponibles de la CPU/NPU.
    // Si permitimos múltiples peticiones concurrentes a nivel de Kotlin (Dispatchers.IO),
    // el procesador colapsará. Este dispatcher encola las peticiones secuencialmente.
    private val ragDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(ragDispatcher + serviceJob)

    // El Binder local para que la IA Mayor (en el mismo proceso) se conecte sin latencia IPC
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): JarvisRagHeadlessService = this@JarvisRagHeadlessService
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "🔗 IA Mayor vinculada al Nodo Cognitivo JarvisRag.")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "🔗 IA Mayor desvinculada del Nodo Cognitivo.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "🛑 Destruyendo Nodo Cognitivo. Cancelando transacciones pendientes.")
        serviceScope.cancel() // Evita fugas de memoria de corrutinas al destruir el servicio
        ragDispatcher.close() // 🛡️ Libera el hilo dedicado devolviéndolo al sistema operativo
    }

    // ============================================================================
    // API ESTRICTA PARA LA IA MAYOR
    // ============================================================================

    /**
     * Asegura que el motor esté cargado en memoria nativa antes de operar.
     */
    suspend fun bootCognitiveCore(): Boolean = withContext(serviceScope.coroutineContext) {
        try {
            JarvisBootstrapper.prepareAndInit(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Fallo crítico en el arranque del núcleo cognitivo.", e)
            false
        }
    }

    /**
     * La IA Mayor invoca esto para inyectar nuevos recuerdos de forma asíncrona.
     */
    suspend fun ingestMemory(uri: Uri): Result<IngestionMetrics> = withContext(serviceScope.coroutineContext) {
        JarvisRagEngine.ingestDocument(applicationContext, uri)
    }

    /**
     * EL PIPELINE PRINCIPAL:
     * Recibe la pregunta cruda de la IA Mayor, busca en CozoDB/ONNX, y ensambla
     * el Super-Prompt estructurado, devolviendo la orden directa lista para ser
     * procesada por el LLM.
     */
    suspend fun requestDeterminativeContext(query: String): Result<String> = withContext(serviceScope.coroutineContext) {
        try {
            Log.d(TAG, "🧠 IA Mayor solicita contexto para: '$query'")

            // 1. Inferencia Matemática (JNI -> Rust -> ONNX -> CozoDB)
            val ragResult = JarvisRagEngine.searchContext(query, maxResults = 5)

            if (ragResult.isFailure) {
                val errorMsg = ragResult.exceptionOrNull()?.message ?: "Error desconocido"
                Log.e(TAG, "Fallo en motor de inferencia nativo: $errorMsg")
                return@withContext Result.failure(Exception(errorMsg))
            }

            val ragResponse = ragResult.getOrThrow()

            // 2. Ensamblaje del Prompt (Stateless)
            val superPrompt = GemmaPromptOrchestrator.buildDeltaPrompt(
                userQuery = query,
                ragResponse = ragResponse
            )

            Log.d(TAG, "✅ Contexto determinista ensamblado con éxito.")
            Result.success(superPrompt)

        } catch (e: Exception) {
            Log.e(TAG, "Excepción no controlada ensamblando contexto", e)
            Result.failure(e)
        }
    }
}
