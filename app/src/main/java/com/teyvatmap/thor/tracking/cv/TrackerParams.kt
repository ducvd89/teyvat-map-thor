package com.teyvatmap.thor.tracking.cv

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every threshold of the tracking pipeline in one place (a verbatim port of `Params`, `ScreenCfg` and `TrackCfg` of
 * `tools/cv`, see tools/cv/README.md "State machine and parameters"). Persisted as JSON and editable in the debug
 * view. All values were chosen on synthetic frames and must be re-tuned on real Thor captures.
 *
 * [sigma] and [lcnC] are baked into the prepared reference images; changing them needs an index rebuild, so the
 * debug view shows them read-only (the template always uses the values the index was built with).
 */
@Serializable
data class TrackerParams(
    /** Parameter-set generation: saved params of an older generation (synthetic tuning) are discarded on load. */
    val paramsVersion: Int = CURRENT_VERSION,
    // ---- representation / masks (preprocess.Params)
    val sigma: Double = 1.2,
    val lcnC: Double = 6.0,
    val rIn: Double = 0.18,             // real Thor frames (real_report.md section 1): annulus 0.18R..0.80R
    val rOut: Double = 0.80,
    val icons: Boolean = true,
    val satThr: Int = 175,
    val valThr: Int = 150,
    val whiteThr: Int = 232,
    val whiteSat: Int = 40,
    val maxBlob: Double = 0.035,
    val dilate: Int = 3,
    // ---- matcher
    val coarseZ: Int = -2,
    val targetZ: Int = -3,
    val globalTopK: Int = 8,
    val refineRadius: Double = 24.0,
    val searchThreads: Int = 3,
    val featureFallback: Boolean = true,
    // ---- cheap screen tests (screen.ScreenCfg)
    val loadFrac: Double = 0.92,
    val loadBand: Double = 12.0,
    val loadBright: Double = 228.0,
    val loadDark: Double = 26.0,
    /** "contrast" = local-contrast ring test of the real Thor (default); "ncc" = legacy correlation with `Calib.ringRef` (synthetic frames). */
    val ringMode: String = "contrast",
    val ringContrastThr: Double = 6.0,  // real: 11..21 with the HUD, 0.0..0.12 on loading / big map
    val ringAngles: Int = 180,
    val ringR0: Double = -1.5,
    val ringR1: Double = 5.5,
    val ringDr: Double = 1.0,
    val ringThr: Double = 0.70,
    val ringTol: Int = 1,           // ring sampling pattern is also tried shifted by up to this many px (centre) and +-1 px (radius)
    val blackRIn: Double = 0.62,
    val blackROut: Double = 0.88,
    val blackMedian: Double = 8.0,
    val blackP90: Double = 24.0,
    val blackEdgeThr: Int = 96,
    val blackEdgeFrac: Double = 0.03,
    val chgSize: Int = 32,
    val chgRIn: Double = 0.55,
    val chgROut: Double = 0.88,
    val chgThr: Double = 1.5,
    val flatSpread: Double = 6.0,
    val flatEdgeFrac: Double = 0.10,
    // ---- check delays in seconds (track.TrackCfg)
    val dTracking: Double = 1.0,
    val dFast: Double = 0.5,
    val dStill: Double = 2.0,
    val dLoading: Double = 1.0,
    val dRelocating: Double = 0.5,
    val dDomain: Double = 5.0,
    val dHidden: Double = 2.0,
    val dLost: Double = 3.0,
    val dPending: Double = 1.0,
    val dLayerPending: Double = 0.5,
    val fastSpeedFrac: Double = 0.12,
    val stillAfter: Double = 10.0,
    val stillVerifyEvery: Int = 5,
    // ---- state transitions
    val uniformChecks: Int = 2,
    val blackChecks: Int = 2,
    val maxMiss: Int = 3,
    val relocMax: Double = 10.0,
    val relocAgree: Double = 30.0,
    // ---- local tracking
    val vmax: Double = 45.0,
    val localMinRadius: Double = 24.0,
    val localMaxRadius: Double = 90.0,
    val tauLocal: Double = 0.40,
    val tauMargin: Double = 0.02,
    val alpha: Double = 0.85,
    val beta: Double = 0.25,
    // ---- relocation search order
    val relocOrderTargets: Boolean = true,
    val relocLastRadius: Double = 150.0,
    val tauRelocLocal: Double = 0.55,
    val tauRelocLocalMargin: Double = 0.10,
    val tauGlobal: Double = 0.50,
    val tauGlobalMargin: Double = 0.08,
    val targetWin: Double = 60.0,
    val targetTopK: Int = 5,
    val domainNear: Double = 3000.0,
    val fullAfterFails: Int = 1,
    val skipFlat: Boolean = true,
    val confirmRadius: Double = 40.0,
    // ---- layers
    val layersOn: Boolean = true,
    val tauLayerDrop: Double = 0.10,
    val layerPeriod: Double = 10.0,
    val layerBias: Double = 0.03,
    val layerAgree: Int = 2,
    val layerOverride: Double = 0.15,
    val avgAlpha: Double = 0.3,
    // ---- layer entry points (PLAN §6.2)
    val useEntrances: Boolean = true,
    val entranceRadius: Double = 60.0,
    val entranceNearest: Double = 600.0,
    val layerStopScore: Double = 0.65,
    /** Relocation candidates matched against a floor overlay (dark, low-texture minimaps score lower than the surface). */
    val tauFloor: Double = 0.40,
    val tauFloorMargin: Double = 0.08,
    /** Floors as references of their own in RELOCATING / LOST: a floor is scanned at scale s only if its longest side covers this fraction of the minimap's diameter (z0 px). */
    val floorMinVisible: Double = 0.4,
    /** Rung step of the floor-scale scan (the NCC peak is +-2 % wide on the small, sharp floor drawings; the scan runs at the coarse floor level) and the zoom it starts around (indoor / fortress zooms: Meropide 1.49, towns 2.22). */
    val floorScanStep: Double = 0.04,
    val floorScaleAround: Double = 1.5,
    // ---- dynamic minimap zoom (PLAN §6.3): ladder of scales around the calibrated base scale s0
    val dynamicScale: Boolean = true,
    val scaleMin: Double = 0.40,        // ladder bounds as multiples of s0 (0.752 on the real Thor: 0.30 .. 2.78 covers 0.752 and 2.222)
    val scaleMax: Double = 3.70,
    val scaleStep: Double = 0.025,      // <= 2.5 % between rungs (the NCC peak is only +-3 % wide)
    val scaleAgree: Int = 2,            // checks in a row that must prefer a neighbour rung before the zoom switches
    val scaleBias: Double = 0.01,       // the current rung gets this bonus (hysteresis on top of the 2-in-a-row)
    val scaleDrop: Double = 0.10,       // score this far below the running average triggers a ladder sweep
    val scaleSweepGain: Double = 0.05,  // a sweep switches the zoom only if it beats the current rung by this much
    val sweepBudgetMs: Double = 700.0,   // a ladder sweep in TRACKING stops after this long
    val scaleStopScore: Double = 0.65,  // a sweep / relocation stops at the first rung scoring at least this
    val relocScaleOffsets: List<Int> = listOf(0),   // rungs (relative to s0) tried in RELOCATING / LOST after the region-table scales
    val fullScalesPerCheck: Int = 6,    // unknown region: whole-map search slices through the ladder, this many scales per check
    val coarseZoomIn: Double = 1.5,     // s >= this: coarse whole-map level z-1 instead of z-2 (real_report.md item 6)
    val zoomBorderMargin: Double = 150.0,   // z0 px: near a region border the neighbour region's scale is tried too
    val zoomLearnScore: Double = 0.65,
    val zoomLearnChecks: Int = 3,
    // ---- multi-map relocation (PLAN §6.2): time-sliced plan, coarse scan level and big-map jump gate
    val relocSliceMs: Double = 1200.0,   // one relocation / lost check works at most this long, then continues on the next frame
    val dLostSlice: Double = 1.0,        // LOST: pause between the slices of one unfinished full search (a new search still starts every dLost)
    val scanStep: Double = 0.06,         // ladder step of the whole-map scale scan (coarse level: the NCC peak is wide, a refine step follows)
    val scanMinTemplate: Int = 34,       // whole-map scan: coarsest level whose minimap template is still at least this many px wide
    val relocUnknownOffsets: List<Int> = listOf(0, -2, 2, -4, 4),   // rungs tried first on a map whose zoom is not learned yet
    val bigFixMaxJump: Double = 250.0,   // z0 px: a big-map fix farther than this from the TRACKING position needs a loading screen first
    // ---- masks
    val maskNorth: Boolean = false,     // legacy (synthetic frames): the "N" label as a disc
    val northY: Double = 0.90,          // its centre above the minimap centre, as a fraction of R
    val northR: Double = 0.17,          // its radius, fraction of R
    val maskNorthRect: Boolean = true,  // real: pale serif "N", frame x 199..221, y 36..58 = centre 108.6 px above the circle centre
    val northRectY: Double = 0.814,     // centre above the minimap centre, fraction of R
    val northRectHalf: Double = 0.095,  // half side, fraction of R (11 px + margin)
    // ---- area-name banner ROI (1080p px, centre-top); the banner is OCR'd only when text-like pixels appear there
    val bannerX: Int = 460,
    val bannerY: Int = 80,
    val bannerW: Int = 1000,
    val bannerH: Int = 200,
    // ---- arrow (real: HSV H 91..101, S 130..160, V >= 210; core V >= 235 grown into H 86..104 S >= 100 V >= 190)
    val arrowTemplate: Boolean = true,
) {
    /** The parameter set the synthetic fixtures (tools/cv/synth) were tuned with: legacy ring, mask and ladder. */
    fun synthetic(): TrackerParams = copy(
        ringMode = "ncc", rIn = 0.15, rOut = 0.88, maskNorth = true, maskNorthRect = false,
        scaleMin = 0.55, scaleMax = 1.45, scaleStep = 0.04, arrowTemplate = false, relocScaleOffsets = listOf(0, -4, -8, -12, 4),
    )

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val CURRENT_VERSION = 2
        val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
        fun fromJson(s: String): TrackerParams = JSON.decodeFromString(serializer(), s)
    }
}

/** One editable number in the debug view. */
class ParamField(
    val name: String,
    val step: Double,
    val get: (TrackerParams) -> Double,
    val set: (TrackerParams, Double) -> TrackerParams,
    val group: String,
)

/** Fields the debug view can edit (booleans and index-bound values are listed separately there). */
val PARAM_FIELDS: List<ParamField> = listOf(
    ParamField("tauLocal", 0.01, { it.tauLocal }, { p, v -> p.copy(tauLocal = v) }, "tracking"),
    ParamField("tauMargin", 0.01, { it.tauMargin }, { p, v -> p.copy(tauMargin = v) }, "tracking"),
    ParamField("vmax", 5.0, { it.vmax }, { p, v -> p.copy(vmax = v) }, "tracking"),
    ParamField("localMinRadius", 4.0, { it.localMinRadius }, { p, v -> p.copy(localMinRadius = v) }, "tracking"),
    ParamField("localMaxRadius", 5.0, { it.localMaxRadius }, { p, v -> p.copy(localMaxRadius = v) }, "tracking"),
    ParamField("maxMiss", 1.0, { it.maxMiss.toDouble() }, { p, v -> p.copy(maxMiss = v.toInt().coerceAtLeast(1)) }, "tracking"),
    ParamField("alpha", 0.05, { it.alpha }, { p, v -> p.copy(alpha = v.coerceIn(0.05, 1.0)) }, "tracking"),
    ParamField("beta", 0.05, { it.beta }, { p, v -> p.copy(beta = v.coerceIn(0.0, 1.0)) }, "tracking"),
    ParamField("fastSpeedFrac", 0.01, { it.fastSpeedFrac }, { p, v -> p.copy(fastSpeedFrac = v) }, "tracking"),
    ParamField("tauRelocLocal", 0.01, { it.tauRelocLocal }, { p, v -> p.copy(tauRelocLocal = v) }, "relocation"),
    ParamField("tauRelocLocalMargin", 0.01, { it.tauRelocLocalMargin }, { p, v -> p.copy(tauRelocLocalMargin = v) }, "relocation"),
    ParamField("tauGlobal", 0.01, { it.tauGlobal }, { p, v -> p.copy(tauGlobal = v) }, "relocation"),
    ParamField("tauGlobalMargin", 0.01, { it.tauGlobalMargin }, { p, v -> p.copy(tauGlobalMargin = v) }, "relocation"),
    ParamField("relocLastRadius", 10.0, { it.relocLastRadius }, { p, v -> p.copy(relocLastRadius = v) }, "relocation"),
    ParamField("targetWin", 5.0, { it.targetWin }, { p, v -> p.copy(targetWin = v) }, "relocation"),
    ParamField("targetTopK", 1.0, { it.targetTopK.toDouble() }, { p, v -> p.copy(targetTopK = v.toInt().coerceAtLeast(1)) }, "relocation"),
    ParamField("relocMax", 1.0, { it.relocMax }, { p, v -> p.copy(relocMax = v) }, "relocation"),
    ParamField("confirmRadius", 5.0, { it.confirmRadius }, { p, v -> p.copy(confirmRadius = v) }, "relocation"),
    ParamField("loadFrac", 0.01, { it.loadFrac }, { p, v -> p.copy(loadFrac = v) }, "screen"),
    ParamField("loadBright", 2.0, { it.loadBright }, { p, v -> p.copy(loadBright = v) }, "screen"),
    ParamField("loadDark", 2.0, { it.loadDark }, { p, v -> p.copy(loadDark = v) }, "screen"),
    ParamField("ringThr", 0.02, { it.ringThr }, { p, v -> p.copy(ringThr = v) }, "screen"),
    ParamField("ringContrastThr", 0.5, { it.ringContrastThr }, { p, v -> p.copy(ringContrastThr = v) }, "screen"),
    ParamField("blackMedian", 1.0, { it.blackMedian }, { p, v -> p.copy(blackMedian = v) }, "screen"),
    ParamField("blackP90", 1.0, { it.blackP90 }, { p, v -> p.copy(blackP90 = v) }, "screen"),
    ParamField("blackEdgeFrac", 0.005, { it.blackEdgeFrac }, { p, v -> p.copy(blackEdgeFrac = v) }, "screen"),
    ParamField("chgThr", 0.1, { it.chgThr }, { p, v -> p.copy(chgThr = v) }, "screen"),
    ParamField("dTracking", 0.25, { it.dTracking }, { p, v -> p.copy(dTracking = v.coerceAtLeast(0.25)) }, "delays"),
    ParamField("dFast", 0.25, { it.dFast }, { p, v -> p.copy(dFast = v.coerceAtLeast(0.25)) }, "delays"),
    ParamField("dStill", 0.5, { it.dStill }, { p, v -> p.copy(dStill = v.coerceAtLeast(0.25)) }, "delays"),
    ParamField("dDomain", 0.5, { it.dDomain }, { p, v -> p.copy(dDomain = v.coerceAtLeast(0.25)) }, "delays"),
    ParamField("dHidden", 0.5, { it.dHidden }, { p, v -> p.copy(dHidden = v.coerceAtLeast(0.25)) }, "delays"),
    ParamField("dLost", 0.5, { it.dLost }, { p, v -> p.copy(dLost = v.coerceAtLeast(0.25)) }, "delays"),
    ParamField("tauLayerDrop", 0.01, { it.tauLayerDrop }, { p, v -> p.copy(tauLayerDrop = v) }, "layers"),
    ParamField("layerPeriod", 0.5, { it.layerPeriod }, { p, v -> p.copy(layerPeriod = v) }, "layers"),
    ParamField("entranceRadius", 5.0, { it.entranceRadius }, { p, v -> p.copy(entranceRadius = v) }, "layers"),
    ParamField("layerStopScore", 0.01, { it.layerStopScore }, { p, v -> p.copy(layerStopScore = v) }, "layers"),
    ParamField("tauFloor", 0.01, { it.tauFloor }, { p, v -> p.copy(tauFloor = v) }, "layers"),
    ParamField("floorMinVisible", 0.05, { it.floorMinVisible }, { p, v -> p.copy(floorMinVisible = v.coerceIn(0.1, 1.5)) }, "layers"),
    ParamField("tauFloorMargin", 0.01, { it.tauFloorMargin }, { p, v -> p.copy(tauFloorMargin = v) }, "layers"),
    ParamField("scaleStep", 0.01, { it.scaleStep }, { p, v -> p.copy(scaleStep = v.coerceIn(0.01, 0.2)) }, "zoom"),
    ParamField("scaleMin", 0.05, { it.scaleMin }, { p, v -> p.copy(scaleMin = v.coerceIn(0.2, 1.0)) }, "zoom"),
    ParamField("scaleMax", 0.1, { it.scaleMax }, { p, v -> p.copy(scaleMax = v.coerceIn(1.0, 6.0)) }, "zoom"),
    ParamField("scaleBias", 0.005, { it.scaleBias }, { p, v -> p.copy(scaleBias = v) }, "zoom"),
    ParamField("scaleDrop", 0.01, { it.scaleDrop }, { p, v -> p.copy(scaleDrop = v) }, "zoom"),
    ParamField("scaleSweepGain", 0.01, { it.scaleSweepGain }, { p, v -> p.copy(scaleSweepGain = v) }, "zoom"),
    ParamField("scaleStopScore", 0.01, { it.scaleStopScore }, { p, v -> p.copy(scaleStopScore = v) }, "zoom"),
    ParamField("northRectHalf", 0.005, { it.northRectHalf }, { p, v -> p.copy(northRectHalf = v) }, "mask"),
    ParamField("bannerX", 20.0, { it.bannerX.toDouble() }, { p, v -> p.copy(bannerX = v.toInt().coerceIn(0, 1800)) }, "banner"),
    ParamField("bannerY", 10.0, { it.bannerY.toDouble() }, { p, v -> p.copy(bannerY = v.toInt().coerceIn(0, 1000)) }, "banner"),
    ParamField("bannerW", 20.0, { it.bannerW.toDouble() }, { p, v -> p.copy(bannerW = v.toInt().coerceIn(100, 1920)) }, "banner"),
    ParamField("bannerH", 10.0, { it.bannerH.toDouble() }, { p, v -> p.copy(bannerH = v.toInt().coerceIn(40, 600)) }, "banner"),
    ParamField("zoomBorderMargin", 10.0, { it.zoomBorderMargin }, { p, v -> p.copy(zoomBorderMargin = v) }, "zoom"),
    ParamField("layerBias", 0.01, { it.layerBias }, { p, v -> p.copy(layerBias = v) }, "layers"),
    ParamField("rIn", 0.01, { it.rIn }, { p, v -> p.copy(rIn = v) }, "mask"),
    ParamField("rOut", 0.01, { it.rOut }, { p, v -> p.copy(rOut = v) }, "mask"),
)
