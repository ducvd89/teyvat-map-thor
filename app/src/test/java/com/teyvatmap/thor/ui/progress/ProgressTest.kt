package com.teyvatmap.thor.ui.progress

import com.teyvatmap.thor.data.db.ProgressRow
import com.teyvatmap.thor.domain.LabelNode
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressTest {
    private val groups = listOf(
        LabelNode(13, "Chests", null, 10, emptyList()),
        LabelNode(4, "Special Items", null, 5, emptyList()),
    )

    @Test fun buildsAreaByCategoryTable() {
        val rows = listOf(
            ProgressRow(areaId = 1, groupId = 4, total = 66, found = 6),
            ProgressRow(areaId = 1, groupId = 13, total = 100, found = 40),
            ProgressRow(areaId = 2, groupId = 13, total = 300, found = 30),
            ProgressRow(areaId = 2, groupId = 99, total = 7, found = 0), // unknown group: ignored
        )
        val t = buildProgress(rows, groups, "Teyvat Interactive Map")
        assertEquals(listOf("Liyue", "Mondstadt"), t.map { it.name }) // biggest area first
        val mond = t.first { it.areaId == 1 }
        assertEquals(46, mond.found); assertEquals(166, mond.total)
        assertEquals(listOf("Chests", "Special Items"), mond.groups.map { it.name }) // tree order
        assertEquals(40, mond.groups.first().found)
    }
}
