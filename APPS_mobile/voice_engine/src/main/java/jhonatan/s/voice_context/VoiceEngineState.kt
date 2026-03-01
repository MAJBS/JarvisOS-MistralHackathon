// voice_engine/src/main/java/jhonatan/s/voice_context/VoiceEngineState.kt
package jhonatan.s.voice_context

sealed class VoiceEngineState {
    object Idle : VoiceEngineState()
    object Initializing : VoiceEngineState()
    object Processing : VoiceEngineState()

    data class Ready(val model: String) : VoiceEngineState()
    data class Recording(val model: String) : VoiceEngineState()

    data class Error(val message: String, val isFatal: Boolean) : VoiceEngineState()

    data class EnrollingProfile(val name: String, val progressSeconds: Int, val totalSeconds: Int) : VoiceEngineState()
    data class VerifyingProfile(val progressSeconds: Int, val totalSeconds: Int) : VoiceEngineState()

    // Estado de éxito que incluye el porcentaje de coincidencia biométrica
    data class VerificationSuccess(val matchedName: String, val score: Float) : VoiceEngineState()
}
