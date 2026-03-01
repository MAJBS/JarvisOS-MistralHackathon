// voice_engine/src/main/java/jhonatan/s/voice_context/VoiceTranscriber.kt
package jhonatan.s.voice_context

// Esta interfaz deja la puerta abierta para cualquier tecnología futura (TFLite, ONNX, Rust)
interface VoiceTranscriber {
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit)
    fun stopListening()
    fun destroy()
}