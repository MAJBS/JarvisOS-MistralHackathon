// voice_engine/src/main/java/jhonatan/s/voice_context/VoiceSegment.kt
package jhonatan.s.voice_context

/**
 * Representa un fragmento de audio crudo procesado por la IA.
 */
data class VoiceSegment(
    val text: String,
    val absoluteStartMs: Long,
    val absoluteEndMs: Long,
    val speakerVector: FloatArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VoiceSegment

        if (text != other.text) return false
        if (absoluteStartMs != other.absoluteStartMs) return false
        if (absoluteEndMs != other.absoluteEndMs) return false
        if (speakerVector != null) {
            if (other.speakerVector == null) return false
            if (!speakerVector.contentEquals(other.speakerVector)) return false
        } else if (other.speakerVector != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + absoluteStartMs.hashCode()
        result = 31 * result + absoluteEndMs.hashCode()
        result = 31 * result + (speakerVector?.contentHashCode() ?: 0)
        return result
    }
}
