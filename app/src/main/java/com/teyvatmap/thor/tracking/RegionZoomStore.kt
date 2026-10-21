package com.teyvatmap.thor.tracking

import com.teyvatmap.thor.data.db.AppDatabase
import com.teyvatmap.thor.data.db.RegionZoomEntity
import com.teyvatmap.thor.tracking.cv.RegionZoomTable
import com.teyvatmap.thor.tracking.cv.ZoomAnchor
import com.teyvatmap.thor.tracking.cv.ZoomEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The maps besides Teyvat (Enkanomiya, The Chasm, Sacred Mountain) are small and often have no (or few) region anchors: one
 * anchor covering the whole map, id `-mapId`, lets the zoom be learned on lock like on Teyvat (a smaller real anchor wins).
 */
internal fun catchAll(mapId: Int): List<ZoomAnchor> =
    if (mapId == 2) emptyList() else listOf(ZoomAnchor(-mapId, 0, "map $mapId", -1e6, -1e6, 1e6, 1e6))

/** Room-backed [RegionZoomTable] (table `region_zoom`), seeded with the two zoom values measured on the real Thor. */
@Singleton
class RegionZoomStore @Inject constructor(private val db: AppDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The table of [mapId]: anchors from the `areas` table, entries from `region_zoom`; learned entries are written back. */
    suspend fun table(mapId: Int): RegionZoomTable {
        if (mapId == 2) {
            db.regionZoom().insertIfMissing(RegionZoomTable.SEEDS.map { (id, s) -> RegionZoomEntity(mapId, id, s, 30, 0) })
        }
        val anchors = db.areas().getForMap(mapId).map { ZoomAnchor(it.id, it.parentId, it.name, it.lX, it.lY, it.rX, it.rY) } + catchAll(mapId)
        val entries = db.regionZoom().forMap(mapId).map { ZoomEntry(it.anchorId, it.scale, it.samples, it.updatedAt) }
        return RegionZoomTable(anchors, entries, onChange = { e ->
            scope.launch { db.regionZoom().upsert(RegionZoomEntity(mapId, e.anchorId, e.scale, e.samples, e.updatedAt)) }
        })
    }
}
