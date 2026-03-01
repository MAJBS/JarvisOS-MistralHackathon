// voice_engine/src/main/java/jhonatan/s/voice_context/FuzzyOverlapMerger.kt
// voice_engine/src/main/java/jhonatan/s/voice_context/FuzzyOverlapMerger.kt
package jhonatan.s.voice_context

import kotlin.math.max
import kotlin.math.min

/**
 * Motor de Alineación de Secuencias Difusa (Fuzzy Sequence Alignment).
 * Diseñado para fusionar los 2 segundos de solapamiento (overlap) entre bloques de Whisper,
 * tolerando variaciones de puntuación, capitalización y pequeñas alucinaciones.
 */
object FuzzyOverlapMerger {

    // Cuántas palabras al final del Bloque A y al principio del Bloque B analizaremos.
    // 2 segundos de audio humano rara vez superan las 10-15 palabras.
    private const val MAX_OVERLAP_WORDS = 15

    // Similitud mínima requerida (0.0 a 1.0) para considerar que dos palabras son "la misma".
    private const val WORD_SIMILARITY_THRESHOLD = 0.80

    fun merge(textA: String, textB: String): String {
        if (textA.isBlank()) return textB
        if (textB.isBlank()) return textA

        // 1. Tokenización preservando el formato original (para la reconstrucción)
        val originalWordsA = textA.trim().split("\\s+".toRegex())
        val originalWordsB = textB.trim().split("\\s+".toRegex())

        // 2. Tokenización normalizada (para la comparación matemática)
        val normA = originalWordsA.map { normalizeWord(it) }
        val normB = originalWordsB.map { normalizeWord(it) }

        val searchLength = min(min(normA.size, normB.size), MAX_OVERLAP_WORDS)
        var bestOverlapCount = 0
        var bestOverlapScore = 0.0

        // 3. Búsqueda del mejor empalme (Suffix de A vs Prefix de B)
        for (overlapSize in 1..searchLength) {
            val suffixA = normA.takeLast(overlapSize)
            val prefixB = normB.take(overlapSize)

            var currentScore = 0.0
            for (i in 0 until overlapSize) {
                currentScore += calculateWordSimilarity(suffixA[i], prefixB[i])
            }

            // Promedio de similitud para este tamaño de solapamiento
            val averageScore = currentScore / overlapSize

            // Si el bloque coincide con alta precisión, registramos este tamaño como el mejor candidato
            if (averageScore >= WORD_SIMILARITY_THRESHOLD && currentScore > bestOverlapScore) {
                bestOverlapScore = currentScore
                bestOverlapCount = overlapSize
            }
        }

        // 4. Fusión Quirúrgica
        return if (bestOverlapCount > 0) {
            // Tomamos todo el texto A, y le concatenamos el texto B omitiendo las palabras solapadas
            val remainingB = originalWordsB.drop(bestOverlapCount).joinToString(" ")
            if (remainingB.isEmpty()) textA else "$textA $remainingB"
        } else {
            // Si no hay solapamiento lógico (ej. silencio en medio), concatenación pura
            "$textA $textB"
        }
    }

    /**
     * Elimina signos de puntuación y pasa a minúsculas para una comparación acústica pura.
     */
    private fun normalizeWord(word: String): String {
        return word.replace(Regex("[^\\p{L}\\p{Nd}]"), "").lowercase()
    }

    /**
     * Calcula la similitud entre dos palabras usando la Distancia de Levenshtein.
     * Retorna un valor entre 0.0 (totalmente distintas) y 1.0 (idénticas).
     */
    private fun calculateWordSimilarity(w1: String, w2: String): Double {
        if (w1 == w2) return 1.0
        if (w1.isEmpty() || w2.isEmpty()) return 0.0

        val distance = levenshteinDistance(w1, w2)
        val maxLength = max(w1.length, w2.length)
        return 1.0 - (distance.toDouble() / maxLength.toDouble())
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }
}
