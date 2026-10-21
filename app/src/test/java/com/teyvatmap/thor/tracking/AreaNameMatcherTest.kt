package com.teyvatmap.thor.tracking

import com.teyvatmap.thor.data.store.PackParser
import com.teyvatmap.thor.tracking.banner.AreaName
import com.teyvatmap.thor.tracking.banner.AreaNameMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

/** Fuzzy matching of OCR text to HoYoLAB anchor names in Vietnamese (the game UI language of the real Thor), `lang=vi-vn`. */
class AreaNameMatcherTest {
    private val areas = PackParser.parseAnchors(
        PackParser.json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("anchors_vi_vn.json")!!.readBytes().decodeToString()), 2,
    )
    private val names = areas.map { AreaName(it.id, it.name) }
    private val matcher = AreaNameMatcher(names)

    private fun noMarks(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.replace('đ', 'd').replace('Đ', 'D')

    @Test fun normalisationIsDiacriticAndCaseInsensitive() {
        assertEquals("lanh nguyen volkodlak", AreaNameMatcher.normalize("Lãnh Nguyên Volkodlak"))
        assertEquals("dinh bang gia", AreaNameMatcher.normalize("Đỉnh Băng Giá"))
        assertEquals("lanh nguyen volkodlak", AreaNameMatcher.normalize("  LÃNH   NGUYÊN, Volkodlak! "))
        assertEquals("nha go tho san", AreaNameMatcher.normalize("Nhà Gỗ Thợ Săn"))
        assertEquals(0, AreaNameMatcher.levenshtein("abc", "abc")); assertEquals(1, AreaNameMatcher.levenshtein("abc", "abd")); assertEquals(3, AreaNameMatcher.levenshtein("", "abc"))
    }

    @Test fun realBannerTextsOfTheRecording() {
        // the in-game strings seen on the real big-map frames (HoYoLAB: 363 Lanh Nguyen Volkodlak, 371 Rankova Zorya, 367 Nha Go Tho San, 368 Hang Bai Ca Thuy Trieu, 369 Nha Hesperides)
        for ((text, id) in listOf("Lãnh Nguyên Volkodlak" to 363, "Rankova Zorya" to 371, "Nhà Gỗ Thợ Săn" to 367, "Hang Bài Ca Thủy Triều" to 368, "Nhà Hesperides" to 369, "Sretomorozsk" to 366)) {
            val m = matcher.match(text)
            assertNotNull(text, m); assertEquals(text, listOf(id), m!!.anchorIds); assertEquals(1.0, m.score, 1e-9)
        }
    }

    @Test fun toleratesOcrNoiseAndMissingToneMarks() {
        // Latin OCR drops the tone marks, confuses l/i and adds junk
        val cases = listOf(
            "Lanh Nguyen Volkodlak" to 363, "LANH NGUYEN VOLKODLAK" to 363, "Lanh Nguyen Volkodiak" to 363, "Lanh Nguyen Volkod1ak" to 363,
            "Lãnh Nguyên Volkodlak 100%" to 363, "Sretomorosk" to 366, "Sretomorozsk." to 366, "Rankova Zorva" to 371, "Hang Bai Ca Thuy Trieu" to 368,
            "Nha Go Tho San" to 367, "Dinh Bang Gia" to 364,
        )
        for ((text, id) in cases) {
            val m = matcher.match(text)
            assertNotNull(text, m)
            assertTrue("$text -> ${m!!.name} ${m.anchorIds}", id in m.anchorIds)
        }
    }

    @Test fun linesAreTriedAloneAndJoined() {
        assertEquals(listOf(363), matcher.match(listOf("Danh Sach Tin", "Lanh Nguyen", "Volkodlak"))!!.anchorIds)       // a wrapped name
        assertEquals(listOf(371), matcher.match(listOf("Khoi Luong Chan Bang - 20", "Rankova Zorya"))!!.anchorIds)
    }

    @Test fun textThatIsNotAnAreaDoesNotMatch() {
        for (t in listOf("Khối Lượng Chân Băng - 20", "Quản Lý Tạo Vật Chân Băng", "Trò Chuyện", "Theo Mục Tiêu", "Thưởng Rèn", "Độ thám hiểm 100%", "Phóng To", "UID", "", "ab", "Tầng 12 phòng 3")) {
            val m = matcher.match(t)
            assertNull("'$t' matched ${m?.name} (${m?.score})", m)
        }
    }

    @Test fun everyNameFindsItselfAndNoOtherFromOneEditOrStrippedMarks() {
        var checked = 0
        for (a in areas.filter { AreaNameMatcher.normalize(it.name).length >= 8 }) {
            val key = AreaNameMatcher.normalize(a.name)
            val same = areas.filter { AreaNameMatcher.normalize(it.name) == key }.map { it.id }
            for (variant in listOf(a.name, noMarks(a.name), noMarks(a.name).uppercase(), a.name.replaceFirst(a.name.last(), 'x'))) {
                val m = matcher.match(variant) ?: error("no match for '$variant' (${a.name})")
                assertTrue("'$variant' -> ${m.name} ${m.anchorIds}, expected one of $same", m.anchorIds.any { it in same })
            }
            checked++
        }
        assertTrue("checked $checked names", checked > 50)
    }
}
