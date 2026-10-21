package com.teyvatmap.thor.domain

/** UI model of one category (label) with its direct children, for the filter drawer. */
data class LabelNode(
    val id: Int,
    val name: String,
    val iconPath: String?,
    val count: Int,
    val children: List<LabelNode>,
) {
    val isLeaf: Boolean get() = children.isEmpty()
    fun leafIds(): List<Int> = if (isLeaf) listOf(id) else children.flatMap { it.leafIds() }
}

data class SearchResults(
    val labels: List<LabelHit> = emptyList(),
    val points: List<PointHit> = emptyList(),
) {
    val isEmpty: Boolean get() = labels.isEmpty() && points.isEmpty()
}

data class LabelHit(val id: Int, val name: String, val iconPath: String?, val count: Int)

data class PointHit(
    val pointId: Long,
    val mapId: Int,
    val labelId: Int,
    val labelName: String,
    val iconPath: String?,
    val snippet: String,
)
