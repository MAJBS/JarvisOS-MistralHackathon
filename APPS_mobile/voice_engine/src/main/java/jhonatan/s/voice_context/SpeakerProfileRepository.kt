// voice_engine/src/main/java/jhonatan/s/voice_context/SpeakerProfileRepository.kt
// voice_engine/src/main/java/jhonatan/s/voice_context/SpeakerProfileRepository.kt
package jhonatan.s.voice_context

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class SpeakerProfileRepository(context: Context) {

    private val profileFile = File(context.filesDir, "speaker_profiles_v1.json")
    private val mutex = Mutex()

    private val inMemoryProfiles = mutableListOf<ProfileEntity>()

    init {
        loadProfilesFromDisk()
    }

    data class ProfileEntity(
        var name: String,
        var vector: FloatArray,
        var enrollmentCount: Int
    )

    suspend fun getAllProfiles(): List<SpeakerProfile> = mutex.withLock {
        // [FIX]: Ahora mapeamos también la cantidad de muestras
        inMemoryProfiles.map { SpeakerProfile(it.name, it.vector, it.enrollmentCount) }
    }

    /**
     * Matricula o actualiza un vector, retornando el análisis matemático de la variación.
     */
    suspend fun enrollOrUpdateSpeaker(name: String, newVector: FloatArray): EnrollmentResult = mutex.withLock {
        val existingProfile = inMemoryProfiles.find { it.name.equals(name, ignoreCase = true) }

        if (existingProfile != null) {
            val count = existingProfile.enrollmentCount
            val averagedVector = FloatArray(newVector.size)

            // MEAN POOLING MATEMÁTICO
            for (i in newVector.indices) {
                averagedVector[i] = ((existingProfile.vector[i] * count) + newVector[i]) / (count + 1)
            }

            val normalizedNewVector = l2Normalize(averagedVector)

            // --- CÁLCULO DEL DELTA (VARIACIÓN) ---
            var sumSqShift = 0.0f
            val dimensionChanges = mutableListOf<DimensionChange>()
            val oldVectorClone = existingProfile.vector.clone()

            for (i in oldVectorClone.indices) {
                val oldVal = oldVectorClone[i]
                val newVal = normalizedNewVector[i]
                val diff = abs(newVal - oldVal)
                sumSqShift += (newVal - oldVal) * (newVal - oldVal)
                dimensionChanges.add(DimensionChange(i, oldVal, newVal, diff))
            }

            val euclideanShift = sqrt(sumSqShift)
            // Extraemos solo el Top 5 de mayores variaciones para no saturar la UI
            val topChanges = dimensionChanges.sortedByDescending { it.absoluteDifference }.take(5)

            val delta = BiometricDelta(name, oldVectorClone, normalizedNewVector, euclideanShift, topChanges)

            // APLICAMOS LOS CAMBIOS A RAM
            existingProfile.vector = normalizedNewVector
            existingProfile.enrollmentCount += 1
            Log.i("SpeakerRepo", "Perfil '$name' actualizado. Muestras totales: ${existingProfile.enrollmentCount}. Shift Euclidiano: $euclideanShift")

            saveProfilesToDisk()
            return@withLock EnrollmentResult.Updated(SpeakerProfile(name, normalizedNewVector, existingProfile.enrollmentCount), delta)
        } else {
            val normalizedVector = l2Normalize(newVector)
            inMemoryProfiles.add(ProfileEntity(name, normalizedVector, 1))
            Log.i("SpeakerRepo", "Nuevo perfil creado: '$name'")
            saveProfilesToDisk()
            return@withLock EnrollmentResult.New(SpeakerProfile(name, normalizedVector, 1))
        }
    }

    suspend fun renameSpeaker(oldName: String, newName: String): Boolean = mutex.withLock {
        val profile = inMemoryProfiles.find { it.name.equals(oldName, ignoreCase = true) }
        if (profile != null) {
            profile.name = newName
            saveProfilesToDisk()
            return true
        }
        return false
    }

    suspend fun deleteSpeaker(name: String): Boolean = mutex.withLock {
        val removed = inMemoryProfiles.removeIf { it.name.equals(name, ignoreCase = true) }
        if (removed) saveProfilesToDisk()
        return removed
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0f
        for (v in vector) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm < 1e-8f) return vector

        val normalized = FloatArray(vector.size)
        for (i in vector.indices) {
            normalized[i] = vector[i] / norm
        }
        return normalized
    }

    private fun loadProfilesFromDisk() {
        if (!profileFile.exists()) return
        try {
            val jsonString = profileFile.readText()
            val jsonArray = JSONArray(jsonString)
            inMemoryProfiles.clear()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.getString("name")
                val count = obj.getInt("enrollmentCount")
                val vectorArray = obj.getJSONArray("vector")

                val vector = FloatArray(vectorArray.length())
                for (j in 0 until vectorArray.length()) {
                    vector[j] = vectorArray.getDouble(j).toFloat()
                }
                inMemoryProfiles.add(ProfileEntity(name, vector, count))
            }
        } catch (e: Exception) {
            Log.e("SpeakerRepo", "Error crítico leyendo perfiles: ${e.message}")
        }
    }

    private suspend fun saveProfilesToDisk() = withContext(Dispatchers.IO) {
        try {
            val jsonArray = JSONArray()
            for (profile in inMemoryProfiles) {
                val obj = JSONObject()
                obj.put("name", profile.name)
                obj.put("enrollmentCount", profile.enrollmentCount)

                val vectorArray = JSONArray()
                for (v in profile.vector) vectorArray.put(v.toDouble())
                obj.put("vector", vectorArray)

                jsonArray.put(obj)
            }
            profileFile.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e("SpeakerRepo", "Error guardando perfiles: ${e.message}")
        }
    }
}
