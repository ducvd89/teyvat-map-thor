package com.teyvatmap.thor.domain

/**
 * Names of the `area_id` values HoYoLAB attaches to points on the main map (the nations). The API only names the
 * sub-regions (anchors), so the nation names are fixed here; unknown ids fall back to "Region N".
 */
object AreaNames {
    private val teyvat = mapOf(
        1 to "Mondstadt", 2 to "Liyue", 3 to "Inazuma", 4 to "Sumeru", 8 to "Fontaine", 11 to "Natlan",
        13 to "Nod-Krai", 16 to "Snezhnaya",
    )

    fun name(areaId: Int, mapName: String?): String = teyvat[areaId]
        ?: if (areaId == 0) "Elsewhere" else mapName?.removeSuffix(" Interactive Map") ?: "Region $areaId"
}
