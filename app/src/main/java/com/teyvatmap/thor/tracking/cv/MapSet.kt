package com.teyvatmap.thor.tracking.cv

/**
 * Everything the tracker needs about one map: its matching index (through the [matcher]), layer zones, entrances,
 * teleport targets and the region zoom table. Tracking is local to one map at a time (the tracker's current [MapCtx]);
 * RELOCATING / LOST also search the others (PLAN §6.2).
 */
class MapCtx(
    val matcher: Matcher,
    val layers: LayerIndex,
    val targets: Targets,
    val entrances: EntranceIndex,
    val zoom: RegionZoomTable,
    /** True for contexts opened by a [MapProvider]: the tracker closes them, the engine owns the primary one. */
    private val owned: Boolean = false,
) : AutoCloseable {
    val index: CvIndex get() = matcher.index
    val geo: MapGeo get() = index.geo
    val mapId: Int get() = geo.mapId

    /** Area of the coarse map: the cost of a whole-map scan, used to scan small maps first. */
    val cost: Double by lazy { levelGeom(geo, -3).let { it.w.toDouble() * it.h } }

    override fun close() {
        if (owned) { matcher.close(); index.close() }
    }

    companion object {
        fun of(index: CvIndex, params: TrackerParams, zoom: RegionZoomTable): MapCtx =
            MapCtx(Matcher(index, params), index.layers, index.targets, index.entrances, zoom, owned = true)
    }
}

/** The maps besides the tracker's primary one: [ids] of the built indexes, each opened lazily on first use (tracker thread). */
interface MapProvider {
    val ids: List<Int>
    fun load(mapId: Int): MapCtx?
}

/**
 * Rules of a big-map position fix (PLAN §6.0), pure logic: the fix must come from the player arrow (an arrow-less view
 * is only a hint) and, unless a loading screen made the old position meaningless, must lie near the position the
 * tracker already has (the big map can be panned anywhere without the player moving).
 */
object BigFixRules {
    fun accept(
        arrowFound: Boolean,
        state: TrackState,
        hiddenRet: String,
        hasPos: Boolean,
        sameMap: Boolean,
        distZ0: Double,
        maxJump: Double,
    ): Boolean {
        if (!arrowFound) return false
        // no trustworthy old position: after a loading screen (RELOCATING), lost, nothing known yet
        if (!hasPos || state == TrackState.RELOCATING || state == TrackState.LOST || state == TrackState.OFF) return true
        if (state == TrackState.HIDDEN && hiddenRet != "track") return true
        return sameMap && distZ0 <= maxJump
    }
}
