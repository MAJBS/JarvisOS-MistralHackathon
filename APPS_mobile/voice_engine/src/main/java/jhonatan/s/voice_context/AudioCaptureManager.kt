// voice_engine/src/main/java/jhonatan/s/voice_context/AudioCaptureManager.kt
// voice_engine/src/main/java/jhonatan/s/voice_context/AudioCaptureManager.kt
package jhonatan.s.voice_context

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AudioCaptureManager {

    // SharedFlow con replay = 0 y extraBufferCapacity para tolerar picos de CPU en la IA
    private val _audioFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 100)
    val audioFlow: SharedFlow<ByteArray> = _audioFlow.asSharedFlow()

    var sessionStartEpochMs: Long = 0L
        private set

    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Control de concurrencia y conteo de referencias
    private val hardwareMutex = Mutex()
    private var activeClients = 0
    private var isRecording = false

    /**
     * Cualquier componente que necesite audio debe llamar a este método.
     * El micrófono físico solo se enciende si es el primer cliente.
     */
    @SuppressLint("MissingPermission")
    suspend fun requestMic() = hardwareMutex.withLock {
        activeClients++
        Log.d("AudioCaptureManager", "Micrófono solicitado. Clientes activos: $activeClients")

        if (activeClients == 1 && !isRecording) {
            startHardwareCapture()
        }
    }

    /**
     * Cualquier componente que termine de usar el audio debe llamar a este método.
     * El micrófono físico solo se apaga si ya no quedan clientes.
     */
    suspend fun releaseMic() = hardwareMutex.withLock {
        if (activeClients > 0) activeClients--
        Log.d("AudioCaptureManager", "Micrófono liberado. Clientes activos: $activeClients")

        if (activeClients == 0 && isRecording) {
            stopHardwareCapture()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startHardwareCapture() {
        isRecording = true
        captureJob = scope.launch {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 4
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioCaptureManager", "Error crítico: AudioRecord no se pudo inicializar.")
                isRecording = false
                return@launch
            }

            audioRecord.startRecording()

            // Ancla cronológica absoluta para la alineación del RAG y subtítulos
            sessionStartEpochMs = System.currentTimeMillis()
            Log.d("AudioCaptureManager", "🎤 Micrófono físico encendido. Ancla Unix: $sessionStartEpochMs")

            // Bloques de 100ms (3200 bytes) para latencia ultra baja
            val chunkBuffer = ByteArray(3200)

            try {
                while (isActive && isRecording) {
                    val readResult = audioRecord.read(chunkBuffer, 0, chunkBuffer.size)
                    if (readResult > 0) {
                        _audioFlow.emit(chunkBuffer.copyOfRange(0, readResult))
                    } else if (readResult < 0) {
                        Log.e("AudioCaptureManager", "Error iterativo del micrófono: $readResult")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioCaptureManager", "Colapso en captura global: ${e.message}")
            } finally {
                try {
                    audioRecord.stop()
                    audioRecord.release()
                } catch (e: Exception) { e.printStackTrace() }
                isRecording = false
                Log.d("AudioCaptureManager", "🛑 Micrófono físico apagado.")
            }
        }
    }

    private fun stopHardwareCapture() {
        isRecording = false
        captureJob?.cancel()
    }
}
