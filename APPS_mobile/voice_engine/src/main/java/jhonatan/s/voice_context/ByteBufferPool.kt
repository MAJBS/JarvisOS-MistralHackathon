// voice_engine/src/main/java/jhonatan/s/voice_context/ByteBufferPool.kt
package jhonatan.s.voice_context

import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gestor de memoria nativa (Object Pool) para evitar el Garbage Collection y el LMK.
 * Pre-asigna N ByteBuffers directos y los recicla.
 */
class ByteBufferPool(
    private val poolSize: Int,
    private val bufferCapacityBytes: Int
) {
    // Usamos un Channel como una cola segura para hilos (Thread-Safe Queue)
    private val availableBuffers = Channel<ByteBuffer>(poolSize)

    init {
        // Pre-asignamos la memoria nativa UNA SOLA VEZ al instanciar la clase
        for (i in 0 until poolSize) {
            val buffer = ByteBuffer.allocateDirect(bufferCapacityBytes).order(ByteOrder.nativeOrder())
            availableBuffers.trySend(buffer)
        }
    }

    /**
     * Obtiene un buffer libre. Si todos están ocupados, suspende la corrutina hasta que uno se libere.
     */
    suspend fun acquire(): ByteBuffer {
        val buffer = availableBuffers.receive()
        buffer.clear() // Resetea los punteros internos de lectura/escritura
        return buffer
    }

    /**
     * Devuelve el buffer a la piscina para ser reutilizado.
     */
    fun release(buffer: ByteBuffer) {
        buffer.clear()
        // trySend no bloquea. Si la piscina está llena (no debería), simplemente falla silenciosamente.
        availableBuffers.trySend(buffer)
    }
}
