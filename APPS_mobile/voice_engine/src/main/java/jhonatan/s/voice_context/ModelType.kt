// voice_engine/src/main/java/jhonatan/s/voice_context/ModelType.kt
package jhonatan.s.voice_context

enum class ModelType(val displayName: String, val folderName: String?) {
    GOOGLE("Google Online (Sin Biometría)", null),
    WHISPER_TINY("Whisper Tiny (Ligero)", "whisper"),
    WHISPER_SMALL("Whisper Small (Precisión)", "whisper_small"),
    SENSE_VOICE("SenseVoice (Ultrarrápido)", "sensevoice")
}