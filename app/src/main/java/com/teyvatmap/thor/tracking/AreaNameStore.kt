package com.teyvatmap.thor.tracking

import com.teyvatmap.thor.data.api.HoyolabApi
import com.teyvatmap.thor.data.db.AppDatabase
import com.teyvatmap.thor.data.db.AreaNameEntity
import com.teyvatmap.thor.data.store.PackParser
import com.teyvatmap.thor.tracking.banner.AreaName
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anchor (region) names in the GAME's language, from `map_anchor/list?lang=<game language>` (default vi-vn), kept in the
 * `area_names` table next to the app-language `areas` (PLAN §6.0 "Map data language"): the area-name banner is OCR'd in the game's
 * language, so it must be matched against names in that language.
 */
@Singleton
class AreaNameStore @Inject constructor(private val api: HoyolabApi, private val db: AppDatabase) {
    /** Fetches the anchors of the given maps (default: every imported map) in [lang] and replaces the stored names of that language. */
    suspend fun refresh(lang: String, mapIds: List<Int>? = null) {
        val ids = mapIds ?: db.maps().getAll().map { it.id }
        for (m in ids) {
            val areas = PackParser.parseAnchors(api.anchors(lang, m), m)
            if (areas.isEmpty()) continue
            db.regionZoom().deleteNames(m, lang)
            db.regionZoom().insertNames(areas.map { AreaNameEntity(m, it.id, lang, it.name) })
        }
    }

    /** Names of [mapId] in [lang]; fetched once when none are stored (needs the network the first time). */
    suspend fun names(mapId: Int, lang: String): List<AreaName> {
        if (db.regionZoom().nameCount(mapId, lang) == 0) runCatching { refresh(lang, listOf(mapId)) }
        return db.regionZoom().names(mapId, lang).map { AreaName(it.id, it.name) }
    }

}
