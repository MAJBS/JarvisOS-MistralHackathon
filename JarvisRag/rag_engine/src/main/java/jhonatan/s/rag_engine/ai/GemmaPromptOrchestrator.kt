// rag_engine/src/main/java/jhonatan/s/rag_engine/ai/GemmaPromptOrchestrator.kt
package jhonatan.s.rag_engine.ai

import jhonatan.s.rag_engine.Confidence
import jhonatan.s.rag_engine.RagResponse

object GemmaPromptOrchestrator {

    fun buildDeltaPrompt(
        userQuery: String,
        ragResponse: RagResponse
    ): String {
        val builder = StringBuilder()

        // 1. SYSTEM: Instrucción de parada implícita
        builder.append("<|im_start|>system\n")
        builder.append("Eres Jarvis. Tu única tarea es redactar un REPORTE TÉCNICO BREVE basado en la memoria. No repitas secciones. Finaliza al terminar el reporte.\n")
        builder.append("<|im_end|>\n")

        // 2. USER
        builder.append("<|im_start|>user\n")

        if (ragResponse.confidenceLevel != Confidence.NULA && ragResponse.results.isNotEmpty()) {
            builder.append("DATOS DE MEMORIA:\n")
            val contextString = ragResponse.results
                .distinctBy { it.chunkId }
                .joinToString("\n") { node -> "[${node.speaker}]: ${node.text}" }
            builder.append(contextString)

            builder.append("\n\nPregunta: ${userQuery}\n")
            builder.append("Instrucción: Genera un solo párrafo técnico y para.\n")
        } else {
            builder.append("No hay datos. Informa la ausencia de registros para: ${userQuery}\n")
        }
        builder.append("<|im_end|>\n")

        // 3. ASSISTANT: Pre-filling de reporte
        builder.append("<|im_start|>assistant\n")
        builder.append("REPORTE TÉCNICO:")

        return builder.toString()
    }
}
