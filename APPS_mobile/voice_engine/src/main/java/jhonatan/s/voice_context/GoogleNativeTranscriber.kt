// voice_engine/src/main/java/jhonatan/s/voice_context/GoogleNativeTranscriber.kt
package jhonatan.s.voice_context

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class GoogleNativeTranscriber(private val context: Context) : VoiceTranscriber {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isUserIntendingToRecord = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var currentOnResult: ((String) -> Unit)? = null
    private var currentOnError: ((String) -> Unit)? = null

    init {
        setupRecognizer()
    }

    private fun setupRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    // El micrófono ya está escuchando, devolvemos el volumen a la normalidad
                    unmuteSystemSound()
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    unmuteSystemSound() // Por si falla antes de estar listo
                    val errorMessage = getErrorText(error)

                    if (isUserIntendingToRecord && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)) {
                        startListeningInternal()
                    } else {
                        currentOnError?.invoke(errorMessage)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        currentOnResult?.invoke(matches[0])
                    }

                    if (isUserIntendingToRecord) {
                        startListeningInternal()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } else {
            Log.e("Transcriber", "El reconocimiento de voz no está disponible")
        }
    }

    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        isUserIntendingToRecord = true
        currentOnResult = onResult
        currentOnError = onError
        startListeningInternal()
    }

    private fun startListeningInternal() {
        muteSystemSound() // Silenciamos el "BEEP" antes de llamar al intent
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
    }

    override fun stopListening() {
        isUserIntendingToRecord = false
        speechRecognizer?.stopListening()
    }

    override fun destroy() {
        isUserIntendingToRecord = false
        speechRecognizer?.destroy()
    }

    // --- MÉTODOS PARA CONTROLAR EL SONIDO ---
    private fun muteSystemSound() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
    }

    private fun unmuteSystemSound() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
    }

    private fun getErrorText(errorCode: Int): String = when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
        SpeechRecognizer.ERROR_CLIENT -> "Error del cliente"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisos insuficientes"
        SpeechRecognizer.ERROR_NETWORK -> "Error de red"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de espera de red agotado"
        SpeechRecognizer.ERROR_NO_MATCH -> "No se encontró coincidencia"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
        SpeechRecognizer.ERROR_SERVER -> "Error del servidor"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tiempo de espera de voz agotado"
        else -> "Error desconocido"
    }
}

