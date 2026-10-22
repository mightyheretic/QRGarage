package com.example.qrgarage.data

import com.example.qrgarage.data.repo.LocationLabel
import com.example.qrgarage.data.repo.LocationLabel.Parts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationLabelTest {

    @Test fun shelfWheelContinuesSpreadsheetStyleAfterZ() { // LOC-10
        assertEquals("A", LocationLabel.shelfLetters(0))
        assertEquals("Z", LocationLabel.shelfLetters(25))
        assertEquals("AA", LocationLabel.shelfLetters(26))
        assertEquals("AB", LocationLabel.shelfLetters(27))
        assertEquals("AZ", LocationLabel.shelfLetters(51))
        assertEquals("BA", LocationLabel.shelfLetters(52))
        assertEquals("ZZ", LocationLabel.shelfLetters(LocationLabel.SHELF_COUNT - 1))
    }

    @Test fun shelfIndexRoundTrips() {
        repeat(LocationLabel.SHELF_COUNT) { assertEquals(it, LocationLabel.shelfIndex(LocationLabel.shelfLetters(it))) }
        assertNull(LocationLabel.shelfIndex("A1"))
        assertNull(LocationLabel.shelfIndex(""))
    }

    @Test fun rowIsZeroPaddedBinIsNot() { // LOC-11, LOC-12
        assertEquals("01", LocationLabel.rowText(1))
        assertEquals("10", LocationLabel.rowText(10))
        assertEquals("120", LocationLabel.rowText(120))
        assertEquals("2", LocationLabel.binText(2))
    }

    @Test fun generatesUniformLabel() { // LOC-13
        assertEquals("Shelf AA Row 03 Bin 2", Parts("AA", 3, 2).label)
        assertEquals("Shelf A Row 01", Parts("A", 1).label)
        assertEquals("Shelf B", Parts("B").label)
        assertEquals("Shelf C Bin 4", Parts("c", null, 4).label)
    }

    @Test fun parsesGeneratedAndLooselyTypedLabels() {
        assertEquals(Parts("AA", 3, 2), LocationLabel.parse("Shelf AA Row 03 Bin 2"))
        assertEquals(Parts("A", 1), LocationLabel.parse("shelf a  row 1"))
        assertNull(LocationLabel.parse("Left wall"))
        assertNull(LocationLabel.parse("Shelf 3"))
        assertNull(LocationLabel.parse("Shelf A Row 0"))
    }

    @Test fun segmentsNeverBreakInternally() { // LOC-16
        val text = LocationLabel.unbreakable("Shelf AA Row 03 Bin 2")
        assertEquals("Shelf AA Row 03 Bin 2", text)
        val breakable = text.indices.filter { text[it] == ' ' }
        assertEquals(2, breakable.size) // only between segments
        assertFalse(LocationLabel.unbreakable("Left wall").contains(' '))
        assertTrue(LocationLabel.unbreakable("Garage > Shelf A").endsWith("Shelf A"))
    }
}
