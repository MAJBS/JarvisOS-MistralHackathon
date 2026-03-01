# 🧠 JARVIS RAG ENGINE - MANUAL TÉCNICO Y DIRECTIVAS PARA LA IA MAESTRA

**ESTADO DE CLASIFICACIÓN:** GRADO MILITAR (PRODUCCIÓN)
**SUBSISTEMA:** NODO COGNITIVO HEADLESS (CAPA 4)
**ARQUITECTURA SUBYACENTE:** KOTLIN JNI ➔ RUST ➔ COZODB (DATALOG) ➔ ONNX (INT8)

---

## 1. DECLARACIÓN DE PROPÓSITO
El módulo `rag_engine` (JarvisRag) es el hipocampo de memoria determinista para la IA Maestra. No es un simple buscador de similitud semántica. Es un motor de razonamiento de grafos (GraphRAG) que opera estrictamente fuera del recolector de basura (Off-Heap) mediante un pipeline *Zero-Copy*. Su objetivo es proveer contexto adyacente validado matemáticamente a la IA Maestra, bloqueando cualquier intento de alucinación mediante un evaluador de confianza integrado (CRAG).

---

## 2. ARQUITECTURA DE INGESTA Y MEMORIA (ZERO-COPY)
La IA Maestra debe comprender que los archivos masivos nunca se cargan en la RAM activa de la JVM.
* **Mapeo de Memoria (`mmap`):** Los archivos (JSONL, TXT) se leen directamente desde el caché del Kernel del sistema operativo (Capa C/C++).
* **Proveniencia Criptográfica:** El identificador (`chunk_id`) de cada fragmento de memoria NO es aleatorio. Es un hash **SHA-256** generado a partir del `texto + speaker + timestamp`. Esto garantiza trazabilidad forense absoluta y evita duplicados lógicos en la base de datos.
* **Transacciones ACID:** Cada nuevo recuerdo se guarda de forma indivisible. El texto, el vector dimensional (768 dimensiones) y la arista relacional (`chunk_edges`) se insertan en CozoDB en una única transacción Datalog. Si la batería falla, la transacción se aborta, manteniendo la integridad del índice HNSW intacta.

---

## 3. RECUPERACIÓN MULTISALTO (GRAPHRAG Y DATALOG)
Cuando la IA Maestra solicita contexto, el motor no devuelve fragmentos aislados. Ejecuta un razonamiento topológico:
1.  **Semilla Semántica:** El motor ONNX vectoriza la pregunta y busca en el índice `HNSW` de CozoDB la distancia del coseno más cercana.
2.  **Travesía de Grafo:** Utilizando álgebra relacional (Datalog), el motor navega por la tabla `chunk_edges` para extraer el contexto adyacente (qué se dijo antes y después del fragmento recuperado).
3.  **Ensamblaje Secuencial:** Entrega los nodos ordenados lógicamente a la IA Maestra.

---

## 4. CORTAFUEGOS METACOGNITIVO (CRAG - CORRECTIVE RAG)
La IA Maestra NO debe confiar a ciegas en el vector. El núcleo Rust de JarvisRag evalúa matemáticamente la distancia del coseno y asigna una métrica de `Confidence` estricta:

| Distancia Coseno | Nivel de Confianza | Directiva para la IA Maestra |
| :--- | :--- | :--- |
| `0.00` a `0.50` | **ALTA** | Hit directo. El contexto es altamente relevante y debe usarse imperativamente como fuente de verdad. |
| `0.51` a `0.85` | **MEDIA** | Relevancia parcial. La IA Maestra debe inferir si la relación es útil o descartarla. |
| `> 0.85` | **NULA** | **BLOQUEO.** Alucinación inminente. El vector devuelto es basura matemática (ortogonal o distante). |

*Nota: Si el nivel es `NULA`, el orquestador (`GemmaPromptOrchestrator`) destruirá los resultados y enviará a la IA Maestra un "Super-Prompt" vacío ordenándole que admita no tener información en sus registros locales.*

---

## 5. CONTRATO DE INTERFAZ (API PARA LA IA MAESTRA)

La IA Maestra interactúa con este motor exclusivamente a través del **Servicio Headless** (`JarvisRagHeadlessService`), asegurando que las inferencias sobrevivan a la destrucción de la Interfaz Gráfica de Usuario (UI).

### Entidad de Respuesta Estructurada (`RagResponse`)
El puente JNI serializa la respuesta nativa de Rust en esta estructura inmutable:
```kotlin
data class RagResponse(
    val status: String,                 // "ok" o "fatal"
    val error: String?,                 // Detalle en caso de fallo del Kernel/Rust
    val confidenceLevel: Confidence,    // ALTA, MEDIA, NULA
    val results: List<RagResultNode>    // Nodos topológicos ordenados
)

data class RagResultNode(
    val chunkId: String,                // Hash SHA-256 del nodo
    val speaker: String,                // Autor original de la memoria
    val text: String,                   // Contenido factual
    val distance: Float                 // Distancia HNSW pura (0.0 = Adyacencia de Grafo)
)


Protocolo de Invocación

La IA Mayor solicita la memoria a través de la función requestDeterminativeContext(query: String) del servicio en segundo plano. El servicio orquesta la llamada a C++ y devuelve el XML/Prompt listo.

## 6. FORMATO DE INYECCIÓN (EL SUPER-PROMPT)
Cuando la IA Maestra recibe una respuesta exitosa, el contexto se inyecta utilizando etiquetas estructurales XML-like para garantizar la delimitación de instrucciones. La IA Maestra debe parsear mentalmente este formato:

<system>
Eres la IA Maestra de este dispositivo. Se te ha proporcionado memoria episódica extraída matemáticamente por tu motor JarvisRag...
</system>

<recovered_context>
<memoria id="e3b0c442..." speaker="USUARIO">
Fragmento de código o conversación recuperada.
</memoria>
<memoria id="8a2b4f91..." speaker="DOC">
Párrafo del manual técnico adyacente.
</memoria>
</recovered_context>

<user_query>
[PREGUNTA REAL DEL USUARIO AQUÍ]
</user_query>

## 7. MANDAMIENTOS OPERATIVOS PARA LA IA MAESTRA

Al operar procesando respuestas generadas por este módulo RAG, tú (la IA Maestra) te riges bajo estas directivas inquebrantables:

    Cero Alucinación Factual: Si la directiva del <system> indica que el nivel de confianza es NULA, tienes terminantemente prohibido inventar, deducir o simular conocimiento técnico local. Debes responder: "No poseo este dato en mis registros locales."

    Precedencia de Contexto: Lo contenido dentro de <recovered_context> tiene prioridad jerárquica sobre tus pesos paramétricos preentrenados. Si JarvisRag afirma un hecho local o muestra un código, asume que es la versión más actual.

    Citas Explícitas: Si se te solicita, debes ser capaz de mencionar el speaker o el id de la memoria que utilizaste para forjar tu respuesta.

    Aislamiento de Lógica: No intentes interactuar con las bases de datos de CozoDB directamente. Tu acceso es exclusivamente a través de inferencias de lenguaje natural despachadas al JarvisRagHeadlessService.

