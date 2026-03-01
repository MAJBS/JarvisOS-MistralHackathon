// app/src/main/java/jhonatan/s/jarvisrag/network/ApiClients.kt
package jhonatan.s.jarvisrag.network

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object MistralApiClient {
    private const val TAG = "JARVIS_MISTRAL"

    // 🔑 LLAVE OFICIAL DE MISTRAL HACKATHON
    private const val API_KEY = "YOUR_KEY"

    // Endpoint oficial de Mistral AI
    private const val API_URL = "https://api.mistral.ai/v1/chat/completions"

    // 🚀 MODELO LIGERO RECOMENDADO: Ministral 8B (Optimizado para RAG y baja latencia)
    private const val MODEL_NAME = "ministral-8b-latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun generateStream(prompt: String): Flow<String> = callbackFlow {
        Log.i(TAG, "Iniciando conexión de alta velocidad con Mistral AI ($MODEL_NAME)...")

        val jsonBody = JSONObject().apply {
            put("model", MODEL_NAME)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("stream", true)
            put("temperature", 0.3) // Temperatura baja (0.3) para respuestas técnicas y precisas (RAG)
            put("max_tokens", 1024)
        }

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val factory = EventSources.createFactory(client)
        val eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "✅ Conexión SSE Abierta con Mistral. Código HTTP: ${response.code}")
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "❌ Error HTTP en Mistral: $errorBody")
                    close(Exception("Error HTTP ${response.code}: $errorBody"))
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    Log.i(TAG, "🛑 Stream finalizado por Mistral ([DONE]).")
                    close()
                    return
                }
                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                        if (delta != null) {
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                trySend(content)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignoramos errores de parseo menores (keep-alive pings)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorBody = response?.body?.string()
                Log.e(TAG, "❌ Fallo crítico de red. HTTP: ${response?.code} | Body: $errorBody")
                close(t ?: Exception("Fallo en API Mistral Cloud"))
            }

            override fun onClosed(eventSource: EventSource) {
                Log.i(TAG, "🔌 Conexión SSE Cerrada.")
            }
        })

        awaitClose { eventSource.cancel() }
    }
}

object ElevenLabsClient {
    private const val TAG = "JARVIS_VOICE"
    private const val API_KEY = "YOUR_KEY2"
    private const val VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Rachel

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null

    // 🛑 Función para callar a Jarvis
    fun stop() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                Log.i(TAG, "🛑 Audio detenido por el usuario.")
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo audio: ${e.message}")
        }
    }

    suspend fun speak(text: String, cacheDir: File) = withContext(Dispatchers.IO) {
        // 1. Generar un Hash único para este texto (MD5 simplificado)
        val textHash = text.hashCode().toString()
        val fileName = "jarvis_$textHash.mp3"
        val cachedFile = File(cacheDir, fileName)

        // 2. CACHÉ INTELIGENTE: Si ya existe, lo reproducimos y AHORRAMOS DINERO
        if (cachedFile.exists() && cachedFile.length() > 0) {
            Log.i(TAG, "♻️ Audio encontrado en caché. Ahorrando créditos API.")
            withContext(Dispatchers.Main) { playAudio(cachedFile.absolutePath) }
            return@withContext
        }

        Log.i(TAG, "💸 Generando nuevo audio con ElevenLabs (${text.length} chars)...")

        val jsonBody = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_multilingual_v2")
        }

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$VOICE_ID")
            .addHeader("xi-api-key", API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val audioBytes = response.body?.bytes()
                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    // Guardamos en caché con el nombre hasheado
                    val fos = FileOutputStream(cachedFile)
                    fos.write(audioBytes)
                    fos.flush()
                    fos.close()

                    withContext(Dispatchers.Main) { playAudio(cachedFile.absolutePath) }
                }
            } else {
                Log.e(TAG, "❌ Error ElevenLabs: ${response.code}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "❌ Error Red: ${e.message}")
        }
    }

    private fun playAudio(filePath: String) {
        stop() // Detener cualquier audio anterior
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reproducción: ${e.message}")
        }
    }
}