package com.teyvatmap.thor.domain

/**
 * State of a point for the user. `NONE` is stored too (instead of deleting the row) so that un-marking is a
 * timestamped change that syncs (PLAN §7).
 */
enum class MarkState(val db: String, val code: String) {
    NONE("NONE", "N"),
    FOUND("FOUND", "F"),
    HIDDEN("HIDDEN", "H");

    companion object {
        fun fromDb(s: String?): MarkState = entries.firstOrNull { it.db == s } ?: NONE
        fun fromCode(s: String?): MarkState = entries.firstOrNull { it.code == s } ?: NONE
    }
}
