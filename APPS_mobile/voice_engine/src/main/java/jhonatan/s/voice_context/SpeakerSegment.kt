// voice_engine/src/main/java/jhonatan/s/voice_context/SpeakerSegment.kt
package jhonatan.s.voice_context

/**
 * Representa un fragmento de diálogo procesado con identificación de locutor.
 * Debe estar en su propio archivo para garantizar visibilidad entre módulos.
 */
data class SpeakerSegment(
    val speakerName: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)