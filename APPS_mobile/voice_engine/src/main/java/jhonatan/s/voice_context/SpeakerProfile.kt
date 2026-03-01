// voice_engine/src/main/java/jhonatan/s/voice_context/SpeakerProfile.kt
// voice_engine/src/main/java/jhonatan/s/voice_context/SpeakerProfile.kt
package jhonatan.s.voice_context

/**
 * Representa la identidad biométrica de un locutor en la sala.
 * @param name Nombre asignado al locutor (ej. "MABJS").
 * @param vector Huella de voz matemática extraída por ECAPA-TDNN.
 * @param enrollmentCount Cantidad de muestras de voz promediadas en este perfil.
 */
data class SpeakerProfile(
    val name: String,
    val vector: FloatArray,
    val enrollmentCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SpeakerProfile

        if (name != other.name) return false
        if (!vector.contentEquals(other.vector)) return false
        if (enrollmentCount != other.enrollmentCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + enrollmentCount
        return result
    }
}
