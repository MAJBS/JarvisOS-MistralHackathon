// voice_engine/src/main/java/jhonatan/s/voice_context/ContinuousMicrophoneOrchestrator.kt
package jhonatan.s.voice_context

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ContinuousMicrophoneOrchestrator(
    private val audioCaptureManager: AudioCaptureManager,
    private val onSegmentProcessed: (VoiceSegment) -> Unit,
    private val onError: (String) -> Unit
) {
    var isParallelMode: Boolean = false

    private val BYTES_PER_SECOND = 32_000
    private val BYTES_PER_MS = 32
    private val WINDOW_SECONDS = 30
    private val OVERLAP_SECONDS = 2

    private val WINDOW_SIZE_BYTES = WINDOW_SECONDS * BYTES_PER_SECOND
    private val ADVANCE_SIZE_BYTES = (WINDOW_SECONDS - OVERLAP_SECONDS) * BYTES_PER_SECOND

    // --- PARÁMETROS DE DIARIZACIÓN (SLIDING WINDOW) ---
    private val SLIDING_WINDOW_BYTES = 3 * BYTES_PER_SECOND // Ventana de 3 segundos
    private val STRIDE_BYTES = 1 * BYTES_PER_SECOND         // Avance de 1 segundo
    private val SPEAKER_SHIFT_THRESHOLD = 0.55f             // Umbral de cambio de locutor

    // --- ZERO ALLOCATION ---
    private val RING_BUFFER_CAPACITY = 60 * BYTES_PER_SECOND
    private val ringBuffer = AudioRingBuffer(RING_BUFFER_CAPACITY)
    private val bufferPool = ByteBufferPool(poolSize = 10, bufferCapacityBytes = WINDOW_SIZE_BYTES)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRecording = false
    private var processingJob: Job? = null

    private var globalBytesReceived = 0L
    private var nextWindowStartByte = 0L

    private val activeInferences = mutableListOf<Job>()
    private val inferenceMutex = Mutex()

    fun startCapture() {
        if (isRecording) return
        isRecording = true
        globalBytesReceived = 0L
        nextWindowStartByte = 0L

        scope.launch(Dispatchers.IO) {
            audioCaptureManager.requestMic()

            processingJob = scope.launch(Dispatchers.Default) {
                try {
                    audioCaptureManager.audioFlow.collect { chunk ->
                        if (!isRecording) return@collect

                        ringBuffer.write(chunk, 0, chunk.size)
                        globalBytesReceived += chunk.size

                        while (globalBytesReceived - nextWindowStartByte >= WINDOW_SIZE_BYTES) {
                            val windowStartMs = audioCaptureManager.sessionStartEpochMs + (nextWindowStartByte / BYTES_PER_MS)

                            // SNAPSHOT SINCRÓNICO (Evita corrupción por lag de CPU)
                            val masterBuffer = bufferPool.acquire()
                            ringBuffer.readIntoDirectBuffer(masterBuffer, nextWindowStartByte, WINDOW_SIZE_BYTES)

                            val job = dispatchToIA(masterBuffer, windowStartMs, WINDOW_SIZE_BYTES, true)

                            inferenceMutex.withLock {
                                activeInferences.add(job)
                                activeInferences.removeAll { it.isCompleted }
                            }

                            nextWindowStartByte += ADVANCE_SIZE_BYTES
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Orchestrator", "Error en el pipeline de orquestación: ${e.message}")
                }
            }
        }
    }

    fun stopCapture(onComplete: () -> Unit) {
        if (!isRecording) return
        isRecording = false

        scope.launch(Dispatchers.IO) {
            processingJob?.cancelAndJoin()
            audioCaptureManager.releaseMic()

            val remainingBytes = (globalBytesReceived - nextWindowStartByte).toInt()
            if (remainingBytes > BYTES_PER_SECOND) {
                val windowStartMs = audioCaptureManager.sessionStartEpochMs + (nextWindowStartByte / BYTES_PER_MS)

                val masterBuffer = ByteBuffer.allocateDirect(remainingBytes).order(ByteOrder.nativeOrder())
                ringBuffer.readIntoDirectBuffer(masterBuffer, nextWindowStartByte, remainingBytes)

                val finalJob = dispatchToIA(masterBuffer, windowStartMs, remainingBytes, false)
                inferenceMutex.withLock { activeInferences.add(finalJob) }
            }

            val jobsToAwait = inferenceMutex.withLock { activeInferences.toList() }
            jobsToAwait.joinAll()

            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    private fun dispatchToIA(
        masterBuffer: ByteBuffer,
        windowStartMs: Long,
        lengthToRead: Int,
        isStandardWindow: Boolean
    ): Job {
        return scope.launch(Dispatchers.IO) {
            try {
                // ====================================================================
                // FASE 1: EXTRACCIÓN BIOMÉTRICA POR VENTANA DESLIZANTE (SLIDING WINDOW)
                // ====================================================================
                data class WindowData(val vector: FloatArray?)
                val windows = mutableListOf<WindowData>()

                if (lengthToRead < SLIDING_WINDOW_BYTES) {
                    // Fragmento muy corto, extracción única
                    masterBuffer.position(0).limit(lengthToRead)
                    val slice = masterBuffer.slice().order(ByteOrder.nativeOrder())
                    windows.add(WindowData(RustCore.extractVoicePrint(slice)))
                } else {
                    // Mapeo topológico del bloque de 30s
                    val numWindows = ((lengthToRead - SLIDING_WINDOW_BYTES) / STRIDE_BYTES) + 1
                    for (i in 0 until numWindows) {
                        val start = i * STRIDE_BYTES
                        masterBuffer.position(start).limit(start + SLIDING_WINDOW_BYTES)
                        val slice = masterBuffer.slice().order(ByteOrder.nativeOrder())
                        windows.add(WindowData(RustCore.extractVoicePrint(slice)))
                    }
                }

                // ====================================================================
                // FASE 2: AGRUPAMIENTO CIEGO Y CORTE QUIRÚRGICO (BLIND CLUSTERING)
                // ====================================================================
                data class SubSegment(val startByte: Int, val endByte: Int, val vector: FloatArray?)
                val subSegments = mutableListOf<SubSegment>()

                if (windows.size <= 1) {
                    subSegments.add(SubSegment(0, lengthToRead, windows.firstOrNull()?.vector))
                } else {
                    var currentStartByte = 0
                    var currentVector = windows[0].vector

                    for (i in 1 until windows.size) {
                        val prevVec = currentVector
                        val currVec = windows[i].vector

                        // Cálculo de distancia coseno entre el segundo T y el T-1
                        val similarity = if (prevVec != null && currVec != null) {
                            RustCore.cosineSimilarity(prevVec, currVec)
                        } else if (prevVec == null && currVec == null) {
                            1.0f // Ambos son silencio, continúan juntos
                        } else {
                            0.0f // Transición abrupta Voz <-> Silencio
                        }

                        // Si la similitud cae, significa que otra persona tomó la palabra
                        if (similarity < SPEAKER_SHIFT_THRESHOLD) {
                            val cutByte = i * STRIDE_BYTES
                            subSegments.add(SubSegment(currentStartByte, cutByte, currentVector))

                            currentStartByte = cutByte
                            currentVector = currVec
                        }
                    }
                    // Añadir el remanente final
                    subSegments.add(SubSegment(currentStartByte, lengthToRead, currentVector))
                }

                // ====================================================================
                // FASE 3: TRANSCRIPCIÓN PARALELA DE SUB-SEGMENTOS
                // ====================================================================
                val deferredTranscriptions = subSegments.map { seg ->
                    async {
                        // Si el vector es nulo, es puro silencio. Ahorramos CPU ignorándolo.
                        if (seg.vector == null) return@async null

                        // Slicing seguro para hilos (Zero-Copy)
                        val slice: ByteBuffer
                        synchronized(masterBuffer) {
                            masterBuffer.position(seg.startByte)
                            masterBuffer.limit(seg.endByte)
                            slice = masterBuffer.slice().order(ByteOrder.nativeOrder())
                        }

                        val jsonResponse = RustCore.transcribeAudio(slice)
                        val json = JSONObject(jsonResponse)
                        val text = json.optString("text", "")

                        if (text.isNotBlank()) {
                            // Alineación temporal absoluta del sub-segmento
                            val segStartMs = windowStartMs + (seg.startByte / BYTES_PER_MS)
                            val relativeStartMs = json.optLong("start_offset_ms", 0)
                            val relativeEndMs = json.optLong("end_offset_ms", 0)

                            VoiceSegment(
                                text = text,
                                absoluteStartMs = segStartMs + relativeStartMs,
                                absoluteEndMs = segStartMs + relativeEndMs,
                                speakerVector = seg.vector
                            )
                        } else null
                    }
                }

                // Ejecución de la barrera
                val results = if (isParallelMode) {
                    deferredTranscriptions.awaitAll()
                } else {
                    deferredTranscriptions.map { it.await() }
                }

                // Emisión a la UI y al JSONL
                withContext(Dispatchers.Main) {
                    results.filterNotNull().forEach { segment ->
                        onSegmentProcessed(segment)
                    }
                }

            } catch (e: Exception) {
                Log.e("Orchestrator", "Fallo en inferencia: ${e.message}")
            } finally {
                if (isStandardWindow) {
                    bufferPool.release(masterBuffer)
                }
            }
        }
    }
}
