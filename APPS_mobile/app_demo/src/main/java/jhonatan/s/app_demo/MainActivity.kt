// app_demo/src/main/java/jhonatan/s/app_demo/MainActivity.kt
// app_demo/src/main/java/jhonatan/s/app_demo/MainActivity.kt
package jhonatan.s.app_demo

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            startVoiceDaemon()
        } else {
            Toast.makeText(this, "Jarvis requiere micrófono para operar.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pedimos permisos de micrófono y notificaciones (Android 13+)
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startVoiceDaemon() {
        val serviceIntent = Intent(this, VoiceHeadlessService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Corteza Auditiva activada en segundo plano.", Toast.LENGTH_SHORT).show()

        // Se autodestruye la UI. El demonio queda vivo.
        finish()
    }
}
