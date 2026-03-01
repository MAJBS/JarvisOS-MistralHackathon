// rag_engine/src/main/java/jhonatan/s/rag_engine/ai/CognitiveNodeConnector.kt
package jhonatan.s.rag_engine.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import jhonatan.s.rag_engine.headless.JarvisRagHeadlessService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ============================================================================
 * CONECTOR DEL NODO COGNITIVO (Implementación IA Mayor)
 * ============================================================================
 * Esta clase gestiona la vinculación segura entre la IA Mayor y el servicio
 * Headless JarvisRag operando en segundo plano.
 */
class CognitiveNodeConnector(private val context: Context) {

    // 🛡️ CORRECCIÓN: Aislamiento estático de la constante de compilación
    companion object {
        private const val TAG = "CognitiveNodeConnector"
    }

    private var headlessService: JarvisRagHeadlessService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as JarvisRagHeadlessService.LocalBinder
            headlessService = binder.getService()
            isBound = true
            Log.i(TAG, "Conexión establecida con el Nodo Cognitivo JarvisRag.")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            headlessService = null
            isBound = false
            Log.e(TAG, "Conexión perdida con el Nodo Cognitivo.")
        }
    }

    /**
     * Inicia el servicio y lo vincula. Usa suspendCancellableCoroutine para
     * esperar asincrónicamente hasta que el servicio esté listo.
     */
    suspend fun bindServiceAndBoot(): Boolean = suspendCancellableCoroutine { continuation ->
        if (isBound && headlessService != null) {
            continuation.resume(true)
            return@suspendCancellableCoroutine
        }

        val intent = Intent(context, JarvisRagHeadlessService::class.java)

        // Envolvemos el ServiceConnection para reanudar la corrutina cuando se conecte
        val connectionWrapper = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                val binder = service as JarvisRagHeadlessService.LocalBinder
                headlessService = binder.getService()
                isBound = true
                Log.i(TAG, "✅ [IA Mayor] Conectado al Servicio Headless.")

                // Una vez conectado, ordenamos el arranque del motor nativo Rust
                // Importante: Esto no bloquea porque se ejecuta en el Scope del Servicio
                continuation.resume(true)
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                isBound = false
                headlessService = null
            }
        }

        context.bindService(intent, connectionWrapper, Context.BIND_AUTO_CREATE)

        continuation.invokeOnCancellation {
            context.unbindService(connectionWrapper)
        }
    }

    /**
     * Función principal que utilizará la IA Mayor.
     * Envía la transcripción de voz y recibe el Super-Prompt estructurado.
     */
    suspend fun getSuperPrompt(userAudioTranscript: String): String {
        if (!isBound || headlessService == null) {
            throw IllegalStateException("El Nodo Cognitivo no está vinculado. Llama a bindServiceAndBoot() primero.")
        }

        val result = headlessService!!.requestDeterminativeContext(userAudioTranscript)

        return result.getOrElse { error ->
            Log.e(TAG, "Error obteniendo contexto. Usando fallback de emergencia.", error)
            // Fallback de emergencia en caso de que Rust colapse
            """
            <system>
            Atención: Tu módulo de memoria JarvisRag ha sufrido un fallo técnico (${error.message}).
            Responde a la consulta del usuario basándote únicamente en tu conocimiento interno.
            </system>
            <user_query>$userAudioTranscript</user_query>
            """.trimIndent()
        }
    }

    fun unbind() {
        if (isBound) {
            context.unbindService(connection)
            isBound = false
            Log.i(TAG, "Desvinculado del Nodo Cognitivo correctamente.")
        }
    }
}
