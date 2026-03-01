package jhonatan.s.rag_engine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class RagViewModel : ViewModel() {

    // Estado que recibe lo que el usuario/IA tipea
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Estado que emite los resultados JSON desde Rust
    private val _ragResults = MutableStateFlow("Motor RAG en espera...")
    val ragResults: StateFlow<String> = _ragResults.asStateFlow()

    init {
        setupReactivePipeline()
    }

    /**
     * Único punto de entrada para nuevas consultas.
     */
    fun updateQuery(newQuery: String) {
        _searchQuery.value = newQuery
    }

    /**
     * Pipeline reactivo estricto para proteger el hilo de ONNX.
     */
    private fun setupReactivePipeline() {
        viewModelScope.launch(Dispatchers.IO) {
            _searchQuery
                .debounce(300) // Regla: 300ms de pausa obligatoria
                .distinctUntilChanged() // Regla: Ignorar si el texto no cambió
                .filter { it.isNotBlank() && it.length > 2 } // Regla: Mínimo 3 caracteres
                .mapLatest { query ->
                    // mapLatest cancela la ejecución anterior si llega una nueva query
                    // Aquí cruzamos la frontera JNI hacia Rust
                    try {
                        NativeBridge.queryGraphRag(query)
                    } catch (e: UnsatisfiedLinkError) {
                        // Fallback temporal mientras no tengamos el .so de Rust compilado
                        "{\"error\": \"Librería nativa no cargada aún. Query: $query\"}"
                    }
                }
                .collect { jsonResult ->
                    _ragResults.value = jsonResult
                }
        }
    }
}