// voice_engine/src/main/java/jhonatan/s/voice_context/VoiceContextManager.kt
// voice_engine/src/main/java/jhonatan/s/voice_context/VoiceContextManager.kt
package jhonatan.s.voice_context

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceContextManager(
    private val applicationContext: Context,
    private val speakerProfileRepository: SpeakerProfileRepository,
    private val audioCaptureManager: AudioCaptureManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _engineState = MutableStateFlow<VoiceEngineState>(VoiceEngineState.Idle)
    val engineState: StateFlow<VoiceEngineState> = _engineState.asStateFlow()

    private val _knownProfiles = MutableStateFlow<List<SpeakerProfile>>(emptyList())
    val knownProfiles: StateFlow<List<SpeakerProfile>> = _knownProfiles.asStateFlow()

    var activeModel: String? = null
        private set

    init {
        refreshProfilesState()
    }

    fun refreshProfilesState() {
        scope.launch {
            _knownProfiles.value = speakerProfileRepository.getAllProfiles()
        }
    }

    private suspend fun ensureEcapaInitialized(): Boolean = withContext(Dispatchers.IO) {
        try {
            val ecapaPath = AssetExtractor.copyAssetsToCache(applicationContext, "ecapa")
            val modelFile = File(ecapaPath, "model.onnx")
            if (!modelFile.exists()) return@withContext false
            return@withContext RustCore.initEcapaEngine(modelFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    suspend fun initializeEngine(modelType: String): Boolean = withContext(Dispatchers.IO) {
        _engineState.value = VoiceEngineState.Initializing
        try {
            val modelPath = AssetExtractor.copyAssetsToCache(applicationContext, modelType)
            val success = RustCore.initTranscriberEngine(modelPath, modelType)

            if (success) {
                activeModel = modelType
                _engineState.value = VoiceEngineState.Ready(modelType)
                ensureEcapaInitialized()
                return@withContext true
            } else {
                _engineState.value = VoiceEngineState.Error("Fallo al inicializar el modelo.", true)
                delay(2000)
                _engineState.value = VoiceEngineState.Idle
                return@withContext false
            }
        } catch (e: Exception) {
            _engineState.value = VoiceEngineState.Error("Excepción: ${e.message}", true)
            delay(2000)
            _engineState.value = VoiceEngineState.Idle
            return@withContext false
        }
    }

    suspend fun enrollSpeakerProfile(name: String, durationSeconds: Int = 12): EnrollmentResult = withContext(Dispatchers.IO) {
        if (_engineState.value is VoiceEngineState.Recording) return@withContext EnrollmentResult.Failed("Micrófono ocupado.")

        if (!ensureEcapaInitialized()) {
            _engineState.value = VoiceEngineState.Error("Error al cargar motor biométrico.", true)
            delay(2000)
            restorePreviousState()
            return@withContext EnrollmentResult.Failed("Fallo al inicializar ECAPA.")
        }

        _engineState.value = VoiceEngineState.EnrollingProfile(name, 0, durationSeconds)

        val buffer = recordRawAudioBuffer(durationSeconds) { progress ->
            if (progress >= 0) {
                _engineState.value = VoiceEngineState.EnrollingProfile(name, progress, durationSeconds)
            }
        }

        if (buffer != null) {
            val vector = RustCore.extractVoicePrint(buffer)
            if (vector != null) {
                val result = speakerProfileRepository.enrollOrUpdateSpeaker(name, vector)
                refreshProfilesState()
                restorePreviousState()
                return@withContext result
            } else {
                _engineState.value = VoiceEngineState.Error("Silencio detectado. Habla más fuerte.", false)
                delay(2500)
                restorePreviousState()
                return@withContext EnrollmentResult.Failed("Señal acústica vacía.")
            }
        } else {
            _engineState.value = VoiceEngineState.Error("Error de micrófono.", false)
            delay(2500)
            restorePreviousState()
            return@withContext EnrollmentResult.Failed("Error de hardware de audio.")
        }

    }

    // [FIX CRÍTICO]: Ahora es suspendida y delega la ejecución al hilo seguro del orquestador (ViewModel)
    suspend fun verifySpeaker(durationSeconds: Int = 8) = withContext(Dispatchers.IO) {
        if (_engineState.value is VoiceEngineState.Recording || _knownProfiles.value.isEmpty()) return@withContext

        if (!ensureEcapaInitialized()) {
            _engineState.value = VoiceEngineState.Error("Error al cargar motor biométrico.", true)
            delay(2000)
            restorePreviousState()
            return@withContext
        }

        _engineState.value = VoiceEngineState.VerifyingProfile(0, durationSeconds)

        val buffer = recordRawAudioBuffer(durationSeconds) { progress ->
            if (progress >= 0) {
                _engineState.value = VoiceEngineState.VerifyingProfile(progress, durationSeconds)
            }
        }

        if (buffer != null) {
            val vector = RustCore.extractVoicePrint(buffer)
            if (vector != null) {
                val bestMatch = findBestMatch(vector)
                if (bestMatch != null && bestMatch.second > 0.39f) {
                    _engineState.value = VoiceEngineState.VerificationSuccess(bestMatch.first, bestMatch.second)
                    delay(3000)
                } else {
                    _engineState.value = VoiceEngineState.Error("Voz no reconocida.", false)
                    delay(2500)
                }
            } else {
                _engineState.value = VoiceEngineState.Error("Silencio detectado.", false)
                delay(2500)
            }
        } else {
            _engineState.value = VoiceEngineState.Error("Error de micrófono.", false)
            delay(2500)
        }

        restorePreviousState()
    }

    private fun findBestMatch(vector: FloatArray): Pair<String, Float>? {
        var bestScore = -1f
        var bestName: String? = null

        for (profile in _knownProfiles.value) {
            val score = RustCore.cosineSimilarity(profile.vector, vector)
            if (score > bestScore) {
                bestScore = score
                bestName = profile.name
            }
        }
        return if (bestName != null) Pair(bestName, bestScore) else null
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordRawAudioBuffer(
        seconds: Int,
        onProgress: (Int) -> Unit
    ): ByteBuffer? = withContext(Dispatchers.IO) {
        val sampleRate = 16000
        val bytesRequired = sampleRate * 2 * seconds
        val directBuffer = ByteBuffer.allocateDirect(bytesRequired).order(ByteOrder.nativeOrder())

        audioCaptureManager.requestMic()

        var bytesRead = 0
        var currentSecond = 0

        try {
            val collectionJob = launch {
                audioCaptureManager.audioFlow.collect { chunk ->
                    if (bytesRead >= bytesRequired) return@collect

                    val spaceLeft = bytesRequired - bytesRead
                    val bytesToCopy = minOf(chunk.size, spaceLeft)

                    directBuffer.put(chunk, 0, bytesToCopy)
                    bytesRead += bytesToCopy

                    val secProgress = bytesRead / (sampleRate * 2)
                    if (secProgress > currentSecond) {
                        currentSecond = secProgress
                        withContext(Dispatchers.Main) { onProgress(currentSecond) }
                    }
                }
            }

            while (bytesRead < bytesRequired && isActive) {
                delay(20)
            }

            collectionJob.cancel()

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            audioCaptureManager.releaseMic()
        }

        directBuffer.flip()
        withContext(Dispatchers.Main) { onProgress(-1) }
        return@withContext directBuffer
    }

    private fun restorePreviousState() {
        activeModel?.let {
            _engineState.value = VoiceEngineState.Ready(it)
        } ?: run {
            _engineState.value = VoiceEngineState.Idle
        }
    }

    fun release() {
        // [FIX CRÍTICO]: Cancela los trabajos en ejecución (hilos huérfanos) pero MANTIENE el Scope vivo
        scope.coroutineContext.cancelChildren()
        RustCore.freeTranscriberEngine()
        RustCore.freeEcapaEngine()
    }
}

