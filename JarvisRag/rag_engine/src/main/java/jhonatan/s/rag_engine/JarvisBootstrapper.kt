// rag_engine/src/main/java/jhonatan/s/rag_engine/JarvisBootstrapper.kt
package jhonatan.s.rag_engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object JarvisBootstrapper {

    private const val TAG = "JarvisBootstrapper"
    private const val MODEL_NAME = "model_quantized.onnx"
    private const val TOKENIZER_NAME = "tokenizer.json"

    /**
     * Orquestador de arranque de Fase 2.
     * Extrae modelos con Caché Inteligente e inicializa el núcleo Rust.
     */
    suspend fun prepareAndInit(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val internalDir = context.filesDir
            val internalPath = internalDir.absolutePath
            val nativeLibDir = context.applicationInfo.nativeLibraryDir

            Log.i(TAG, ">> INICIANDO SECUENCIA DE BOOTSTRAP JARVIS RAG <<")
            Log.d(TAG, "Work Dir: $internalPath")
            Log.d(TAG, "Libs Dir: $nativeLibDir")

            // PASO 1: Extracción Inteligente de Activos (Modelos y Tokenizers)
            extractAssetSmart(context, MODEL_NAME, File(internalDir, MODEL_NAME))
            extractAssetSmart(context, TOKENIZER_NAME, File(internalDir, TOKENIZER_NAME))

            // PASO 2: Inicialización del Motor Nativo a través de la Fachada
            Log.i(TAG, "Invocando fachada del motor con telemetría...")

            // Delegamos la inicialización y el parseo del JSON a nuestra fachada blindada
            val engineResult = JarvisRagEngine.bootEngine(context)

            if (engineResult.isSuccess) {
                Log.i(TAG, "✅ [OK] MOTOR JARVIS RAG INICIALIZADO Y OPERATIVO.")
                return@withContext true
            } else {
                Log.e(TAG, "❌ [FAIL] Fallo en el núcleo. Motivo: ${engineResult.exceptionOrNull()?.message}")
                return@withContext false
            }

        } catch (e: Exception) {
            Log.e(TAG, "🔥 [CRITICAL] Excepción fatal durante el bootstrap", e)
            return@withContext false
        }
    }

    @Throws(IOException::class)
    private fun extractAssetSmart(context: Context, assetName: String, destinationFile: File) {
        val assetManager = context.assets

        // Obtenemos el tamaño real del asset para compararlo
        val assetSize = try {
            assetManager.open(assetName).use { it.available().toLong() }
        } catch (e: IOException) {
            Log.e(TAG, "No se pudo leer el tamaño del asset: $assetName", e)
            throw e
        }

        // CACHÉ INTELIGENTE: Si existe y el tamaño es idéntico, omitimos la copia (Ahorra segundos de CPU/IO)
        if (destinationFile.exists() && destinationFile.length() == assetSize) {
            Log.v(TAG, "Asset '$assetName' intacto (${assetSize} bytes). Omitiendo copia (Arranque Rápido).")
            return
        }

        Log.d(TAG, "Extrayendo '$assetName' al almacenamiento interno...")

        try {
            assetManager.open(assetName).use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    val buffer = ByteArray(16 * 1024) // Buffer de 16KB para I/O rápido
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }
            }

            if (destinationFile.exists() && destinationFile.length() > 0) {
                Log.v(TAG, "--> Extracción exitosa: ${destinationFile.name} | Tamaño: ${destinationFile.length()} bytes")
            } else {
                throw IOException("El archivo ${destinationFile.name} se creó pero está vacío.")
            }

        } catch (e: IOException) {
            Log.e(TAG, "Error IO extrayendo $assetName", e)
            if (destinationFile.exists()) destinationFile.delete() // Limpieza en caso de fallo parcial
            throw e
        }
    }
}
