package com.teyvatmap.thor.domain

import com.teyvatmap.thor.data.MarkChange
import com.teyvatmap.thor.data.db.UserMarkEntity

/**
 * Pure planning of the "Mark all found" / "Clear all found" actions of the filter drawer (no database access, so it
 * is unit-tested). Every produced row carries a fresh `updatedAt` that is strictly newer than the row it replaces,
 * which is what makes the last-writer-wins sync (PLAN §7) propagate the bulk change to the other devices.
 */
object BulkMark {
    /**
     * Changes needed to make every point of [pointIds] FOUND ([found] = true) or not found.
     * Points that are HIDDEN are left alone in both directions (hiding is a separate user decision); points already
     * in the target state produce no change. Notes are kept.
     */
    fun plan(
        pointIds: Collection<Long>,
        existing: Map<Long, UserMarkEntity>,
        found: Boolean,
        now: Long,
        deviceId: String,
    ): List<MarkChange> {
        val out = ArrayList<MarkChange>()
        for (id in pointIds.distinct()) {
            val before = existing[id]
            val cur = MarkState.fromDb(before?.state)
            if (cur == MarkState.HIDDEN) continue
            val target = if (found) MarkState.FOUND else MarkState.NONE
            if (cur == target) continue
            out += MarkChange(id, before, restamp(id, before, target, now, deviceId))
        }
        return out
    }

    /** Rows that put every changed point back to what it was before [changes] (a *new* change that wins when synced). */
    fun undoPlan(changes: List<MarkChange>, current: Map<Long, UserMarkEntity>, now: Long, deviceId: String): List<UserMarkEntity> =
        changes.map { c ->
            val cur = current[c.pointId] ?: c.after
            // Keep a note edited since (the note is an independent register); only the state goes back.
            restamp(c.pointId, cur, MarkState.fromDb(c.before?.state), now, deviceId)
        }

    private fun restamp(id: Long, base: UserMarkEntity?, state: MarkState, now: Long, deviceId: String) = UserMarkEntity(
        pointId = id, state = state.db, updatedAt = maxOf(now, (base?.updatedAt ?: 0) + 1), deviceId = deviceId,
        note = base?.note, noteUpdatedAt = base?.noteUpdatedAt ?: 0,
    )
}
