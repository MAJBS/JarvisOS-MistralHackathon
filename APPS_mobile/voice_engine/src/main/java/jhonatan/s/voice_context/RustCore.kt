// voice_engine/src/main/java/jhonatan/s/voice_context/RustCore.kt
package jhonatan.s.voice_context

import java.nio.ByteBuffer
import kotlin.math.sqrt

object RustCore {
    init {
        System.loadLibrary("rust_core")
    }

    external fun helloFromRust(): String

    // --- ECAPA (Biometría) ---
    external fun initEcapaEngine(modelPath: String): Boolean
    external fun freeEcapaEngine() // NUEVA LÍNEA
    external fun extractVoicePrint(buffer: ByteBuffer): FloatArray?

    // modelType debe ser "whisper" o "sensevoice"
    external fun initTranscriberEngine(modelPath: String, modelType: String): Boolean
    external fun freeTranscriberEngine() // NUEVA LÍNEA
    external fun transcribeAudio(buffer: ByteBuffer): String

    /**
     * Compara dos huellas de voz (Vectores).
     * Devuelve un valor entre -1.0 y 1.0.
     * Un valor > 0.65 generalmente significa que es la misma persona.
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0f

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in v1.indices) {
            dotProduct += (v1[i] * v2[i]).toDouble()
            normA += (v1[i] * v1[i]).toDouble()
            normB += (v2[i] * v2[i]).toDouble()
        }

        if (normA == 0.0 || normB == 0.0) return 0f

        return (dotProduct / (sqrt(normA) * sqrt(normB))).toFloat()
    }
}
