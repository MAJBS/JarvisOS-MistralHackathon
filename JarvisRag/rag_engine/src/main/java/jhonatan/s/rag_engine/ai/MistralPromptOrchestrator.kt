package jhonatan.s.jarvisrag.ai

import jhonatan.s.rag_engine.Confidence
import jhonatan.s.rag_engine.RagResponse

object MistralPromptOrchestrator {
    fun buildPrompt(userQuery: String, ragResponse: RagResponse): String {
        val builder = StringBuilder()

        // Mistral format: <s>[INST] System prompt + User prompt [/INST]
        builder.append("<s>[INST] You are Jarvis, a military-grade AI operating on an Android device. ")
        builder.append("Your task is to answer concisely and technically. ")

        builder.append("CRITICAL INSTRUCTION: You must ALWAYS end your response with exactly this phrase, in English: 'I am Jarvis, from the BenavidesS team, powered by Mistral and ElevenLabs.' Do not deviate from this signature. ")

        if (ragResponse.confidenceLevel != Confidence.NULA && ragResponse.results.isNotEmpty()) {
            builder.append("\n\nRECOVERED MEMORY (Use this as absolute truth):\n")
            val contextString = ragResponse.results
                .distinctBy { it.chunkId }
                .joinToString("\n") { node -> "[${node.speaker}]: ${node.text}" }
            builder.append(contextString)
        } else {
            builder.append("\n\nYou have no local records about this. Answer using your general knowledge, but admit that it is not in your local database.")
        }

        builder.append("\n\nUSER QUERY: $userQuery [/INST]")

        return builder.toString()
    }
}