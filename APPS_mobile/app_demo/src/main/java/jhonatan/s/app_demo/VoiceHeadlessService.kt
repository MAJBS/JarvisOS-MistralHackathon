// app_demo/src/main/java/jhonatan/s/app_demo/VoiceHeadlessService.kt
package jhonatan.s.app_demo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import jhonatan.s.jarvis.ipc.IVoiceEngineCallback
import jhonatan.s.jarvis.ipc.IVoiceEngineService

/**
 * SERVICIO FANTASMA (HEADLESS DAEMON)
 * Opera en el espacio de usuario como un Foreground Service persistente.
 * Implementa el protocolo IVoiceEngineService para recibir órdenes del Master.
 */
class VoiceHeadlessService : Service() {

    private var masterCallback: IVoiceEngineCallback? = null
    private lateinit var engineCore: VoiceEngineCore

    // ========================================================================
    // IMPLEMENTACIÓN DEL CONTRATO AIDL (Órdenes del Master)
    // ========================================================================
    private val binder = object : IVoiceEngineService.Stub() {

        override fun registerCallback(callback: IVoiceEngineCallback?) {
            masterCallback = callback
            Log.i("VOICE_ENGINE", "✅ Master vinculado. Túnel AIDL establecido.")
            sendEngineState("CONNECTED", "Enlace con JarvisRag activo.")
            // Al conectar, enviamos la lista de perfiles actual
            engineCore.refreshProfilesToMaster()
        }

        override fun unregisterCallback(callback: IVoiceEngineCallback?) {
            masterCallback = null
            Log.i("VOICE_ENGINE", "⚠️ Master desvinculado.")
        }

        override fun startContinuousCapture() {
            Log.i("VOICE_ENGINE", ">> Comando: START_CAPTURE")
            engineCore.startListening()
        }

        override fun stopContinuousCapture() {
            Log.i("VOICE_ENGINE", ">> Comando: STOP_CAPTURE")
            engineCore.stopListening()
        }

        override fun enrollSpeakerProfile(name: String) {
            Log.i("VOICE_ENGINE", ">> Comando: ENROLL_SPEAKER ($name)")
            engineCore.enrollSpeaker(name)
        }

        override fun verifySpeaker() {
            Log.i("VOICE_ENGINE", ">> Comando: VERIFY_SPEAKER")
            engineCore.verifySpeaker()
        }

        override fun setParallelMode(enabled: Boolean) {
            Log.i("VOICE_ENGINE", ">> Comando: SET_PARALLEL ($enabled)")
            engineCore.setParallelMode(enabled)
        }

        override fun loadModel(modelName: String) {
            Log.i("VOICE_ENGINE", ">> Comando: LOAD_MODEL ($modelName)")
            engineCore.bootEngine(modelName)
        }

        override fun requestProfiles() {
            Log.i("VOICE_ENGINE", ">> Comando: REQUEST_PROFILES")
            engineCore.refreshProfilesToMaster()
        }

        override fun deleteProfile(name: String) {
            Log.i("VOICE_ENGINE", ">> Comando: DELETE_PROFILE ($name)")
            engineCore.deleteProfile(name)
        }
    }

    // ========================================================================
    // CICLO DE VIDA DEL SERVICIO
    // ========================================================================

    override fun onCreate() {
        super.onCreate()

        // 1. Configuración de Notificación Persistente (Foreground)
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "VOICE_ENGINE_CHANNEL")
            .setContentTitle("Jarvis Voice Engine")
            .setContentText("Corteza auditiva operando en segundo plano")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        // 🛡️ RIGOR ANDROID 14: Declaración de tipo Microphone
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1001, notification)
        }

        // 2. Inicialización del motor lógico
        engineCore = VoiceEngineCore(this, this)

        // El motor arranca por defecto con Whisper Small
        engineCore.bootEngine("whisper_small")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.w("VOICE_ENGINE", "🛑 Destruyendo servicio. Liberando recursos nativos.")
        engineCore.release()
    }

    // ========================================================================
    // MÉTODOS DE SALIDA (Callbacks hacia el Master)
    // Todos protegidos con try-catch para evitar DeadObjectException
    // ========================================================================

    fun sendTranscriptionToBrain(speaker: String, text: String, timestamp: Long) {
        try {
            masterCallback?.onTranscription(speaker, text, timestamp)
        } catch (e: Exception) {
            Log.e("VOICE_ENGINE", "Error enviando transcripción: ${e.message}")
        }
    }

    // Añade esta función debajo de sendTranscriptionToBrain
    fun sendTranscriptionSealedToBrain(speaker: String, text: String, startTime: Long, endTime: Long) {
        try {
            masterCallback?.onTranscriptionSealed(speaker, text, startTime, endTime)
        } catch (e: Exception) {
            Log.e("VOICE_ENGINE", "Error enviando transcripción sellada: ${e.message}")
        }
    }

    fun sendEngineState(state: String, message: String) {
        try {
            masterCallback?.onEngineStateChanged(state, message)
        } catch (e: Exception) {
            Log.e("VOICE_ENGINE", "Error enviando estado: ${e.message}")
        }
    }

    fun sendBiometricResult(profileName: String, shiftPercent: Float, success: Boolean) {
        try {
            masterCallback?.onBiometricResult(profileName, shiftPercent, success)
        } catch (e: Exception) {
            Log.e("VOICE_ENGINE", "Error enviando biometría: ${e.message}")
        }
    }

    fun sendProgressUpdate(current: Int, total: Int) {
        try {
            masterCallback?.onProgressUpdate(current, total)
        } catch (e: Exception) {
            Log.e("VOICE_ENGINE", "Error enviando progreso: ${e.message}")
        }
    }

    fun sendProfilesUpdated(profilesJson: String) {
        try {
            masterCallback?.onProfilesUpdated(profilesJson)
        } catch (e: Exception) {
            Log.e("VOICE_ENGINE", "Error enviando perfiles: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "VOICE_ENGINE_CHANNEL",
                "Jarvis Auditory Cortex",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

}
