// voice_engine/src/main/java/jhonatan/s/voice_context/DiarizationEngine.kt
package jhonatan.s.voice_context

import kotlin.math.max

class DiarizationEngine(
    private val speakerRepository: SpeakerProfileRepository,
    private val onTranscriptUpdated: (List<SpeakerSegment>) -> Unit,
    private val onSegmentSealed: (SpeakerSegment) -> Unit
) {
    private val conversationHistory = mutableListOf<SpeakerSegment>()
    private val SILENCE_MERGE_THRESHOLD_MS = 3500L
    // 🚀 NUEVO: Límite estricto para evitar dilución semántica en el vector
    private val MAX_SEGMENT_DURATION_MS = 30000L
    private val BIOMETRIC_THRESHOLD = 0.66f

    suspend fun processIncomingSegment(segment: VoiceSegment) {
        val currentSpeaker = identifySpeaker(segment.speakerVector)
        val lastSegment = conversationHistory.lastOrNull()

        val isSameSpeaker = lastSegment?.speakerName == currentSpeaker
        val isUnderSilenceThreshold = lastSegment != null && (segment.absoluteStartMs - lastSegment.endTimeMs) <= SILENCE_MERGE_THRESHOLD_MS
        val isUnderMaxDuration = lastSegment != null && (segment.absoluteEndMs - lastSegment.startTimeMs) <= MAX_SEGMENT_DURATION_MS

        // Si es el mismo locutor, no hubo pausa larga, Y no hemos excedido los 20 segundos -> Fusionamos
        if (lastSegment != null && isSameSpeaker && isUnderSilenceThreshold && isUnderMaxDuration) {
            val mergedText = try {
                FuzzyOverlapMerger.merge(lastSegment.text, segment.text)
            } catch (e: Exception) {
                "${lastSegment.text} ${segment.text}"
            }

            val updatedSegment = lastSegment.copy(
                text = mergedText,
                endTimeMs = max(lastSegment.endTimeMs, segment.absoluteEndMs)
            )
            conversationHistory[conversationHistory.lastIndex] = updatedSegment
        } else {
            // 🚀 SELLADO: Hubo pausa, cambio de voz, o pasaron 20 segundos.
            if (lastSegment != null) {
                onSegmentSealed(lastSegment)
            }

            val newSegment = SpeakerSegment(
                speakerName = currentSpeaker,
                startTimeMs = segment.absoluteStartMs,
                endTimeMs = segment.absoluteEndMs,
                text = segment.text
            )
            conversationHistory.add(newSegment)
        }

        onTranscriptUpdated(conversationHistory.toList())
    }

    suspend fun flushLastSegment() {
        // 🚀 FIX: Sella el último fragmento al detener el micrófono
        conversationHistory.lastOrNull()?.let {
            onSegmentSealed(it)
        }
        // Limpiamos la RAM para que no se duplique si volvemos a encender el micro
        conversationHistory.clear()
        onTranscriptUpdated(emptyList())
    }

    fun clearHistory() {
        conversationHistory.clear()
        onTranscriptUpdated(emptyList())
    }

    private suspend fun identifySpeaker(vector: FloatArray?): String {
        val profiles = speakerRepository.getAllProfiles()
        if (vector == null || profiles.isEmpty()) return "Desconocido"

        var bestMatch = "Desconocido"
        var highestScore = 0f

        for (profile in profiles) {
            val score = RustCore.cosineSimilarity(profile.vector, vector)
            if (score > highestScore) {
                highestScore = score
                if (score > BIOMETRIC_THRESHOLD) {
                    bestMatch = profile.name
                }
            }
        }
        return bestMatch
    }
}
