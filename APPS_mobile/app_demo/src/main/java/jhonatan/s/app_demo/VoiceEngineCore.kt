// app_demo/src/main/java/jhonatan/s/app_demo/VoiceEngineCore.kt
package jhonatan.s.app_demo

import android.content.Context
import android.util.Log
import jhonatan.s.voice_context.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class VoiceEngineCore(
    private val context: Context,
    private val headlessService: VoiceHeadlessService
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val speakerRepository = SpeakerProfileRepository(context)
    // 🗑️ ELIMINADO: transcriptionRepository ya no existe aquí.
    private val globalAudioCaptureManager = AudioCaptureManager()

    private val voiceManager = VoiceContextManager(
        applicationContext = context,
        speakerProfileRepository = speakerRepository,
        audioCaptureManager = globalAudioCaptureManager
    )

    private val diarizationEngine = DiarizationEngine(
        speakerRepository = speakerRepository,
        onTranscriptUpdated = { updatedLog ->
            val lastProcessed = updatedLog.lastOrNull()
            if (lastProcessed != null) {
                // Flujo UI (Tiempo Real)
                headlessService.sendTranscriptionToBrain(
                    speaker = lastProcessed.speakerName,
                    text = lastProcessed.text,
                    timestamp = lastProcessed.startTimeMs
                )
            }
        },
        onSegmentSealed = { sealedSegment ->
            // 🚀 Flujo Documental (Inmutable)
            headlessService.sendTranscriptionSealedToBrain(
                speaker = sealedSegment.speakerName,
                text = sealedSegment.text,
                startTime = sealedSegment.startTimeMs,
                endTime = sealedSegment.endTimeMs
            )
        }
    )

    private val orchestrator = ContinuousMicrophoneOrchestrator(
        audioCaptureManager = globalAudioCaptureManager,
        onSegmentProcessed = { segment ->
            scope.launch {
                diarizationEngine.processIncomingSegment(segment)
            }
        },
        onError = { error ->
            headlessService.sendEngineState("ERROR", "Fallo en orquestador: $error")
        }
    )

    init {
        scope.launch {
            voiceManager.engineState.collect { state ->
                when (state) {
                    is VoiceEngineState.VerificationSuccess -> {
                        headlessService.sendEngineState("VERIFICATION_RESULT", "${state.matchedName}|${state.score}")
                        headlessService.sendProgressUpdate(-1, 0)
                    }
                    is VoiceEngineState.Error -> {
                        headlessService.sendEngineState("ERROR", state.message)
                        headlessService.sendProgressUpdate(-1, 0)
                    }
                    is VoiceEngineState.EnrollingProfile -> {
                        headlessService.sendProgressUpdate(state.progressSeconds, state.totalSeconds)
                    }
                    is VoiceEngineState.VerifyingProfile -> {
                        headlessService.sendProgressUpdate(state.progressSeconds, state.totalSeconds)
                    }
                    is VoiceEngineState.Ready, is VoiceEngineState.Idle -> {
                        headlessService.sendProgressUpdate(-1, 0)
                    }
                    else -> {}
                }
            }
        }
    }

    fun bootEngine(modelType: String) {
        scope.launch {
            headlessService.sendEngineState("BOOTING", "Inyectando tensores: $modelType...")
            val success = voiceManager.initializeEngine(modelType)
            if (success) {
                headlessService.sendEngineState("READY", "Motor $modelType listo en RAM nativa.")
                refreshProfilesToMaster()
            } else {
                headlessService.sendEngineState("ERROR", "Fallo crítico al cargar $modelType.")
            }
        }
    }

    fun startListening() {
        diarizationEngine.clearHistory()
        orchestrator.startCapture()
        headlessService.sendEngineState("RECORDING", "Escucha activa (Whisper).")
    }

    fun stopListening() {
        orchestrator.stopCapture(onComplete = {
            scope.launch {
                diarizationEngine.flushLastSegment()
                headlessService.sendEngineState("READY", "Escucha finalizada.")
            }
        })
    }

    fun enrollSpeaker(name: String) {
        scope.launch {
            val duration = 12
            headlessService.sendEngineState("ENROLLING", "Grabando firma para $name...")
            val result = voiceManager.enrollSpeakerProfile(name, durationSeconds = duration)

            when (result) {
                is EnrollmentResult.Updated -> {
                    headlessService.sendBiometricResult(name, result.delta.euclideanShift * 100, true)
                    refreshProfilesToMaster()
                }
                is EnrollmentResult.New -> {
                    headlessService.sendBiometricResult(name, 0f, true)
                    refreshProfilesToMaster()
                }
                is EnrollmentResult.Failed -> {
                    headlessService.sendEngineState("ERROR", "Fallo: ${result.reason}")
                }
            }
            headlessService.sendProgressUpdate(-1, 0)
        }
    }

    fun verifySpeaker() {
        scope.launch {
            val duration = 8
            headlessService.sendEngineState("VERIFYING", "Verificando identidad (8s)...")
            voiceManager.verifySpeaker(durationSeconds = duration)
        }
    }

    fun deleteProfile(name: String) {
        scope.launch {
            speakerRepository.deleteSpeaker(name)
            refreshProfilesToMaster()
            headlessService.sendEngineState("READY", "Perfil $name eliminado.")
        }
    }

    fun refreshProfilesToMaster() {
        scope.launch {
            val profiles = speakerRepository.getAllProfiles()
            val jsonArray = JSONArray()

            for (p in profiles) {
                val obj = JSONObject()
                obj.put("name", p.name)
                obj.put("count", p.enrollmentCount)

                val vectorJson = JSONArray()
                p.vector.forEach { vectorJson.put(it.toDouble()) }
                obj.put("vector", vectorJson)

                jsonArray.put(obj)
            }

            headlessService.sendProfilesUpdated(jsonArray.toString())
        }
    }

    fun setParallelMode(enabled: Boolean) {
        orchestrator.isParallelMode = enabled
        val mode = if(enabled) "PARALELO (Stress)" else "SECUENCIAL"
        headlessService.sendEngineState("READY", "Modo de cómputo: $mode")
    }

    fun release() {
        orchestrator.stopCapture {}
        voiceManager.release()
        scope.cancel()
    }
}
