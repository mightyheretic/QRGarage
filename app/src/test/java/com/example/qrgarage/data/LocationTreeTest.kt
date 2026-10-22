package com.example.qrgarage.data

import com.example.qrgarage.data.db.LocationEntity
import com.example.qrgarage.data.db.LocationKind
import com.example.qrgarage.data.repo.LocationTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTreeTest {

    private fun loc(id: String, name: String, parent: String?, kind: LocationKind = LocationKind.ZONE) =
        LocationEntity(id, name, id.uppercase().padEnd(5, 'X').take(5), kind, parent, 0, 0, null, "dev")

    private val garage = loc("g", "Garage", null, LocationKind.GARAGE)
    private val wall = loc("w", "Left wall", "g")
    private val shelf = loc("s", "Shelf 3", "w", LocationKind.SHELF)
    private val bin = loc("b", "Bin B", "s", LocationKind.BIN)
    private val tree = LocationTree(listOf(bin, shelf, wall, garage))

    @Test fun pathOmitsSoleRoot() {
        // Segments are joined with no-break spaces (LOC-16).
        assertEquals("Left wall > Shelf\u00A03 > Bin\u00A0B", tree.pathString("b"))
        assertEquals("Garage", tree.pathString("g"))
    }

    @Test fun pathIncludesRootWhenSeveralGarages() {
        val t = LocationTree(listOf(garage, wall, loc("g2", "Shed", null, LocationKind.GARAGE)))
        assertEquals("Garage > Left wall", t.pathString("w"))
    }

    @Test fun descendantCheck() {
        assertTrue(tree.isSelfOrDescendant("b", "w"))
        assertTrue(tree.isSelfOrDescendant("w", "w"))
        assertFalse(tree.isSelfOrDescendant("w", "b"))
    }

    @Test fun flattenIsDepthFirst() {
        assertEquals(listOf("g" to 0, "w" to 1, "s" to 2, "b" to 3), tree.flatten().map { it.location.id to it.depth })
        assertEquals(listOf("g", "w"), tree.flatten(expanded = setOf("g")).map { it.location.id })
    }

    @Test fun cyclesDoNotHang() {
        val a = loc("a", "A", "c")
        val b = loc("b", "B", "a")
        val c = loc("c", "C", "b")
        assertEquals(3, LocationTree(listOf(a, b, c)).path("a").size)
    }

    @Test fun orphanBecomesRoot() {
        val t = LocationTree(listOf(loc("x", "Orphan", "missing")))
        assertEquals(listOf("x"), t.roots.map { it.id })
    }

    @Test fun wheelLocationsSortInWheelOrder() {
        val names = listOf("Shelf AA Row 01", "Shelf B Row 10", "Shelf B Row 02", "Zone 1", "Shelf A Row 01 Bin 2")
        val t = LocationTree(listOf(garage) + names.mapIndexed { i, n -> loc("x$i", n, "g", LocationKind.SHELF) })
        assertEquals(
            listOf("Zone 1", "Shelf A Row 01 Bin 2", "Shelf B Row 02", "Shelf B Row 10", "Shelf AA Row 01"),
            t.children("g").map { it.name },
        )
    }

    @Test fun duplicateLabelIsDetectedPerParent() { // LOC-15
        val t = LocationTree(listOf(garage, wall, loc("a", "Shelf A Row 01", "w", LocationKind.SHELF)))
        assertTrue(t.labelTaken("w", "shelf a row 01"))
        assertFalse(t.labelTaken("g", "Shelf A Row 01"))
        assertFalse(t.labelTaken("w", "Shelf A Row 01", excludeId = "a"))
    }

    @Test fun suggestsNextFreeRowAndBin() {
        val shelfA = loc("a", "Shelf A Row 01", "w", LocationKind.SHELF)
        val t = LocationTree(listOf(garage, wall, shelfA, loc("b1", "Shelf A Row 01 Bin 1", "a", LocationKind.BIN)))
        assertEquals("Shelf A Row 02", t.suggestWheels("w", LocationKind.SHELF).label)
        assertEquals("Shelf A Row 01 Bin 2", t.suggestWheels("a", LocationKind.BIN).label)
        assertEquals("Shelf A Row 01", t.suggestWheels("g", LocationKind.SHELF).label)
    }

    @Test fun sameShelfSiblingsCollapseUnderOneEntry() {
        val names = listOf("Shelf A Row 01", "Shelf A Row 02", "Shelf B Row 01", "Zone 1")
        val t = LocationTree(listOf(garage) + names.mapIndexed { i, n -> loc("x$i", n, "g", LocationKind.SHELF) })
        val key = t.groupKeyOf("x0")!!
        assertEquals(key, t.groupKeyOf("x1"))
        assertEquals(null, t.groupKeyOf("x2")) // a shelf with one location stays ungrouped
        assertEquals(listOf("Garage", "Zone 1", "Shelf\u00A0A", "Shelf\u00A0B Row\u00A001"), t.flattenGrouped(setOf("g")).map { it.label })
        assertEquals(
            listOf("Shelf\u00A0A" to 1, "Row\u00A001" to 2, "Row\u00A002" to 2),
            t.flattenGrouped(setOf("g", key)).filter { it.label.startsWith("Shelf\u00A0A") || it.depth == 2 }.map { it.label to it.depth },
        )
    }

    @Test fun bareShelfIsTheGroupHeader() {
        val names = listOf("Shelf A", "Shelf A Row 01")
        val t = LocationTree(listOf(garage) + names.mapIndexed { i, n -> loc("x$i", n, "g", LocationKind.SHELF) })
        assertEquals("x0", t.groupKeyOf("x1"))
        assertEquals(null, t.groupKeyOf("x0"))
        val rows = t.flattenGrouped(setOf("g", "x0"))
        assertEquals(listOf("g", "x0", "x1"), rows.map { it.location?.id })
        assertEquals(listOf(t["x1"]), rows[1].members)
    }
}
