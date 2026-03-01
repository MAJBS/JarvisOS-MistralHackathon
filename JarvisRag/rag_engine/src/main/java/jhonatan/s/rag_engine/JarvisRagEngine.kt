// rag_engine/src/main/java/jhonatan/s/rag_engine/JarvisRagEngine.kt
package jhonatan.s.rag_engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileOutputStream

// ============================================================================
// MODELOS DE DATOS ESTRICTOS TIPADOS (El Contrato JNI -> Kotlin)
// ============================================================================
@Serializable
data class EngineStatus(
    val status: String = "fatal",
    val error: String? = null,
    val logs: List<String> = emptyList(),
    val errores: List<String> = emptyList()
)

@Serializable
data class IngestionMetrics(
    val status: String = "error",
    @SerialName("chunks_processed") val chunksCreated: Int = 0,
    @SerialName("execution_time_ms") val executionTimeMs: Long = 0L,
    val error: String? = null
)

@Serializable
enum class Confidence { ALTA, MEDIA, NULA }

@Serializable
data class RagResultNode(
    @SerialName("chunk_id") val chunkId: String,
    val speaker: String = "UNKNOWN",
    val text: String,
    val distance: Float = 0.0f,
    @SerialName("start_time") val startTime: Long = 0L,
    @SerialName("end_time") val endTime: Long = 0L
)

@Serializable
data class RagResponse(
    val status: String = "error",
    val error: String? = null,
    @SerialName("confidence") val confidenceLevel: Confidence = Confidence.NULA,
    val results: List<RagResultNode> = emptyList()
)

@Serializable
data class SystemDiagnostics(
    @SerialName("onnx_loaded") val onnxLoaded: Boolean = false,
    @SerialName("db_connected") val dbConnected: Boolean = false,
    @SerialName("tables_found") val tablesFound: List<String> = emptyList(),
    val counts: Map<String, Long> = emptyMap(),
    val error: String? = null
)

// Wrapper de dominio interno (no serializado por la red)
data class DomainEngineStatus(val isOperational: Boolean, val message: String, val logs: List<String> = emptyList())

// ============================================================================
// LA FACHADA DEL MOTOR (Agnóstica, Blindada y Reactiva)
// ============================================================================
object JarvisRagEngine {
    private const val TAG = "JarvisRagEngine"
    private const val BUFFER_SIZE = 16 * 1024

    // 🛡️ CORRECCIÓN APLICADA: El parser ahora vive DENTRO del objeto
    // para que RagViewModel pueda acceder a él vía JarvisRagEngine.jarvisJsonParser
    val jarvisJsonParser = Json {
        ignoreUnknownKeys = true  // Inmunidad ante nuevos campos de Rust
        isLenient = true          // Tolerancia a comillas malformadas
        coerceInputValues = true  // Fuerza null safety automático
    }

    suspend fun bootEngine(context: Context): Result<DomainEngineStatus> = withContext(Dispatchers.IO) {
        try {
            val storagePath = context.filesDir.absolutePath
            val libPath = context.applicationInfo.nativeLibraryDir

            Log.i(TAG, "Inyectando parámetros de inicialización a Rust...")
            val responseJsonString = NativeBridge.initGraphRepository(storagePath, libPath)

            // Decodificación ultra-rápida sin reflection
            val statusData = jarvisJsonParser.decodeFromString<EngineStatus>(responseJsonString)

            if (statusData.status == "success") {
                Log.i(TAG, "✅ [MOTOR NATIVO ON] Secuencia de arranque:\n${statusData.logs.joinToString("\n")}")
                Result.success(DomainEngineStatus(true, "Motor Multimodal CozoDB/ONNX Operativo.", statusData.logs))
            } else {
                val errorMsg = statusData.error ?: statusData.errores.joinToString(" | ")
                Log.e(TAG, "❌ [CRÍTICO] Fallo Estructural en Rust: $errorMsg")
                Result.failure(Exception("Fallo en inicialización de núcleo: $errorMsg"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción no controlada en bootEngine (JVM/JNI/Serialization)", e)
            Result.failure(e)
        }
    }

    suspend fun ingestDocument(context: Context, uri: Uri): Result<IngestionMetrics> = withContext(Dispatchers.IO) {
        val fileName = getFileName(context, uri) ?: "doc_${System.currentTimeMillis()}.raw"
        val cachedFile = File(context.cacheDir, fileName)

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cachedFile).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }

            if (!cachedFile.exists() || cachedFile.length() == 0L) {
                return@withContext Result.failure(Exception("El archivo transferido está vacío o es inaccesible."))
            }

            val metricsJsonString = NativeBridge.ingestDataViaMmap(cachedFile.absolutePath)

            // Decodificación Tipada
            val metrics = jarvisJsonParser.decodeFromString<IngestionMetrics>(metricsJsonString)

            if (metrics.error != null || metrics.status != "ok") {
                return@withContext Result.failure(Exception(metrics.error ?: "Fallo desconocido en ingesta Rust"))
            }

            Result.success(metrics)

        } catch (e: Exception) {
            Log.e(TAG, "Error IO, JNI o Serialización en ingestDocument", e)
            Result.failure(e)
        } finally {
            if (cachedFile.exists()) {
                val deleted = cachedFile.delete()
                if (!deleted) Log.w(TAG, "Advertencia: No se pudo eliminar el archivo temporal ${cachedFile.name}")
            }
        }
    }

    // 🚀 NUEVO: Fachada segura para sincronizar memoria viva sin exponer JSON al módulo App
    suspend fun syncLiveMemory(filePath: String): Result<IngestionMetrics> = withContext(Dispatchers.IO) {
        try {
            val metricsJsonString = NativeBridge.syncLiveMemory(filePath)
            val metrics = jarvisJsonParser.decodeFromString<IngestionMetrics>(metricsJsonString)

            if (metrics.error != null || metrics.status != "ok") {
                Result.failure(Exception(metrics.error ?: "Fallo desconocido en sync Rust"))
            } else {
                Result.success(metrics)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en syncLiveMemory", e)
            Result.failure(e)
        }
    }

    suspend fun searchContext(query: String, maxResults: Int = 5): Result<RagResponse> = withContext(Dispatchers.IO) {
        try {
            val jsonString = NativeBridge.queryGraphRag(query, maxResults)

            // Decodificación Mágica y Tipada: Rust String -> Kotlin Data Class directo
            val responseData = jarvisJsonParser.decodeFromString<RagResponse>(jsonString)

            if (responseData.error != null) {
                return@withContext Result.failure(Exception(responseData.error))
            }

            Result.success(responseData)
        } catch (e: Exception) {
            Log.e(TAG, "Excepción durante la búsqueda GraphRAG", e)
            Result.failure(e)
        }
    }

    suspend fun setSystemRule(key: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        val success = NativeBridge.setSystemRule(key, value)
        if (success) Result.success(Unit) else Result.failure(Exception("Fallo al escribir regla en el índice LSM de CozoDB"))
    }

    suspend fun runOracleQuery(query: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val jsonString = NativeBridge.runRawDatalog(query)
            Result.success(jsonString)
        } catch (e: Throwable) {
            Log.e(TAG, "Excepción Fatal/JNI en el Oráculo Datalog", e)
            Result.failure(e)
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        return result ?: uri.path?.let { File(it).name }
    }
}
