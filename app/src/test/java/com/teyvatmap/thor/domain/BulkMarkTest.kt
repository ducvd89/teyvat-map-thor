package com.teyvatmap.thor.domain

import com.teyvatmap.thor.data.db.UserMarkEntity
import com.teyvatmap.thor.sync.MarkEntry
import com.teyvatmap.thor.sync.SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkMarkTest {
    private fun row(id: Long, s: MarkState, t: Long, note: String? = null) =
        UserMarkEntity(id, s.db, t, "other", note, if (note != null) 5 else 0)

    private val existing = mapOf(
        1L to row(1, MarkState.FOUND, 100),
        2L to row(2, MarkState.HIDDEN, 100),
        3L to row(3, MarkState.NONE, 100, note = "near the tree"),
    )

    @Test fun markAllSkipsFoundAndHiddenAndKeepsNotes() {
        val plan = BulkMark.plan(listOf(1, 2, 3, 4, 4), existing, found = true, now = 1000, deviceId = "me")
        assertEquals(listOf(3L, 4L), plan.map { it.pointId })            // 1 already found, 2 hidden, 4 listed twice
        val p3 = plan[0].after
        assertEquals(MarkState.FOUND.db, p3.state); assertEquals(1000L, p3.updatedAt)
        assertEquals("near the tree", p3.note); assertEquals(5L, p3.noteUpdatedAt); assertEquals("me", p3.deviceId)
        assertEquals(null, plan[1].before)
    }

    @Test fun clearOnlyTouchesFound() {
        val plan = BulkMark.plan(listOf(1, 2, 3, 4), existing, found = false, now = 1000, deviceId = "me")
        assertEquals(listOf(1L), plan.map { it.pointId })
        assertEquals(MarkState.NONE.db, plan[0].after.state)
    }

    @Test fun stampIsStrictlyNewerEvenWithClockSkew() {
        val plan = BulkMark.plan(listOf(3), mapOf(3L to row(3, MarkState.NONE, 5000)), true, now = 1000, deviceId = "me")
        assertEquals(5001L, plan[0].after.updatedAt)
    }

    @Test fun undoRestoresPreviousStateWithNewTimestamp() {
        val plan = BulkMark.plan(listOf(1, 3, 4), existing, found = false, now = 1000, deviceId = "me") // only 1 changes
        val applied = plan.associate { it.pointId to it.after }
        val back = BulkMark.undoPlan(plan, applied, now = 2000, deviceId = "me")
        assertEquals(MarkState.FOUND.db, back.single().state)
        assertEquals(2000L, back.single().updatedAt)

        val marked = BulkMark.plan(listOf(3, 4), existing, found = true, now = 1000, deviceId = "me")
        val undone = BulkMark.undoPlan(marked, marked.associate { it.pointId to it.after }, now = 2000, deviceId = "me")
        assertEquals(listOf(MarkState.NONE.db, MarkState.NONE.db), undone.map { it.state })   // 4 had no row -> NONE
        assertTrue(undone.all { it.updatedAt == 2000L })
        assertEquals("near the tree", undone.first { it.pointId == 3L }.note)
    }

    @Test fun bulkChangeWinsOverOlderRemoteAndUndoWinsOverBulk() {
        fun entry(r: UserMarkEntity) = MarkEntry(MarkState.fromDb(r.state).code, r.updatedAt, r.note, r.noteUpdatedAt)
        val remoteOld = entry(row(3, MarkState.NONE, 900))
        val plan = BulkMark.plan(listOf(3), existing, found = true, now = 1000, deviceId = "me")
        val merged = SyncEngine.mergeMark(remoteOld, entry(plan[0].after))
        assertEquals("F", merged.s)                       // the bulk mark propagates
        val undone = BulkMark.undoPlan(plan, mapOf(3L to plan[0].after), now = 2000, deviceId = "me").single()
        assertEquals("N", SyncEngine.mergeMark(entry(plan[0].after), entry(undone)).s)   // and the undo propagates too
    }
}
