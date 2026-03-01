package jhonatan.s.slm_engine

import android.util.Log
import jhonatan.s.slm_engine.jni.SlmNativeBridge
import jhonatan.s.slm_engine.jni.TokenCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer

object SlmEngineManager {
    @Volatile
    private var enginePtr: Long = 0L
    private val mutex = Mutex()

    // Ahora es suspend para respetar el Mutex sin bloquear el hilo
    suspend fun loadModel(modelPath: String): Boolean = mutex.withLock {
        if (enginePtr != 0L) return true
        enginePtr = SlmNativeBridge.initSlmEngine(modelPath)
        return enginePtr != 0L
    }

    suspend fun resetMemory() = mutex.withLock {
        if (enginePtr != 0L) {
            SlmNativeBridge.resetKvCache(enginePtr)
            Log.i("JARVIS_SLM_MANAGER", "Orden de Purga enviada al kernel C++.")
        }
    }

    fun generateResponseStateful(deltaPrompt: String): Flow<String> = callbackFlow {
        val currentPtr = enginePtr // Lectura volátil segura
        if (currentPtr == 0L) {
            close(Exception("El motor SLM no está cargado."))
            return@callbackFlow
        }

        val promptBytes = deltaPrompt.toByteArray(Charsets.UTF_8)
        val promptBuffer = ByteBuffer.allocateDirect(promptBytes.size)
        promptBuffer.put(promptBytes)

        val callback = object : TokenCallback {
            override fun onTokenGenerated(tokenBytes: ByteArray) {
                val tokenStr = String(tokenBytes, Charsets.UTF_8)
                trySend(tokenStr)
            }
        }

        val result = SlmNativeBridge.generateTokensZeroCopy(
            currentPtr,
            promptBuffer,
            promptBytes.size,
            callback
        )

        when (result) {
            1 -> close()
            -1 -> close(Exception("KV_CACHE_OVERFLOW"))
            else -> close(Exception("Fallo interno en decodificación auto-regresiva C++"))
        }

        awaitClose { }
    }

    suspend fun release() = mutex.withLock {
        if (enginePtr != 0L) {
            SlmNativeBridge.releaseSlmEngine(enginePtr)
            enginePtr = 0L
        }
    }
}