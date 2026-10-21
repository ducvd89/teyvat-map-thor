package com.teyvatmap.thor.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SyncEngineTest {
    private fun mark(s: String, t: Long, n: String? = null, nt: Long = 0) = MarkEntry(s, t, n, nt)
    private fun doc(marks: Map<String, MarkEntry> = emptyMap(), pins: Map<String, PinEntry> = emptyMap(), respawn: Map<String, RespawnEntry> = emptyMap()) =
        MarksDoc(device = "d", marks = marks, pins = pins, respawn = respawn)

    // ------------------------------------------------------------ generators (seeded, "property style")
    private fun randomMark(r: Random) = MarkEntry(
        s = listOf("F", "H", "N").random(r), t = r.nextLong(0, 6),
        n = listOf(null, "a", "b", "").random(r), nt = r.nextLong(0, 6),
    )

    private fun randomPin(r: Random) = PinEntry(
        mapId = r.nextInt(2), x = r.nextInt(3).toDouble(), y = r.nextInt(3).toDouble(), floorId = listOf(null, 1).random(r),
        title = listOf("p", "q").random(r), note = listOf("", "n").random(r), color = r.nextInt(3), t = r.nextLong(0, 6), del = r.nextBoolean(),
    )

    private fun randomDoc(r: Random): MarksDoc {
        val keys = listOf("1", "2", "3", "4")
        return MarksDoc(
            device = listOf("a", "b", "c").random(r),
            marks = keys.filter { r.nextInt(3) > 0 }.associateWith { randomMark(r) },
            pins = listOf("u1", "u2", "u3").filter { r.nextInt(3) > 0 }.associateWith { randomPin(r) },
            respawn = listOf("10", "11").filter { r.nextBoolean() }.associateWith { RespawnEntry(r.nextInt(4) * 24, r.nextLong(0, 6)) },
        )
    }

    @Test fun mergeIsCommutative() {
        val r = Random(1)
        repeat(500) {
            val a = randomDoc(r); val b = randomDoc(r)
            assertEquals(SyncEngine.merge(a, b), SyncEngine.merge(b, a))
        }
    }

    @Test fun mergeIsIdempotent() {
        val r = Random(2)
        repeat(500) {
            val a = randomDoc(r); val b = randomDoc(r)
            val m = SyncEngine.merge(a, b)
            assertEquals(m, SyncEngine.merge(m, m))
            assertEquals(m, SyncEngine.merge(m, a))
            assertEquals(m, SyncEngine.merge(m, b))
            assertEquals(a, SyncEngine.merge(a, a))
        }
    }

    @Test fun mergeIsAssociative() {
        val r = Random(3)
        repeat(500) {
            val a = randomDoc(r); val b = randomDoc(r); val c = randomDoc(r)
            assertEquals(
                SyncEngine.merge(SyncEngine.merge(a, b), c),
                SyncEngine.merge(a, SyncEngine.merge(b, c)),
            )
        }
    }

    @Test fun threeDevicesConvergeInAnyOrder() {
        val r = Random(4)
        repeat(200) {
            val ds = List(3) { randomDoc(r) }
            val orders = listOf(listOf(0, 1, 2), listOf(2, 1, 0), listOf(1, 0, 2))
            val results = orders.map { o -> o.map { ds[it] }.reduce(SyncEngine::merge) }
            assertEquals(results[0], results[1]); assertEquals(results[1], results[2])
        }
    }

    // --------------------------------------------------------------------------------- semantics
    @Test fun newerTimestampWins() {
        val a = doc(marks = mapOf("1" to mark("F", 100)))
        val b = doc(marks = mapOf("1" to mark("N", 200)))
        assertEquals("N", SyncEngine.merge(a, b).marks.getValue("1").s)
    }

    @Test fun unmarkingSyncsAsEntryNotAbsence() {
        val local = doc(marks = mapOf("1" to mark("N", 300)))
        val remote = doc(marks = mapOf("1" to mark("F", 200)))
        val m = SyncEngine.merge(local, remote)
        assertEquals("N", m.marks.getValue("1").s)
        assertEquals(1, SyncEngine.diff(remote, m).marks.size) // remote must learn about the un-mark
        assertTrue(SyncEngine.diff(local, m).marks.isEmpty())  // local has nothing new
    }

    @Test fun missingKeysAreUnioned() {
        val m = SyncEngine.merge(doc(marks = mapOf("1" to mark("F", 1))), doc(marks = mapOf("2" to mark("H", 2))))
        assertEquals(setOf("1", "2"), m.marks.keys)
    }

    @Test fun noteAndStateMergeIndependently() {
        val a = doc(marks = mapOf("1" to mark("F", 100, "from a", 10)))
        val b = doc(marks = mapOf("1" to mark("N", 200, "from b", 5)))
        val e = SyncEngine.merge(a, b).marks.getValue("1")
        assertEquals("N", e.s); assertEquals(200L, e.t)          // state: b is newer
        assertEquals("from a", e.n); assertEquals(10L, e.nt)     // note: a is newer
    }

    @Test fun pinTombstoneWinsOverOlderEdit() {
        val live = PinEntry(2, 1.0, 2.0, null, "x", "", 0, t = 100, del = false)
        val dead = live.copy(t = 150, del = true)
        assertTrue(SyncEngine.merge(doc(pins = mapOf("u" to live)), doc(pins = mapOf("u" to dead))).pins.getValue("u").del)
        val revived = live.copy(t = 200, title = "again")
        val m = SyncEngine.merge(doc(pins = mapOf("u" to dead)), doc(pins = mapOf("u" to revived)))
        assertEquals("again", m.pins.getValue("u").title)
    }

    @Test fun respawnOverrideLww() {
        val m = SyncEngine.merge(
            doc(respawn = mapOf("10" to RespawnEntry(24, 1))), doc(respawn = mapOf("10" to RespawnEntry(72, 2))),
        )
        assertEquals(72, m.respawn.getValue("10").h)
    }

    @Test fun sameTimestampTieBreakIsDeterministic() {
        val a = doc(marks = mapOf("1" to mark("F", 100)))
        val b = doc(marks = mapOf("1" to mark("H", 100)))
        assertEquals(SyncEngine.merge(a, b), SyncEngine.merge(b, a))
    }

    @Test fun jsonRoundTripAndGzip() {
        val d = MarksDoc(
            device = "thor-1",
            marks = mapOf("105872" to MarkEntry("F", 1792425600123, "behind the tree", 5)),
            pins = mapOf("u" to PinEntry(2, -4274.5, 2529.5, null, "Camp", "note", 0xFF00FF00.toInt(), 99)),
            respawn = mapOf("15" to RespawnEntry(72, 7)),
        )
        assertEquals(d, MarksJson.decode(MarksJson.encode(d)))
        assertEquals(d, MarksJson.decodeBytes(MarksJson.gzip(d)))
        assertEquals(d, MarksJson.decodeBytes(MarksJson.encode(d).toByteArray()))
    }
}
