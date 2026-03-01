// rag_engine/src/main/java/jhonatan/s/rag_engine/RagViewModel.kt
package jhonatan.s.rag_engine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileOutputStream

@OptIn(FlowPreview::class)
class RagViewModel : ViewModel() {

    // ============================================================================
    // ESTADOS REACTIVOS (StateFlows)
    // ============================================================================

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Para el panel táctico del desarrollador (texto crudo/logs)
    private val _ragResults = MutableStateFlow("Motor RAG en espera...")
    val ragResults: StateFlow<String> = _ragResults.asStateFlow()

    // Para la Interfaz Visual del Asistente (Objetos fuertemente tipados)
    private val _structuredResponse = MutableStateFlow<RagResponse?>(null)
    val structuredResponse: StateFlow<RagResponse?> = _structuredResponse.asStateFlow()

    // Panel de monitorización del sistema
    private val _systemStatus = MutableStateFlow("Esperando comandos...")
    val systemStatus: StateFlow<String> = _systemStatus.asStateFlow()

    // 🚀 NUEVO: Estado para el Editor JSONL Directo
    private val _rawJsonlEditor = MutableStateFlow("")
    val rawJsonlEditor: StateFlow<String> = _rawJsonlEditor.asStateFlow()

    init {
        setupReactivePipeline()
        checkSystemHealth()
    }

    // ============================================================================
    // INTENCIONES DE USUARIO (Acciones de UI)
    // ============================================================================

    fun updateQuery(newQuery: String) {
        _searchQuery.value = newQuery
    }

    // 🚀 NUEVO: Actualiza el texto del editor mientras escribes
    fun updateJsonlEditor(text: String) {
        _rawJsonlEditor.value = text
    }

    // 🚀 NUEVO: Carga el archivo interno al editor
    fun loadInternalJsonl(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(context.filesDir, "transcriptions.jsonl")
            if (file.exists()) {
                _rawJsonlEditor.value = file.readText()
                _systemStatus.value = "✅ Memoria episódica cargada en el editor."
            } else {
                _rawJsonlEditor.value = ""
                _systemStatus.value = "⚠️ El archivo transcriptions.jsonl está vacío o no existe."
            }
        }
    }

    fun ingestEditedJsonl(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _systemStatus.value = "🚀 Iniciando Purga Quirúrgica y Reconstrucción..."
            try {
                val file = File(context.filesDir, "transcriptions.jsonl")
                file.writeText(_rawJsonlEditor.value)

                // Usamos la fachada segura
                val result = JarvisRagEngine.syncLiveMemory(file.absolutePath)

                result.onSuccess { metrics ->
                    _systemStatus.value = "✅ Grafo Sincronizado: ${metrics.chunksCreated} chunks reconstruidos en ${metrics.executionTimeMs}ms."
                    checkSystemHealth()
                }.onFailure { error ->
                    _systemStatus.value = "❌ Error en Rust Sync: ${error.message}"
                }
            } catch (e: Exception) {
                _systemStatus.value = "❌ Error crítico en Sync: ${e.message}"
                Log.e("RagViewModel", "Fallo en sincronización directa", e)
            }
        }
    }

    // 🚀 Botón de Pánico (Borrado Total de Memoria Viva)
    fun clearAllJsonlMemory(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _systemStatus.value = "🚀 Purgando toda la memoria episódica..."
            try {
                val file = File(context.filesDir, "transcriptions.jsonl")
                if (file.exists()) {
                    file.writeText("") // Vaciamos el archivo físico
                }
                _rawJsonlEditor.value = ""

                // Usamos la fachada segura
                val result = JarvisRagEngine.syncLiveMemory(file.absolutePath)

                result.onSuccess {
                    _systemStatus.value = "✅ Memoria borrada por completo. Grafo reiniciado."
                    checkSystemHealth()
                }.onFailure { error ->
                    _systemStatus.value = "❌ Error en Rust al purgar: ${error.message}"
                }
            } catch (e: Exception) {
                _systemStatus.value = "❌ Error crítico al borrar memoria: ${e.message}"
            }
        }
    }

    fun ingestFile(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _systemStatus.value = "🚀 Iniciando transferencia binaria Zero-Copy..."

            val result = JarvisRagEngine.ingestDocument(context, uri)

            result.onSuccess { metrics ->
                val msg = "✅ Ingesta OK: ${metrics.chunksCreated} chunks procesados en ${metrics.executionTimeMs}ms."
                _systemStatus.value = msg
                Log.i("RagViewModel", msg)
                checkSystemHealth() // Refrescar los contadores de la base de datos tras la inserción
            }.onFailure { error ->
                val errorMsg = "❌ Error crítico en ingesta: ${error.message}"
                _systemStatus.value = errorMsg
                Log.e("RagViewModel", errorMsg, error)
            }
        }
    }

    fun setRule(key: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (key.isBlank() || value.isBlank()) {
                _systemStatus.value = "⚠️ La clave y el valor no pueden estar vacíos."
                return@launch
            }

            val result = JarvisRagEngine.setSystemRule(key, value)
            if (result.isSuccess) {
                _systemStatus.value = "✅ Regla '$key' guardada correctamente en KV Store."
                checkSystemHealth()
            } else {
                _systemStatus.value = "❌ Error al guardar la regla en la base de datos."
            }
        }
    }

    fun executeRawDatalog(rawQuery: String) {
        _ragResults.value = "🔮 Consultando al Oráculo Datalog..."
        viewModelScope.launch(Dispatchers.IO) {
            val result = JarvisRagEngine.runOracleQuery(rawQuery)

            result.onSuccess { jsonResponse ->
                _ragResults.value = "--- RESPUESTA DEL ORÁCULO ---\n$jsonResponse"
            }.onFailure { error ->
                _ragResults.value = "❌ Error Datalog Engine: ${error.message}"
            }
        }
    }

    // ============================================================================
    // METACOGNICIÓN Y MONITOREO DEL SISTEMA
    // ============================================================================

    fun checkSystemHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Obtenemos el diagnóstico crudo desde Rust (JNI)
                val jsonStr = NativeBridge.getSystemDiagnostics()

                // Parseo directo a Data Class usando el Parser centralizado de JarvisRagEngine
                val diagnostics = JarvisRagEngine.jarvisJsonParser.decodeFromString<SystemDiagnostics>(jsonStr)

                if (diagnostics.error != null) {
                    _systemStatus.value = "⚠️ Diagnóstico fallido: ${diagnostics.error}"
                    return@launch
                }

                val onnx = if (diagnostics.onnxLoaded) "🟢" else "🔴"
                val db = if (diagnostics.dbConnected) "🟢" else "🔴"

                val sb = StringBuilder()
                sb.append("ESTADO DEL SISTEMA:\nONNX: $onnx | DB: $db\nDATOS EN MEMORIA:\n")

                diagnostics.counts.forEach { (table, count) ->
                    sb.append(" • $table: $count registros\n")
                }

                _systemStatus.value = sb.toString()

            } catch (e: Exception) {
                Log.e("RagViewModel", "Error ejecutando health check", e)
                _systemStatus.value = "⚠️ Error de parseo en diagnóstico: ${e.message}"
            }
        }
    }

    // ============================================================================
    // PIPELINE REACTIVO ESTRICTO (El Corazón del Buscador)
    // ============================================================================

    private fun setupReactivePipeline() {
        viewModelScope.launch(Dispatchers.IO) {
            _searchQuery
                .debounce(300) // Regla de fuego: Retraso de 300ms para no asfixiar a ONNX en inferencias letra por letra
                .distinctUntilChanged()
                .filter { it.isNotBlank() && it.length > 2 }
                .mapLatest { query ->
                    // Interfaz táctica entra en estado de carga visual
                    _ragResults.value = "🔍 Calculando similitud del coseno en hiperespacio..."
                    _structuredResponse.value = null

                    // Disparo al motor nativo (Capa JNI -> Rust)
                    val result = JarvisRagEngine.searchContext(query, maxResults = 5)

                    result.getOrNull()?.let { response ->
                        // ÉXITO: Actualizamos la UI estructurada
                        _structuredResponse.value = response

                        // Formateamos la salida textual para el Dashboard de Desarrollo
                        if (response.results.isEmpty()) {
                            "⚠️ No se encontró contexto relevante en el grafo."
                        } else {
                            val topResults = response.results.joinToString("\n\n") { node ->
                                "🗣️ [${node.speaker}] (Dist: ${String.format("%.4f", node.distance)})\n\"${node.text}\"\nID: ${node.chunkId}"
                            }
                            "📊 ESTADO: ${response.status.uppercase()} | CONFIANZA: ${response.confidenceLevel}\n📚 NODOS RECUPERADOS: ${response.results.size}\n\n$topResults"
                        }
                    } ?: run {
                        // FALLO: Intercepción de error crítico (C++ o Datalog) y protección del hilo principal
                        val errorMsg = result.exceptionOrNull()?.message ?: "Error matemático nativo"

                        // Fabricamos un nodo falso ("Dummy") para que la UI mapee y muestre el error elegantemente
                        val errorNode = RagResultNode(
                            chunkId = "00000000",
                            speaker = "SISTEMA (KERNEL)",
                            text = errorMsg,
                            distance = 0f
                        )

                        _structuredResponse.value = RagResponse(
                            status = "fatal",
                            error = errorMsg,
                            confidenceLevel = Confidence.NULA,
                            results = listOf(errorNode)
                        )

                        "❌ Colapso de inferencia detectado: $errorMsg"
                    }
                }
                .collect { formattedResult ->
                    // Emisión final asincrónica que será consumida reactivamente por Jetpack Compose
                    _ragResults.value = formattedResult
                }
        }
    }
}
