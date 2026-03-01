// voice_engine/src/main/java/jhonatan/s/voice_context/BiometricDomain.kt
// voice_engine/src/main/java/jhonatan/s/voice_context/BiometricDomain.kt
package jhonatan.s.voice_context

/**
 * Representa el cambio en un parámetro individual (dimensión) del vector neuronal.
 */
data class DimensionChange(
    val index: Int,
    val oldValue: Float,
    val newValue: Float,
    val absoluteDifference: Float
)

/**
 * Contiene la radiografía completa del "Mean Pooling" (convergencia biométrica).
 */
data class BiometricDelta(
    val profileName: String,
    val oldVector: FloatArray,
    val newVector: FloatArray,
    val euclideanShift: Float, // La distancia total que se movió la identidad en el hiperespacio
    val topChanges: List<DimensionChange> // Los N parámetros que más variaron
) {
    // Sobrescribimos equals por contener arrays
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BiometricDelta
        if (profileName != other.profileName) return false
        if (!oldVector.contentEquals(other.oldVector)) return false
        if (!newVector.contentEquals(other.newVector)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = profileName.hashCode()
        result = 31 * result + oldVector.contentHashCode()
        result = 31 * result + newVector.contentHashCode()
        return result
    }
}

/**
 * Respuesta segura para la máquina de estados.
 */
sealed class EnrollmentResult {
    data class New(val profile: SpeakerProfile) : EnrollmentResult()
    data class Updated(val profile: SpeakerProfile, val delta: BiometricDelta) : EnrollmentResult()
    data class Failed(val reason: String) : EnrollmentResult()
}
