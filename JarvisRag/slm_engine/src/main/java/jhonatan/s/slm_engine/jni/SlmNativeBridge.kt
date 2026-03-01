package jhonatan.s.slm_engine.jni

import java.nio.ByteBuffer

// 🛡️ INTERFAZ DE COMUNICACIÓN C++ -> KOTLIN
interface TokenCallback {
    fun onTokenGenerated(tokenBytes: ByteArray)
}

object SlmNativeBridge {
    init {
        System.loadLibrary("slm_engine")
    }

    external fun initSlmEngine(modelPath: String): Long

    // Ahora retorna Int: 1 (Éxito), 0 (Fallo), -1 (Overflow de KV Cache)
    external fun generateTokensZeroCopy(
        enginePtr: Long,
        promptBuffer: ByteBuffer,
        promptLength: Int,
        callback: TokenCallback
    ): Int

    // Nuevo comando de amnesia inducida
    external fun resetKvCache(enginePtr: Long)

    external fun releaseSlmEngine(enginePtr: Long)
}