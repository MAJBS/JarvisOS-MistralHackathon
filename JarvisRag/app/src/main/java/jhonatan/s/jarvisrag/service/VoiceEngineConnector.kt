// app/src/main/java/jhonatan/s/jarvisrag/service/VoiceEngineConnector.kt
package jhonatan.s.jarvisrag.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import jhonatan.s.jarvis.ipc.IVoiceEngineCallback
import jhonatan.s.jarvis.ipc.IVoiceEngineService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VoiceEngineConnector(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onTranscriptionReceived: (String, String, Long) -> Unit,
    // 🚀 NUEVO: Callback directo para la memoria sellada
    private val onTranscriptionSealedReceived: (String, String, Long, Long) -> Unit,
    private val onProgress: (Int, Int) -> Unit,
    private val onProfilesReceived: (String) -> Unit
) {
    private var voiceService: IVoiceEngineService? = null
    private var isBound = false

    private val connectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reconnectMutex = Mutex()
    private var isReconnecting = false

    private val callback = object : IVoiceEngineCallback.Stub() {
        override fun onTranscription(speakerName: String, text: String, timestampMs: Long) {
            onLog("🗣️ AUDIO RECIBIDO [$speakerName]: $text")
            onTranscriptionReceived(speakerName, text, timestampMs)
        }

        // 🚀 NUEVO: Recepción de Memoria Sellada desde AIDL
        override fun onTranscriptionSealed(speakerName: String, text: String, startTimeMs: Long, endTimeMs: Long) {
            onLog("🗄️ [MEMORIA SELLADA] $speakerName: $text")
            onTranscriptionSealedReceived(speakerName, text, startTimeMs, endTimeMs)
        }

        override fun onEngineStateChanged(state: String, message: String) {
            onLog("👂 ESTADO OÍDOS: $state - $message")
        }
        override fun onBiometricResult(profileName: String, shiftPercent: Float, success: Boolean) {
            val status = if (success) "ÉXITO" else "FALLO"
            onLog("🧬 BIOMETRÍA [$status]: $profileName (Variación: ${String.format("%.2f", shiftPercent)}%)")
        }
        override fun onProgressUpdate(currentSecond: Int, totalSeconds: Int) {
            onProgress(currentSecond, totalSeconds)
        }
        override fun onProfilesUpdated(profilesJson: String) {
            onProfilesReceived(profilesJson)
        }
    }

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            onLog("💀 [ALERTA CRÍTICA] El proceso esclavo ha sido asesinado.")
            voiceService?.asBinder()?.unlinkToDeath(this, 0)
            voiceService = null
            isBound = false
            triggerResurrectionProtocol()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            voiceService = IVoiceEngineService.Stub.asInterface(service)
            try {
                service?.linkToDeath(deathRecipient, 0)
                voiceService?.registerCallback(callback)
                isBound = true
                isReconnecting = false
                onLog("✅ ENLACE AIDL ESTABLECIDO: Oídos conectados y blindados.")
                safeAidlCall { requestProfiles() }
            } catch (e: RemoteException) {
                onLog("❌ Error al registrar callback: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (isBound) {
                onLog("⚠️ ENLACE ROTO: Desconexión abrupta detectada.")
                voiceService = null
                isBound = false
                triggerResurrectionProtocol()
            }
        }
    }

    fun connectToEars() {
        if (isBound || isReconnecting) return
        executeBinding()
    }

    private fun executeBinding() {
        try {
            val intent = Intent("jhonatan.s.jarvis.ACTION_BIND_VOICE_ENGINE").apply {
                setPackage("jhonatan.s.app_demo")
            }
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            onLog("⛔ [SECURITY EXCEPTION] Permiso denegado. Verifica las firmas de los APKs.")
        } catch (e: Exception) {
            onLog("❌ Excepción de conexión: ${e.message}")
        }
    }

    fun disconnect() {
        if (isBound) {
            try {
                voiceService?.unregisterCallback(callback)
                voiceService?.asBinder()?.unlinkToDeath(deathRecipient, 0)
                context.unbindService(connection)
                isBound = false
                isReconnecting = false
                connectorScope.cancel()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun triggerResurrectionProtocol() {
        connectorScope.launch {
            reconnectMutex.withLock {
                if (isReconnecting) return@launch
                isReconnecting = true
            }
            var attempt = 1
            var delayMs = 1000L
            while (!isBound && isReconnecting) {
                onLog("🔄 Protocolo Lázaro: Intento de reconexión #$attempt...")
                executeBinding()
                delay(delayMs)
                if (!isBound) {
                    attempt++
                    delayMs = (delayMs * 2).coerceAtMost(8000L)
                }
            }
        }
    }

    private fun safeAidlCall(action: IVoiceEngineService.() -> Unit) {
        if (!isBound || voiceService == null) {
            onLog("⚠️ Comando ignorado: El túnel AIDL está caído.")
            return
        }
        try {
            voiceService?.action()
        } catch (e: DeadObjectException) {
            onLog("💀 [DeadObjectException] El esclavo murió durante la transmisión.")
            triggerResurrectionProtocol()
        } catch (e: RemoteException) {
            onLog("❌ [RemoteException] Fallo en IPC: ${e.message}")
        } catch (e: Exception) {
            onLog("❌ Error inesperado en IPC: ${e.message}")
        }
    }

    fun startListening() = safeAidlCall { startContinuousCapture() }
    fun stopListening() = safeAidlCall { stopContinuousCapture() }
    fun loadModel(modelName: String) = safeAidlCall { loadModel(modelName) }
    fun enrollSpeaker(name: String) = safeAidlCall { enrollSpeakerProfile(name) }
    fun verifySpeaker() = safeAidlCall { verifySpeaker() }
    fun deleteProfile(name: String) = safeAidlCall { deleteProfile(name) }
    fun requestProfiles() = safeAidlCall { requestProfiles() }
}
