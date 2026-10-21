package com.teyvatmap.thor.sync

/**
 * Per-key last-writer-wins merge of two [MarksDoc]s. Every winner choice is a total order on the entry's content
 * (time first, then a deterministic tie-break), so the merge is commutative, associative and idempotent: devices
 * converge no matter in which order or how often they merge.
 */
object SyncEngine {

    fun merge(a: MarksDoc, b: MarksDoc): MarksDoc = MarksDoc(
        schema = maxOf(a.schema, b.schema),
        device = if (a.device >= b.device) a.device else b.device,
        marks = mergeMaps(a.marks, b.marks, ::mergeMark),
        pins = mergeMaps(a.pins, b.pins, ::mergePin),
        respawn = mergeMaps(a.respawn, b.respawn, ::mergeRespawn),
    )

    private fun <V> mergeMaps(a: Map<String, V>, b: Map<String, V>, pick: (V, V) -> V): Map<String, V> {
        val out = LinkedHashMap<String, V>(maxOf(a.size, b.size))
        for ((k, v) in a) out[k] = b[k]?.let { pick(v, it) } ?: v
        for ((k, v) in b) if (k !in a) out[k] = v
        return out
    }

    /** State and note are independent registers: the state follows `t`, the note follows `nt`. */
    fun mergeMark(a: MarkEntry, b: MarkEntry): MarkEntry {
        val state = if (compareState(a, b) >= 0) a else b
        val note = if (compareNote(a, b) >= 0) a else b
        return MarkEntry(s = state.s, t = state.t, n = note.n, nt = note.nt)
    }

    private fun compareState(a: MarkEntry, b: MarkEntry): Int =
        compareValuesBy(a, b, { it.t }, { it.s })

    private fun compareNote(a: MarkEntry, b: MarkEntry): Int =
        compareValuesBy(a, b, { it.nt }, { it.n ?: "" }, { it.n != null })

    fun mergePin(a: PinEntry, b: PinEntry): PinEntry =
        if (compareValuesBy(a, b, { it.t }, { it.del }, { it.title }, { it.note }, { it.x }, { it.y }, { it.mapId }, { it.floorId ?: Int.MIN_VALUE }, { it.color }) >= 0) a else b

    fun mergeRespawn(a: RespawnEntry, b: RespawnEntry): RespawnEntry =
        if (compareValuesBy(a, b, { it.t }, { it.h }) >= 0) a else b

    /** Entries of [merged] that differ from [base]: what has to be written to the local database. */
    fun diff(base: MarksDoc, merged: MarksDoc): MarksDoc = MarksDoc(
        schema = merged.schema, device = merged.device,
        marks = merged.marks.filter { (k, v) -> base.marks[k] != v },
        pins = merged.pins.filter { (k, v) -> base.pins[k] != v },
        respawn = merged.respawn.filter { (k, v) -> base.respawn[k] != v },
    )

    fun isEmpty(d: MarksDoc) = d.marks.isEmpty() && d.pins.isEmpty() && d.respawn.isEmpty()

    /** Content equality ignoring the `device` field. */
    fun sameContent(a: MarksDoc, b: MarksDoc) = a.marks == b.marks && a.pins == b.pins && a.respawn == b.respawn
}
