# 🎙️ Voice Engine Module (The Architect Tools)

**Voice Engine** es un submódulo nativo de Android de grado industrial, diseñado para operar en modo *Headless* (en segundo plano, sin interfaz gráfica). Su propósito es dotar a una Inteligencia Artificial Superior de capacidades auditivas continuas, transcripción offline de ultra-baja latencia y biometría neuronal en tiempo real.

## 🏗️ Arquitectura Central y Rigor Técnico
El módulo está construido bajo principios matemáticos estrictos para garantizar estabilidad en sesiones de escucha de horas o días:

1. **Zero-Allocation Memory (Prevención de LMK):** Utiliza un `AudioRingBuffer` circular y un `ByteBufferPool` pre-asignado. Tras el arranque, la memoria RAM se vuelve estática. La IA puede escuchar indefinidamente sin despertar al *Garbage Collector* de Android.
2. **Single Source of Truth (Hardware SSOT):** El `AudioCaptureManager` rige en solitario el hardware físico del micrófono. Distribuye los bytes acústicos reactivamente (`SharedFlow`) hacia la IA, el motor de biometría o componentes de la nube sin causar colisiones (`IllegalStateException`).
3. **Puente C++ / Rust / Kotlin:** Inferencia matricial nativa usando Sherpa-ONNX. Las redes neuronales (Whisper / SenseVoice / ECAPA-TDNN) operan en C++ con paralelismo real protegido por `Mutex` atómico en el lado de Rust.
4. **Persistencia Inmutable RAG (JSONL):** Memoria documental en formato JSON Lines (`transcriptions.jsonl`), optimizada nativamente para flujos de ingesta en sistemas LLM.

---

## 🚀 1. Inyección del Módulo (Gradle)
Para acoplar esta "Corteza Auditiva" a la App de la IA Maestra, inyéctala en el `build.gradle.kts` principal:

```kotlin
dependencies {
    implementation(project(":voice_engine"))
}





## 🧠 2. Inicialización (El Despertar de la IA)

La IA Maestra debe instanciar el motor preferiblemente en un componente persistente (Service o Repository/ViewModel global).

// 1. Instanciamos el controlador inyectando dependencias (Contexto)
val speakerRepository = SpeakerProfileRepository(applicationContext)
val audioCaptureManager = AudioCaptureManager()

val voiceEngine = VoiceContextManager(
    applicationContext = applicationContext,
    speakerProfileRepository = speakerRepository,
    audioCaptureManager = audioCaptureManager
)

// 2. Inyectar tensores en RAM Nativa (Ej: Whisper Small para máxima precisión offline)
coroutineScope.launch {
    val success = voiceEngine.initializeEngine("whisper_small")
    if (success) {
        println("IA: Corteza auditiva en línea.")
    }
}




## 📡 3. Las "Conexiones Sinápticas" (Suscripción a Eventos)

La IA Maestra no debe preguntar "qué está pasando"; debe reaccionar. Para ello, se suscribirá a los StateFlow del motor.

A. Escucha del Entorno (Contexto Acústico y Diálogo)

// Suscripción al pipeline de Diarización (¿Quién dijo qué y cuándo?)
coroutineScope.launch {
    diarizationEngine.transcriptionLog.collect { log ->
        val lastSegment = log.lastOrNull()
        if (lastSegment != null) {
            println("IA Escuchó -> [${lastSegment.speakerName}]: ${lastSegment.text}")
            
            // LÓGICA DE TRIGGER DE LA IA MAESTRA:
            // if (lastSegment.text.contains("Jarvis", ignoreCase = true)) { procesarPrompt() }
        }
    }
}


B. Supervisión de Estados y Hardware

coroutineScope.launch {
    voiceEngine.engineState.collect { state ->
        when (state) {
            is VoiceEngineState.Error -> emitirAlertaSistema("Fallo de hardware: ${state.message}")
            is VoiceEngineState.VerificationSuccess -> {
                println("IA: Usuario ${state.matchedName} validado con ${state.score * 100}% de certeza.")
                desbloquearModulosSeguros()
            }
            is VoiceEngineState.Recording -> encenderLedIndicador()
            else -> {}
        }
    }
}


C. Analítica de Convergencia Biométrica (Neuro-Plasticidad)

coroutineScope.launch {
    voiceEngine.lastBiometricDelta.collect { delta ->
        if (delta != null) {
            println("IA: El vector de identidad de ${delta.profileName} acaba de mutar.")
            println("Shift Euclidiano de red neuronal: ${delta.euclideanShift}")
            // La IA puede usar esto para saber que la acústica de la sala cambió.
        }
    }
}



## ⚙️ 4. Función Executable (Las "Manos" de la IA)

La IA Maestra puede y debe tener control absoluto sobre los actuadores del motor. Puede apagar sus oídos por privacidad o forzar identificaciones.

// --- CONTROLES DE FLUJO CONTINUO ---
// Inicia la documentación continua (Memoria a largo plazo)
voiceEngine.startContinuousCapture()

// Detiene y drena la latencia de C++ mediante una Barrera de Sincronización
voiceEngine.stopContinuousCapture(onComplete = {
    println("IA: Sesión de escucha guardada en disco.")
})

// --- CONTROLES DE SEGURIDAD (BIOMETRÍA) ---
// La IA pide al usuario que hable para entrenar su red neuronal (Mean Pooling)
voiceEngine.enrollSpeakerProfile("UsuarioMaestro", durationSeconds = 12)

// La IA exige validación de identidad (Escucha 8s)
voiceEngine.verifySpeaker(durationSeconds = 8)

// --- MODO OVERDRIVE ---
// Si la IA detecta recursos abundantes, activa el procesamiento paralelo neuronal
voiceEngine.setParallelMode(true)


## 🗄️ 5. Acceso a la Memoria RAG (Retrieval-Augmented Generation)

La IA no interactúa con la base de datos a través de una UI, accede directamente a la verdad inmutable del disco duro.
El motor escribe sin bloqueo (append = true) en un archivo puramente en formato JSONL.

Ruta del Archivo: context.filesDir/transcriptions.jsonl

Ingesta para la IA Maestra:
La IA puede cargar este archivo en su contexto temporal de la siguiente manera, sin riesgo de fugas de memoria (OOM):

val ragFile = File(context.filesDir, "transcriptions.jsonl")

// La IA lee el flujo de disco usando secuencias Lazy (useLines)
ragFile.useLines { lines ->
    lines.forEach { line ->
        val json = JSONObject(line)
        val speaker = json.getString("speaker")
        val text = json.getString("text")
        // Inyectar en el vector de contexto del LLM (Prompt engeneering)
    }
}


***

### Siguientes Pasos
Este documento traza la hoja de ruta exacta. 