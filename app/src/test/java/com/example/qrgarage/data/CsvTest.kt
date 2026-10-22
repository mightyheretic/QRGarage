package com.example.qrgarage.data

import com.example.qrgarage.data.repo.Csv
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun parsesQuotesCommasAndNewlines() {
        val rows = Csv.parse("id,name,notes\r\n1,\"Bolts, M6\",\"line1\nline2 \"\"q\"\"\"\n2,Saw,\n")
        assertEquals(listOf("id", "name", "notes"), rows[0])
        assertEquals(listOf("1", "Bolts, M6", "line1\nline2 \"q\""), rows[1])
        assertEquals(listOf("2", "Saw", ""), rows[2])
        assertEquals(3, rows.size)
    }
}
