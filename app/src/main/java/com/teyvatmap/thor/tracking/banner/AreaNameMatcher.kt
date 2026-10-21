package com.teyvatmap.thor.tracking.banner

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** An area (HoYoLAB anchor) name in the game's language. */
data class AreaName(val anchorId: Int, val name: String)

/** A banner text matched to anchors: [anchorIds] are all anchors whose normalised name equals the best match (usually one). */
data class AreaMatch(val name: String, val anchorIds: List<Int>, val score: Double, val line: String)

/**
 * Fuzzy matching of OCR text to area names (PLAN §6.0): diacritic-insensitive (the game UI is Vietnamese, OCR with the Latin
 * model drops or garbles the tone marks), case-insensitive, Levenshtein-based. Pure Kotlin.
 *
 * Score = 1 - distance / length of the shorter side over the best window of the OCR line (so "Lanh Nguyen Volkodlak 100%" or
 * a banner line with a decoration next to it still matches). Short names (< 5 letters) must match exactly: one wrong letter in
 * "Lan" is not a different place but noise.
 */
class AreaNameMatcher(names: List<AreaName>, private val minScore: Double = 0.80) {
    private class Entry(val anchorId: Int, val name: String, val key: String)

    private val entries = names.filter { it.name.isNotBlank() }.map { Entry(it.anchorId, it.name, normalize(it.name)) }.filter { it.key.length >= 3 }

    val size: Int get() = entries.size

    /** Best match of the text lines of one OCR result, or null. Adjacent lines are also tried joined (long names wrap onto two lines). */
    fun match(lines: List<String>): AreaMatch? {
        if (entries.isEmpty()) return null
        val cands = ArrayList<String>()
        for (l in lines) cands += l
        for (i in 0 until lines.size - 1) cands += lines[i] + " " + lines[i + 1]
        var best: AreaMatch? = null
        var bestEntry: Entry? = null
        for (raw in cands) {
            val key = normalize(raw)
            if (key.length < 3) continue
            for (e in entries) {
                val s = similarity(e.key, key)
                if (s < minScore) continue
                // prefer a higher score, then the longer (more specific) name
                if (best == null || s > best.score + 1e-9 || (Math.abs(s - best.score) <= 1e-9 && e.key.length > bestEntry!!.key.length)) {
                    best = AreaMatch(e.name, emptyList(), s, raw); bestEntry = e
                }
            }
        }
        val b = best ?: return null
        val ids = entries.filter { it.key == bestEntry!!.key }.map { it.anchorId }.distinct()
        return b.copy(anchorIds = ids)
    }

    fun match(text: String): AreaMatch? = match(text.lines())

    companion object {
        /** Lowercase, diacritics removed (also the Vietnamese d-bar), only letters and digits, single spaces. */
        fun normalize(s: String): String {
            val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            val sb = StringBuilder(d.length)
            var space = false
            for (c in d) {
                val ch = when (c) { 'đ' -> 'd'; 'ø' -> 'o'; 'ł' -> 'l'; else -> c }
                if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
                if (ch.isLetterOrDigit()) { sb.append(ch); space = false } else if (!space && sb.isNotEmpty()) { sb.append(' '); space = true }
            }
            return sb.toString().trim()
        }

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }

        /** How well the (normalised) [name] occurs in [text]: best window of the text, 1.0 = identical. */
        fun similarity(name: String, text: String): Double {
            val n = name.length
            if (n == 0 || text.isEmpty()) return 0.0
            if (text.contains(name)) return 1.0
            val exactOnly = n < 5
            if (exactOnly) return if (text == name) 1.0 else 0.0
            var best = 1.0 - levenshtein(name, text).toDouble() / max(n, text.length)
            // windows of the text of about the name's length (the name inside a longer line)
            if (text.length > n) {
                for (w in max(3, n - 2)..(n + 2)) {
                    if (w > text.length) break
                    var i = 0
                    while (i + w <= text.length) {
                        val d = levenshtein(name, text.substring(i, i + w))
                        best = max(best, 1.0 - d.toDouble() / max(n, w))
                        i += 1
                    }
                }
            }
            return best
        }
    }
}
