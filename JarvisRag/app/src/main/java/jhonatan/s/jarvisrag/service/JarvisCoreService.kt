// app/src/main/java/jhonatan/s/jarvisrag/service/JarvisCoreService.kt
package jhonatan.s.jarvisrag.service

import kotlinx.coroutines.runBlocking
import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import jhonatan.s.slm_engine.SlmEngineManager

class JarvisCoreService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "JARVIS_CORE"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Jarvis Core", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis OS")
            .setContentText("Córtex Cognitivo Activo (Aceleración GPU)")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(999, notification)
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()

        // 🛡️ BLOQUEO DE SEGURIDAD:
        // Usamos runBlocking porque estamos en el evento final de muerte del servicio.
        // Debemos esperar (bloquear) a que el Mutex de SlmEngineManager se libere
        // para garantizar que no matamos el motor C++ mientras está escribiendo en RAM.
        runBlocking {
            SlmEngineManager.release()
        }
    }
}
