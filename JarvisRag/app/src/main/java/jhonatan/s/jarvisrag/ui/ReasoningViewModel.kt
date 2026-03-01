// app/src/main/java/jhonatan/s/jarvisrag/ui/ReasoningViewModel.kt
package jhonatan.s.jarvisrag.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jhonatan.s.jarvisrag.service.JarvisCoreService
import jhonatan.s.jarvisrag.service.VoiceEngineConnector
import jhonatan.s.rag_engine.Confidence
import jhonatan.s.rag_engine.JarvisRagEngine
import jhonatan.s.rag_engine.RagResponse
import jhonatan.s.slm_engine.SlmEngineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

// Importaciones de los nuevos módulos de Red y Orquestación (Asegúrate de haberlos creado)
import jhonatan.s.jarvisrag.ai.MistralPromptOrchestrator
import jhonatan.s.jarvisrag.network.MistralApiClient
import jhonatan.s.jarvisrag.network.ElevenLabsClient

data class RemoteSpeakerProfile(
    val name: String,
    val enrollmentCount: Int,
    val vector: FloatArray
)

data class TranscriptionUI(
    val speaker: String,
    val text: String,
    val timestamp: Long
)

sealed class ReasoningState {
    object Idle : ReasoningState()
    data class LoadingEngine(val message: String) : ReasoningState()
    data class Thinking(val operation: String) : ReasoningState()
    data class Generating(val currentText: String) : ReasoningState()
    data class Error(val reason: String) : ReasoningState()
}

class ReasoningViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<ReasoningState>(ReasoningState.Idle)
    val uiState: StateFlow<ReasoningState> = _uiState.asStateFlow()

    private val _telemetryLogs = MutableStateFlow<List<String>>(emptyList())
    val telemetryLogs: StateFlow<List<String>> = _telemetryLogs.asStateFlow()

    private var displayChatLog = ""

    @Volatile
    private var isSlmLoaded = false

    private var _voiceConnector: VoiceEngineConnector? = null

    private val _remoteProfiles = MutableStateFlow<List<RemoteSpeakerProfile>>(emptyList())
    val remoteProfiles: StateFlow<List<RemoteSpeakerProfile>> = _remoteProfiles.asStateFlow()

    private val _recordingProgress = MutableStateFlow(0f)
    val recordingProgress: StateFlow<Float> = _recordingProgress.asStateFlow()

    private val _currentTimer = MutableStateFlow("")
    val currentTimer: StateFlow<String> = _currentTimer.asStateFlow()

    private val _liveTranscriptions = MutableStateFlow<List<TranscriptionUI>>(emptyList())
    val liveTranscriptions: StateFlow<List<TranscriptionUI>> = _liveTranscriptions.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _verificationAlert = MutableStateFlow<String?>(null)
    val verificationAlert: StateFlow<String?> = _verificationAlert.asStateFlow()

    private val _jsonlContent = MutableStateFlow<String?>(null)
    val jsonlContent: StateFlow<String?> = _jsonlContent.asStateFlow()

    // ============================================================================
    // NUEVOS ESTADOS: HIBRIDACIÓN NUBE-BORDE Y SÍNTESIS VOCAL
    // ============================================================================
    private val _isOnlineMode = MutableStateFlow(false) // False = SLM Local, True = API Mistral
    val isOnlineMode: StateFlow<Boolean> = _isOnlineMode.asStateFlow()

    private val _isVoiceEnabled = MutableStateFlow(false) // Apagado por defecto
    val isVoiceEnabled: StateFlow<Boolean> = _isVoiceEnabled.asStateFlow()

    init {
        _telemetryLogs.value = listOf(
            "I JARVIS_SYS: Operando en modo STATELESS.",
            "I JARVIS_SYS: Motor SLM y RAG listos para enlace."
        )
    }

    fun toggleOnlineMode(online: Boolean) {
        _isOnlineMode.value = online
        val mode = if (online) "MISTRAL API (Cloud)" else "MINISTRAL LOCAL (Edge)"
        appendLog("🔄 Cambio de enrutamiento cognitivo: $mode")
    }

    fun toggleVoice(enabled: Boolean) {
        _isVoiceEnabled.value = enabled
        val state = if (enabled) "ACTIVADA" else "DESACTIVADA"
        appendLog("🔊 Síntesis vocal de ElevenLabs: $state")
    }

    fun bootCognitiveEngine(modelPath: String, context: Context) {
        if (isSlmLoaded) return

        if (_voiceConnector == null) {
            _voiceConnector = VoiceEngineConnector(
                context = context,
                onLog = { msg -> appendLog(msg) },
                onTranscriptionReceived = { speaker, text, timestamp ->
                    appendLog("🗣️ [$speaker]: $text")

                    viewModelScope.launch(Dispatchers.Main) {
                        val currentList = _liveTranscriptions.value.toMutableList()
                        val existingIndex = currentList.indexOfFirst { it.timestamp == timestamp }

                        if (existingIndex != -1) {
                            currentList[existingIndex] = TranscriptionUI(speaker, text, timestamp)
                        } else {
                            currentList.add(TranscriptionUI(speaker, text, timestamp))
                        }

                        currentList.sortBy { it.timestamp }
                        if (currentList.size > 50) currentList.removeAt(0)
                        _liveTranscriptions.value = currentList
                    }
                },
                onTranscriptionSealedReceived = { speaker, text, startTime, endTime ->
                    appendSealedMemoryToJsonl(context, speaker, text, startTime, endTime)
                },
                onProgress = { current, total ->
                    if (current < 0) {
                        _recordingProgress.value = 0f
                        _currentTimer.value = ""
                    } else {
                        _recordingProgress.value = if (total > 0) current.toFloat() / total.toFloat() else 0f
                        val remaining = total - current
                        _currentTimer.value = "⏳ ${remaining}s"
                    }
                },
                onProfilesReceived = { json ->
                    parseAndSetProfiles(json)
                }
            )

            _voiceConnector?.connectToEars()
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = ReasoningState.LoadingEngine("Verificando integridad del núcleo...")
            val file = File(modelPath)
            if (!file.exists() || file.length() == 0L) {
                _uiState.value = ReasoningState.Error("Archivo no encontrado: $modelPath")
                return@launch
            }

            try {
                val serviceIntent = Intent(context, JarvisCoreService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("JARVIS_SLM", "Fallo al iniciar Servicio: ${e.message}")
            }

            _uiState.value = ReasoningState.LoadingEngine("Cargando pesos en VRAM...")
            val success = SlmEngineManager.loadModel(modelPath)

            if (success) {
                isSlmLoaded = true
                _uiState.value = ReasoningState.Idle
                appendLog("✅ Motor SLM Local en línea.")
            } else {
                _uiState.value = ReasoningState.Error("Fallo crítico en C++.")
            }
        }
    }

    private fun appendSealedMemoryToJsonl(context: Context, speaker: String, text: String, startTime: Long, endTime: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, "transcriptions.jsonl")
                val json = JSONObject().apply {
                    put("speaker", speaker)
                    put("start_time", startTime)
                    put("end_time", endTime)
                    put("text", text)
                }

                val writer = FileWriter(file, true)
                writer.append(json.toString() + "\n")
                writer.flush()
                writer.close()

                appendLog("💾 Memoria guardada: $speaker")

                val result = JarvisRagEngine.syncLiveMemory(file.absolutePath)

                result.onSuccess {
                    appendLog("🧠 Grafo Vectorial actualizado automáticamente.")
                    loadJsonlHistory(context)
                }.onFailure { error ->
                    appendLog("⚠️ Fallo al auto-inyectar en CozoDB: ${error.message}")
                }

            } catch (e: Exception) {
                Log.e("JARVIS_VM", "Error escribiendo memoria sellada", e)
                appendLog("❌ Error de I/O al guardar memoria.")
            }
        }
    }

    fun toggleEars(active: Boolean) {
        _isListening.value = active
        if (active) {
            _voiceConnector?.startListening()
        } else {
            _voiceConnector?.stopListening()
        }
    }

    fun switchVoiceModel(modelName: String) {
        _voiceConnector?.loadModel(modelName)
    }

    fun enrollRemoteProfile(name: String) {
        _voiceConnector?.enrollSpeaker(name)
    }

    fun verifyRemoteSpeaker() {
        _voiceConnector?.verifySpeaker()
    }

    fun deleteRemoteProfile(name: String) {
        _voiceConnector?.deleteProfile(name)
    }

    private fun parseAndSetProfiles(json: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val profilesList = mutableListOf<RemoteSpeakerProfile>()
                val jsonArray = JSONArray(json)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.getString("name")
                    val count = obj.getInt("count")
                    val vectorJson = obj.getJSONArray("vector")

                    val vector = FloatArray(vectorJson.length())
                    for (j in 0 until vectorJson.length()) {
                        vector[j] = vectorJson.getDouble(j).toFloat()
                    }

                    profilesList.add(RemoteSpeakerProfile(name, count, vector))
                }

                withContext(Dispatchers.Main) {
                    _remoteProfiles.value = profilesList
                    appendLog("🧬 Base de datos biométrica sincronizada (${profilesList.size} perfiles).")
                }
            } catch (e: Exception) {
                Log.e("JARVIS_VM", "Error parseando perfiles: ${e.message}")
            }
        }
    }

    fun loadJsonlHistory(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(context.filesDir, "transcriptions.jsonl")
            val content = if (file.exists()) file.readText() else "El archivo JSONL está vacío o no existe aún."
            _jsonlContent.value = content
        }
    }

    fun saveJsonlHistory(context: Context, newContent: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(context.filesDir, "transcriptions.jsonl")
            file.writeText(newContent)
            _jsonlContent.value = null
            appendLog("🗄️ Memoria JSONL reescrita manualmente.")
        }
    }

    fun closeJsonlEditor() {
        _jsonlContent.value = null
    }

    fun clearVerificationAlert() {
        _verificationAlert.value = null
    }

    // ============================================================================
    // NÚCLEO DE RAZONAMIENTO (RAG + ENRUTAMIENTO DUAL + ELEVENLABS)
    // ============================================================================
    fun processQuery(userQuery: String, context: Context) {
        if (!_isOnlineMode.value && !isSlmLoaded) {
            _uiState.value = ReasoningState.Error("El motor SLM local no está cargado. Activa el modo Online o espera a que cargue.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                displayChatLog += "\n>> USUARIO:\n$userQuery\n\n>> JARVIS:\n"
                _uiState.value = ReasoningState.Generating(displayChatLog)

                // 1. Búsqueda RAG
                val ragResult = JarvisRagEngine.searchContext(userQuery, 15)
                val ragResponse = ragResult.getOrElse {
                    RagResponse(status = "error", confidenceLevel = Confidence.NULA, results = emptyList())
                }

                var assistantResponse = ""

                // 2. ENRUTAMIENTO DINÁMICO
                val tokenFlow = if (_isOnlineMode.value) {
                    appendLog("🌐 Ruteando a Mistral API (Cloud)...")
                    // Usamos el orquestador de Mistral
                    val prompt = MistralPromptOrchestrator.buildPrompt(userQuery, ragResponse)
                    MistralApiClient.generateStream(prompt)
                } else {
                    appendLog("🧠 Ruteando a Mistral 7B Local (Edge)...")

                    // 🚀 CAMBIO CLAVE: AHORA USAMOS EL FORMATO MISTRAL TAMBIÉN EN LOCAL
                    // Antes usábamos GemmaPromptOrchestrator (ChatML), ahora unificamos a [INST]
                    val prompt = MistralPromptOrchestrator.buildPrompt(userQuery, ragResponse)

                    SlmEngineManager.generateResponseStateful(prompt)
                }

                // 3. Recolección de Tokens Reactiva
                tokenFlow.collect { token ->
                    // Limpieza de basura específica de Mistral
                    val cleanToken = token
                        .replace("</s>", "")       // Fin de secuencia Mistral
                        .replace("[/INST]", "")    // Cierre de instrucción
                        .replace(" ", " ")         // A veces el tokenizador de llama.cpp escupe este caracter

                    assistantResponse += cleanToken
                    _uiState.value = ReasoningState.Generating(displayChatLog + assistantResponse)
                }

                displayChatLog += assistantResponse + "\n"
                _uiState.value = ReasoningState.Generating(displayChatLog)

                // 4. Síntesis Vocal (ElevenLabs)
                if (_isVoiceEnabled.value) {
                    if (assistantResponse.isNotBlank()) {
                        appendLog("🔊 Sintetizando voz con ElevenLabs...")
                        ElevenLabsClient.speak(assistantResponse, context.cacheDir)
                    } else {
                        appendLog("⚠️ Respuesta vacía, omitiendo síntesis de voz.")
                    }
                }

            } catch (e: Exception) {
                _uiState.value = ReasoningState.Error("Excepción en inferencia: ${e.message}")
                appendLog("❌ ERROR INFERENCIA: ${e.message}")
                Log.e("JARVIS_CORE", "Error en processQuery", e)
            }
        }
    }

    fun clearMemoryManual() {
        displayChatLog = ""
        _uiState.value = ReasoningState.Idle
        viewModelScope.launch(Dispatchers.IO) { SlmEngineManager.resetMemory() }
        appendLog("🧹 Memoria KV purgada.")
    }

    private fun appendLog(msg: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val currentLogs = _telemetryLogs.value.toMutableList()
            currentLogs.add(msg)
            if (currentLogs.size > 100) currentLogs.removeAt(0)
            _telemetryLogs.value = currentLogs

            if (msg.contains("VERIFICATION_RESULT")) {
                val cleanMsg = msg.substringAfter("VERIFICATION_RESULT - ").trim()
                val parts = cleanMsg.split("|")
                if (parts.size == 2) {
                    val name = parts[0]
                    val score = (parts[1].toFloat() * 100).toInt()
                    _verificationAlert.value = "✅ MATCH CONFIRMADO\nLocutor: $name\nCerteza: $score%"
                }
            } else if (msg.contains("ERROR")) {
                val cleanMsg = msg.substringAfter("ERROR - ").trim()
                _verificationAlert.value = "⚠️ ALERTA DEL MOTOR\n$cleanMsg"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _voiceConnector?.disconnect()
    }
}
