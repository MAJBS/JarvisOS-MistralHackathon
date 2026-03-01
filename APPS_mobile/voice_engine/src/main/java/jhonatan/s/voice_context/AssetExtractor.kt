// voice_engine/src/main/java/jhonatan/s/voice_context/AssetExtractor.kt
package jhonatan.s.voice_context

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AssetExtractor {

    fun copyAssetsToCache(context: Context, folderName: String): String {
        val prefs = context.getSharedPreferences("AssetPrefs", Context.MODE_PRIVATE)

        // Obtenemos la versión actual de la app para saber si hubo actualización
        val currentVersion = try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            1L
        }

        val storedVersion = prefs.getLong("version_$folderName", -1L)
        val cacheDir = File(context.cacheDir, folderName)

        // Si la versión de la app cambió, borramos la caché para forzar la extracción de los nuevos assets
        if (currentVersion != storedVersion) {
            Log.d("AssetExtractor", "Nueva versión detectada. Limpiando caché de $folderName...")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
            prefs.edit().putLong("version_$folderName", currentVersion).apply()
        }

        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val assetManager = context.assets
        try {
            val files = assetManager.list(folderName) ?: return cacheDir.absolutePath

            for (filename in files) {
                val outFile = File(cacheDir, filename)

                // Si el archivo ya existe, no lo volvemos a copiar (ahorra tiempo)
                if (outFile.exists()) continue

                val inputStream = assetManager.open("$folderName/$filename")
                val outputStream = FileOutputStream(outFile)

                val buffer = ByteArray(1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }

                inputStream.close()
                outputStream.flush()
                outputStream.close()

                Log.d("AssetExtractor", "Copiado: $filename a ${outFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("AssetExtractor", "Error copiando assets: ${e.message}")
        }

        return cacheDir.absolutePath
    }
}
