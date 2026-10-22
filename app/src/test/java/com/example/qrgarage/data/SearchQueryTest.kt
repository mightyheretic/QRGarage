package com.example.qrgarage.data

import com.example.qrgarage.data.db.SearchParams
import com.example.qrgarage.data.db.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SEARCH-1 / SEARCH-2 */
class SearchQueryTest {

    @Test fun everyTermIsPrefixMatched() {
        assertEquals("m6* bol*", SearchQuery.toFtsMatch("M6 bol"))
    }

    @Test fun ftsOperatorsCannotBeInjected() {
        // FTS operators are upper-case only; lower-casing turns OR/NEAR into plain terms.
        assertEquals("a* or* b* c* near* 3* d*", SearchQuery.toFtsMatch("a OR \"b\" -c NEAR/3 d*"))
        assertEquals("drill* bits*", SearchQuery.toFtsMatch("drill-bits"))
    }

    @Test fun unicodeLettersAreKept() {
        assertEquals("schraubenzieher* größe*", SearchQuery.toFtsMatch("Schraubenzieher Größe"))
    }

    @Test fun blankInputHasNoMatch() {
        assertNull(SearchQuery.toFtsMatch("   "))
        assertNull(SearchQuery.toFtsMatch("!!"))
    }

    @Test fun queryUsesFtsNotLike() {
        val sql = SearchQuery.build(SearchParams(text = "bolt")).sql
        assertTrue(sql.contains("item_fts MATCH ?"))
        assertFalse(sql.contains("LIKE"))
    }

    @Test fun filtersAddClausesAndArgs() {
        val q = SearchQuery.build(
            SearchParams(text = "x", locationId = "L", categoryId = "C", tagId = "T", lowStockOnly = true, lentOutOnly = true)
        )
        assertEquals(4, q.argCount)
        assertTrue(q.sql.contains("WITH RECURSIVE"))
        assertTrue(q.sql.contains("i.quantity <= i.lowStockThreshold"))
        assertTrue(q.sql.contains("l.returnedAt IS NULL"))
    }

    @Test fun emptyParams() {
        assertTrue(SearchParams().isEmpty)
        assertFalse(SearchParams(lentOutOnly = true).isEmpty)
    }
}
