package com.teyvatmap.thor.tracking

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Learned floor-selector mapping of the big map: (floor group, tile count, lit tile index) -> floor id. The game's tile order is not the data
 * order of `point_groups.json`, so it is learned whenever the floor art on the screen identifies the highlighted floor with confidence;
 * later the selector alone can decide. Persisted as a small JSON file ([file] null = memory only).
 */
class FloorSelectorMapStore(private val file: File? = null) {
    private val map = HashMap<String, Int>()

    init {
        runCatching {
            if (file != null && file.exists()) JSONObject(file.readText()).let { o -> o.keys().forEach { map[it] = o.getInt(it) } }
        }.onFailure { Log.w(TAG, "selector map unreadable", it) }
    }

    private fun key(groupId: Int, n: Int, idx: Int) = "$groupId:$n:$idx"

    @Synchronized fun get(groupId: Int, n: Int, idx: Int): Int? = map[key(groupId, n, idx)]

    /** Records the mapping; persists when it changed. */
    @Synchronized
    fun put(groupId: Int, n: Int, idx: Int, floorId: Int) {
        val k = key(groupId, n, idx)
        if (map[k] == floorId) return
        map[k] = floorId
        runCatching { file?.writeText(JSONObject(map as Map<*, *>).toString()) }.onFailure { Log.w(TAG, "selector map not saved", it) }
    }

    private companion object { const val TAG = "FloorSelectorMap" }
}
