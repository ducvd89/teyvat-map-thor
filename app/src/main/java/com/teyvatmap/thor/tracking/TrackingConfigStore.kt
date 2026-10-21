package com.teyvatmap.thor.tracking

import android.content.Context
import android.util.Log
import com.teyvatmap.thor.tracking.cv.Calib
import com.teyvatmap.thor.tracking.cv.TrackerParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SavedPose(val mapId: Int, val xPos: Double, val yPos: Double, val floorId: Int? = null, val savedAtMs: Long = 0)

/**
 * Persisted tracking configuration: the calibration profile (minimap circle, scale, ring reference), the
 * [TrackerParams] and the last known position, as JSON in `getExternalFilesDir/cv/`.
 */
@Singleton
class TrackingConfigStore @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "cv").also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val _calib = MutableStateFlow(read("calibration.json")?.let { runCatching { json.decodeFromString(Calib.serializer(), it) }.getOrNull() })
    /** The saved calibration, or null when the user never calibrated. */
    val calib: StateFlow<Calib?> = _calib.asStateFlow()

    private val _params = MutableStateFlow(read("params.json")?.let { runCatching { TrackerParams.fromJson(it) }.getOrNull()?.takeIf { it.paramsVersion == TrackerParams.CURRENT_VERSION } } ?: TrackerParams())
    val params: StateFlow<TrackerParams> = _params.asStateFlow()

    private fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()

    private fun write(name: String, text: String) {
        val f = File(dir, name)
        val tmp = File(dir, "$name.part")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    fun saveCalib(c: Calib) {
        write("calibration.json", json.encodeToString(Calib.serializer(), c))
        _calib.value = c
    }

    fun clearCalib() {
        File(dir, "calibration.json").delete()
        _calib.value = null
    }

    fun saveParams(p: TrackerParams) {
        write("params.json", p.toJson())
        _params.value = p
        Log.i("TrackingConfig", "params saved")
    }

    fun lastPose(): SavedPose? = read("lastpose.json")?.let { runCatching { json.decodeFromString(SavedPose.serializer(), it) }.getOrNull() }

    fun saveLastPose(p: SavedPose) = write("lastpose.json", json.encodeToString(SavedPose.serializer(), p))
}
