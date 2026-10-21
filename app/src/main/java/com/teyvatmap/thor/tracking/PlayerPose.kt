package com.teyvatmap.thor.tracking

import android.graphics.Bitmap
import com.teyvatmap.thor.tracking.cv.TrackState

/**
 * Where the player is, as published by the tracking service. [xPos]/[yPos] are HoYoLAB game coordinates (the same
 * space as the points); [hasPosition] is false until the first lock. [floorId]/[groupId] are null on the surface.
 */
data class PlayerPose(
    val mapId: Int = 2,
    val xPos: Double = 0.0,
    val yPos: Double = 0.0,
    val headingDeg: Double? = null,
    val confidence: Double = 0.0,
    val state: TrackState = TrackState.OFF,
    val groupId: Int? = null,
    val floorId: Int? = null,
    val hasPosition: Boolean = false,
    val timeMs: Long = 0,
    /** Live minimap scale (minimap px per zoom-0 px); changes when the game zooms the minimap. 0 = unknown. */
    val scale: Double = 0.0,
)

/** Everything the debug view shows for the latest check (images are created only while the debug view is open). */
class DebugSnapshot(
    val timeMs: Long,
    val state: TrackState,
    val mode: String,
    val score: Double,
    val margin: Double,
    val confidence: Double,
    val delaySec: Double,
    val totalMs: Double,
    val stageMs: Map<String, Double>,
    val ringNcc: Double,
    val thumbMedian: Double,
    val thumbUniform: Double,
    val layerTrigger: String,
    val scale: Double,
    val baseScale: Double,
    val scaleCurve: List<Pair<Double, Double>>,
    val floorId: Int?,
    val counters: Map<String, Number>,
    val crop: Bitmap?,
    val mask: Bitmap?,
    val heat: Bitmap?,
    /** Region zoom table, big-map fix and banner OCR state, one per line. */
    val info: String = "",
)
