package com.teyvatmap.thor.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** Two devices converge through the merge the sync round performs (no Android / Drive needed). */
class DriveSyncRoundTest {
    @Test fun twoDevicesConvergeAndUnmarkPropagates() {
        var remote = MarksDoc.EMPTY
        var a = MarksDoc(device = "a", marks = mapOf("1" to MarkEntry("F", 100), "2" to MarkEntry("F", 100)))
        var b = MarksDoc(device = "b", marks = mapOf("2" to MarkEntry("N", 200), "3" to MarkEntry("H", 150)))
        fun round(local: MarksDoc): MarksDoc {
            val merged = SyncEngine.merge(local, remote)
            remote = SyncEngine.merge(remote, merged)
            return merged
        }
        a = round(a); b = round(b); a = round(a)
        assertEquals(b.marks, a.marks)
        assertEquals("N", a.marks.getValue("2").s)
        assertEquals(setOf("1", "2", "3"), a.marks.keys)
    }
}
