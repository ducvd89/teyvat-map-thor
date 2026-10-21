package com.teyvatmap.thor.domain

import com.teyvatmap.thor.data.db.FloorEntity
import com.teyvatmap.thor.data.store.PackParser
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Up / Down stepping through the floors of one underground group (PLAN §5.1 "layer chooser"). The stack of a group is
 * the surface on top (id null), then its floors in the order of the HoYoLAB data (`point_groups.json`, `floors[]`,
 * listed top to bottom), falling back to ascending id when that file is not on the device.
 */
object FloorNav {
    /** Where the buttons lead: [upTo]/[downTo] are floor ids (null = surface) and only valid when the matching flag is true. */
    data class Nav(
        val groupId: Int,
        val index: Int,
        val count: Int,
        val canUp: Boolean,
        val canDown: Boolean,
        val upTo: Int?,
        val downTo: Int?,
    )

    /** Floors of [groupId], top first. */
    fun floorsOf(floors: List<FloorEntity>, groupId: Int, order: Map<Int, Int>): List<FloorEntity> =
        floors.filter { it.groupId == groupId }.sortedWith(compareBy({ order[it.id] ?: Int.MAX_VALUE }, { it.id }))

    /**
     * The navigation state for the view, or null when neither the shown floor nor the player is inside a group.
     * [activeFloorId] is the floor on display (null = surface), [playerGroupId] the group the player is in.
     */
    fun nav(floors: List<FloorEntity>, activeFloorId: Int?, playerGroupId: Int?, order: Map<Int, Int>): Nav? {
        val group = floors.firstOrNull { it.id == activeFloorId }?.groupId ?: playerGroupId ?: return null
        val stack = floorsOf(floors, group, order)
        if (stack.isEmpty()) return null
        val ids: List<Int?> = listOf<Int?>(null) + stack.map { it.id }
        val at = ids.indexOf(activeFloorId).takeIf { it >= 0 } ?: 0
        return Nav(
            groupId = group, index = at, count = stack.size,
            canUp = at > 0, canDown = at < ids.size - 1,
            upTo = ids.getOrNull(at - 1), downTo = ids.getOrNull(at + 1),
        )
    }

    /** Floor id -> position in the group's `floors[]` array of `point_groups.json`; empty when the file is missing. */
    fun readOrder(pointGroups: File): Map<Int, Int> = runCatching {
        if (pointGroups.length() == 0L) return emptyMap()
        val root = Json.parseToJsonElement(pointGroups.readText())
        val list = PackParser.unwrap(root, "list") as? JsonArray ?: return emptyMap()
        val out = HashMap<Int, Int>()
        for (g in list) for ((i, f) in (g.jsonObject["floors"] as? JsonArray).orEmpty().withIndex()) {
            f.jsonObject["id"]?.jsonPrimitive?.intOrNull?.let { out[it] = i }
        }
        out
    }.getOrDefault(emptyMap())
}
