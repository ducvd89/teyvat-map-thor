package com.teyvatmap.thor.domain

import java.util.concurrent.TimeUnit

/**
 * Respawn rules (PLAN §5.4). HoYoLAB reports `rec_refresh_hour = 0` for every label, so the defaults live here.
 * Defaults are keyed by label id (language independent): the top-level group of a leaf decides, with a few leaf
 * overrides. A value of 0 means "never respawns". The user's overrides live in the `respawn_rules` table and win.
 */
object RespawnDefaults {
    // Top-level groups (HoYoLAB label ids, parent_id = 0).
    const val GROUP_SPECIALTIES = 10
    const val GROUP_ORES = 11
    const val GROUP_ANIMALS = 273
    const val GROUP_WOOD = 173
    const val GROUP_MATERIALS = 60

    val CRYSTALFLIES = setOf(281, 277, 315, 298, 393, 513, 624)

    const val HOURS_ORE = 72
    const val HOURS_SPECIALTY = 48
    const val HOURS_WOOD = 48
    const val HOURS_MATERIAL = 24
    const val HOURS_ANIMAL = 24
    const val HOURS_CRYSTALFLY = 12

    /** Default respawn hours of a leaf label; [groupId] is its top-level group (or its parent). */
    fun hoursFor(labelId: Int, groupId: Int): Int = when {
        labelId in CRYSTALFLIES -> HOURS_CRYSTALFLY
        groupId == GROUP_ORES -> HOURS_ORE
        groupId == GROUP_SPECIALTIES -> HOURS_SPECIALTY
        groupId == GROUP_ANIMALS -> HOURS_ANIMAL
        groupId == GROUP_WOOD -> HOURS_WOOD
        groupId == GROUP_MATERIALS -> HOURS_MATERIAL
        else -> 0
    }
}

/** Pure respawn arithmetic, kept free of Android types for unit tests. */
object RespawnMath {
    private val HOUR_MS = TimeUnit.HOURS.toMillis(1)

    /** Epoch millis at which an item found at [foundAt] is available again, or null if it never respawns. */
    fun expiresAt(foundAt: Long, hours: Int): Long? = if (hours <= 0) null else foundAt + hours * HOUR_MS

    fun isExpired(foundAt: Long, hours: Int, now: Long): Boolean =
        expiresAt(foundAt, hours)?.let { now >= it } ?: false

    /** Milliseconds until respawn (0 once expired), or null if it never respawns. */
    fun remainingMs(foundAt: Long, hours: Int, now: Long): Long? =
        expiresAt(foundAt, hours)?.let { (it - now).coerceAtLeast(0) }

    /** "2d 3h", "5h 12m", "42m", "<1m". */
    fun formatRemaining(ms: Long): String {
        val totalMin = TimeUnit.MILLISECONDS.toMinutes(ms.coerceAtLeast(0))
        val d = totalMin / (24 * 60)
        val h = (totalMin / 60) % 24
        val m = totalMin % 60
        return when {
            d > 0 -> if (h > 0) "${d}d ${h}h" else "${d}d"
            h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
            m > 0 -> "${m}m"
            else -> "<1m"
        }
    }

    /** Hours a stepper offers: never, 6 h, 12 h, 1 d, 2 d, 3 d, 4 d, 1 week. */
    val STEPS = listOf(0, 6, 12, 24, 48, 72, 96, 168)

    fun stepUp(h: Int): Int = STEPS.firstOrNull { it > h } ?: STEPS.last()
    fun stepDown(h: Int): Int = STEPS.lastOrNull { it < h } ?: STEPS.first()
    fun formatHours(h: Int): String = when {
        h <= 0 -> "never"
        h % 24 == 0 -> "${h / 24} d"
        else -> "$h h"
    }
}
