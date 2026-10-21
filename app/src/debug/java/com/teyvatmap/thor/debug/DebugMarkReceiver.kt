package com.teyvatmap.thor.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only bulk marking without the UI:
 * `adb shell am broadcast -n com.teyvatmap.thor/.debug.DebugMarkReceiver -a com.teyvatmap.thor.DEBUG_MARK_FOUND --es names "Anemoculus,Geoculus" --ez found true`
 * (or `--es label_ids "5,6"`). Names match label names case-insensitively on every map; a group name/id expands to
 * its leaf categories. `found` defaults to true. Logs `DebugMark: changed N points` (tag DebugMark).
 */
class DebugMarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val names = intent.getStringExtra("names")?.split(',').orEmpty()
        val ids = intent.getStringExtra("label_ids")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val found = intent.getBooleanExtra("found", true)
        if (names.isEmpty() && ids.isEmpty()) { Log.w(TAG, "need --es names or --es label_ids"); return }
        val pending = goAsync()
        val repo = EntryPointAccessors.fromApplication(context.applicationContext, DebugEntryPoint::class.java).marks()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val labels = (repo.leafLabelsByName(names) + repo.leafLabelsById(ids)).distinct()
                val ch = repo.bulkSetFound(labels, found)
                Log.i(TAG, "changed ${ch.count} points (found=$found, ${labels.size} categories; names=$names ids=$ids)")
            } catch (e: Exception) {
                Log.w(TAG, "failed: $e")
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val TAG = "DebugMark" }
}
