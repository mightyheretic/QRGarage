package com.example.qrgarage.data.db

import androidx.sqlite.db.SimpleSQLiteQuery

/** SEARCH-2 filters. Empty [text] with no filters means "no search". */
data class SearchParams(
    val text: String = "",
    val locationId: String? = null,
    val categoryId: String? = null,
    val tagId: String? = null,
    val lowStockOnly: Boolean = false,
    val lentOutOnly: Boolean = false,
) {
    val hasFilters: Boolean
        get() = locationId != null || categoryId != null || tagId != null || lowStockOnly || lentOutOnly

    val isEmpty: Boolean get() = text.isBlank() && !hasFilters
}

object SearchQuery {

    /**
     * SEARCH-1: turns free text into an FTS4 MATCH expression with prefix matching on every
     * term, e.g. `"M6 bol"` → `m6* bol*` (implicit AND). Punctuation is treated as a separator
     * and everything is lower-cased (FTS operators such as OR/NEAR are upper-case only), so user
     * input can never inject FTS syntax. Returns null when nothing searchable remains.
     */
    fun toFtsMatch(text: String): String? {
        val terms = text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .map { "$it*" }
        return terms.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    fun build(params: SearchParams): SimpleSQLiteQuery {
        val where = mutableListOf("i.deletedAt IS NULL")
        val args = mutableListOf<Any>()

        toFtsMatch(params.text)?.let {
            where += "i.id IN (SELECT itemId FROM item_fts WHERE item_fts MATCH ?)"
            args += it
        }
        params.locationId?.let {
            where += """i.locationId IN (
                WITH RECURSIVE sub(id) AS (
                    SELECT ?
                    UNION
                    SELECT l.id FROM locations l JOIN sub ON l.parentId = sub.id WHERE l.deletedAt IS NULL
                ) SELECT id FROM sub)"""
            args += it
        }
        params.categoryId?.let {
            where += "i.categoryId = ?"
            args += it
        }
        params.tagId?.let {
            where += "EXISTS(SELECT 1 FROM item_tags it WHERE it.itemId = i.id AND it.tagId = ?)"
            args += it
        }
        if (params.lowStockOnly) {
            where += "i.quantity IS NOT NULL AND i.lowStockThreshold IS NOT NULL AND i.quantity <= i.lowStockThreshold"
        }
        if (params.lentOutOnly) {
            where += "EXISTS(SELECT 1 FROM loans l WHERE l.itemId = i.id AND l.returnedAt IS NULL AND l.deletedAt IS NULL)"
        }

        val sql = "SELECT $ITEM_ROW_COLUMNS FROM items i WHERE ${where.joinToString(" AND ")} " +
            "ORDER BY i.name COLLATE NOCASE, i.id"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
