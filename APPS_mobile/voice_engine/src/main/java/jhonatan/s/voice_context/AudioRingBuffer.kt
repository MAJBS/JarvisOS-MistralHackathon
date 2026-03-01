// voice_engine/src/main/java/jhonatan/s/voice_context/AudioRingBuffer.kt
// voice_engine/src/main/java/jhonatan/s/voice_context/AudioRingBuffer.kt
package jhonatan.s.voice_context

import java.nio.ByteBuffer

/**
 * Estructura de datos Zero-Allocation para flujo de audio continuo.
 * Evita el uso de System.arraycopy para desplazar datos.
 * Los datos se escriben en un bucle infinito sobrescribiendo los más antiguos.
 */
class AudioRingBuffer(private val capacityBytes: Int) {

    // El único array que se instanciará en toda la vida del motor
    private val buffer = ByteArray(capacityBytes)
    private var writePos = 0

    /**
     * Escribe nuevos datos en el anillo. Si llega al final, da la vuelta (wrap-around).
     */
    fun write(data: ByteArray, offset: Int, length: Int) {
        require(length <= capacityBytes) { "Los datos exceden la capacidad del RingBuffer" }

        val firstPart = minOf(length, capacityBytes - writePos)
        System.arraycopy(data, offset, buffer, writePos, firstPart)

        val secondPart = length - firstPart
        if (secondPart > 0) {
            System.arraycopy(data, offset + firstPart, buffer, 0, secondPart)
        }

        writePos = (writePos + length) % capacityBytes
    }

    /**
     * Lee una ventana exacta de tiempo y la inyecta DIRECTAMENTE en la memoria nativa (JNI).
     * Cero arrays intermedios = Cero Garbage Collection.
     */
    fun readIntoDirectBuffer(target: ByteBuffer, absoluteStartByte: Long, length: Int) {
        target.clear()

        // Aritmética modular para encontrar dónde empieza la ventana en el anillo físico
        val startPos = (absoluteStartByte % capacityBytes).toInt()

        val firstPart = minOf(length, capacityBytes - startPos)
        target.put(buffer, startPos, firstPart)

        val secondPart = length - firstPart
        if (secondPart > 0) {
            target.put(buffer, 0, secondPart)
        }

        target.flip()
    }
}
