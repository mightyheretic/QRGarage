package com.example.qrgarage.data

import com.example.qrgarage.data.db.AppDatabase
import com.example.qrgarage.data.db.LocationKind
import com.example.qrgarage.data.db.SearchParams
import com.example.qrgarage.data.repo.ItemDraft
import com.example.qrgarage.data.repo.ItemRepository
import com.example.qrgarage.data.repo.LocationRepository
import com.example.qrgarage.data.repo.LocationTree
import com.example.qrgarage.data.repo.ScanResolution
import com.example.qrgarage.qr.QrPayload
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/** Data-layer acceptance criteria from section 13, against a real (in-memory) SQLite + FTS4. */
@HiltAndroidTest
class RepositoryTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var db: AppDatabase
    @Inject lateinit var items: ItemRepository
    @Inject lateinit var locations: LocationRepository

    private lateinit var garageId: String
    private lateinit var wallId: String
    private lateinit var shelfId: String
    private lateinit var binId: String

    @Before fun setUp() = runTest {
        hilt.inject()
        garageId = locations.ensureRoot().id
        wallId = locations.create("Left wall", LocationKind.ZONE, garageId).id
        shelfId = locations.create("Shelf 3", LocationKind.SHELF, wallId).id
        binId = locations.create("Bin B", LocationKind.BIN, shelfId).id
    }

    @After fun tearDown() = db.close()

    private suspend fun search(text: String = "", f: SearchParams.() -> SearchParams = { this }) =
        items.searchOnce(SearchParams(text = text).f())

    @Test fun quickAddNameOnlyIsFindableImmediately() = runTest {
        items.save(ItemDraft(name = "Hacksaw", locationId = binId))
        assertEquals(listOf("Hacksaw"), search("hacksaw").map { it.name })
    }

    @Test fun prefixSearchFindsM6BoltsWithPath() = runTest {
        items.save(ItemDraft(name = "M6 bolts", locationId = binId))
        items.save(ItemDraft(name = "M8 nuts", locationId = binId))
        val results = search("m6 bol")
        assertEquals(listOf("M6 bolts"), results.map { it.name })
        val tree = LocationTree(locations.tree.first().byId.values.toList())
        assertEquals("Left wall > Shelf 3 > Bin B", tree.pathString(results.single().locationId))
    }

    @Test fun searchCoversDescriptionTagsAndNotes() = runTest {
        items.save(ItemDraft(name = "Box A", description = "Christmas lights", locationId = binId))
        items.save(ItemDraft(name = "Box B", tags = listOf("Camping"), locationId = binId))
        items.save(ItemDraft(name = "Box C", notes = "Borrowed from Sam", locationId = binId))
        assertEquals(listOf("Box A"), search("christ").map { it.name })
        assertEquals(listOf("Box B"), search("camp").map { it.name })
        assertEquals(listOf("Box C"), search("borrow").map { it.name })
    }

    @Test fun editingRefreshesTheIndex() = runTest {
        val id = items.save(ItemDraft(name = "Drill", locationId = binId))
        items.save(ItemDraft(id = id, name = "Impact driver", locationId = binId))
        assertTrue(search("drill").isEmpty())
        assertEquals(1, search("impact").size)
    }

    @Test fun tenItemsShareStickyLocation() = runTest {
        repeat(10) { items.save(ItemDraft(name = "Part $it", locationId = shelfId)) }
        val rows = items.observeInLocation(shelfId).first()
        assertEquals(10, rows.size)
    }

    @Test fun renamedShelfStillResolvesFromOldLabel() = runTest {
        val label = QrPayload.forLocation(shelfId)
        locations.update(shelfId, "Top shelf", LocationKind.SHELF)
        val r = locations.resolveScan(label) as ScanResolution.Found
        assertEquals(shelfId, r.location.id)
        assertEquals("Top shelf", r.location.name)
    }

    @Test fun shortCodeResolves() = runTest {
        val code = locations.get(binId)!!.code
        assertEquals(binId, (locations.resolveScan(code.lowercase()) as ScanResolution.Found).location.id)
    }

    @Test fun unknownAndTrashedCodesGiveClearResults() = runTest {
        assertTrue(locations.resolveScan("garageinv://loc/00000000-0000-0000-0000-000000000000") is ScanResolution.Error)
        assertTrue(locations.resolveScan("hello world") is ScanResolution.Error)
        val empty = locations.create("Empty bin", LocationKind.BIN, shelfId)
        assertTrue(locations.softDelete(empty.id))
        assertTrue(locations.resolveScan(QrPayload.forLocation(empty.id)) is ScanResolution.InTrash)
    }

    @Test fun deleteAndRestoreItem() = runTest {
        val id = items.save(ItemDraft(name = "Ladder", locationId = wallId))
        items.softDelete(listOf(id))
        assertTrue(search("ladder").isEmpty())
        assertEquals(listOf(id), items.observeTrash().first().map { it.id })
        items.restore(listOf(id))
        assertEquals(1, search("ladder").size)
        assertTrue(items.observeTrash().first().isEmpty())
    }

    @Test fun moveAndUndo() = runTest {
        val id = items.save(ItemDraft(name = "Vise", locationId = binId))
        val previous = items.move(listOf(id), wallId)
        assertEquals(wallId, items.get(id)!!.locationId)
        items.undoMove(previous)
        assertEquals(binId, items.get(id)!!.locationId)
    }

    @Test fun locationFilterIncludesDescendants() = runTest {
        items.save(ItemDraft(name = "Deep thing", locationId = binId))
        items.save(ItemDraft(name = "Other thing", locationId = garageId))
        assertEquals(listOf("Deep thing"), search("thing") { copy(locationId = wallId) }.map { it.name })
        assertEquals(2, search("thing") { copy(locationId = garageId) }.size)
    }

    @Test fun lowStockAndLentFilters() = runTest {
        items.save(ItemDraft(name = "Zip ties", quantity = 2.0, lowStockThreshold = 5.0, locationId = binId))
        items.save(ItemDraft(name = "Screws", quantity = 50.0, lowStockThreshold = 5.0, locationId = binId))
        val saw = items.save(ItemDraft(name = "Saw", locationId = binId))
        assertEquals(listOf("Zip ties"), search { copy(lowStockOnly = true) }.map { it.name })
        items.lend(saw, "Alex", System.currentTimeMillis(), null)
        assertEquals(listOf("Saw"), search { copy(lentOutOnly = true) }.map { it.name })
        items.markReturned(saw)
        assertTrue(search { copy(lentOutOnly = true) }.isEmpty())
    }

    @Test fun pathAndDescendantQueries() = runTest {
        assertEquals(listOf(garageId, wallId, shelfId, binId), locations.path(binId).map { it.id })
        assertEquals(setOf(wallId, shelfId, binId), locations.descendantIds(wallId).toSet())
    }

    @Test fun nonEmptyLocationNeedsReassignment() = runTest {
        val id = items.save(ItemDraft(name = "Clamp", locationId = shelfId))
        assertFalse(locations.softDelete(shelfId))
        val blockers = locations.deleteBlockers(shelfId)
        assertEquals(1, blockers.itemCount)
        assertEquals(1, blockers.childCount)
        // Reassigning into its own subtree is refused.
        assertFalse(locations.reassignAndDelete(shelfId, binId))
        assertTrue(locations.reassignAndDelete(shelfId, wallId))
        assertEquals(wallId, items.get(id)!!.locationId)
        assertEquals(wallId, locations.get(binId)!!.parentId)
        assertTrue(locations.get(shelfId)!!.deletedAt != null)
    }

    @Test fun cannotMoveLocationIntoItself() = runTest {
        assertFalse(locations.move(wallId, binId))
        assertTrue(locations.move(binId, garageId))
    }

    @Test fun restoringItemRestoresTrashedLocation() = runTest {
        val id = items.save(ItemDraft(name = "Rake", locationId = binId))
        items.softDelete(listOf(id))
        assertTrue(locations.softDelete(binId))
        items.restore(listOf(id))
        assertEquals(null, locations.get(binId)!!.deletedAt)
    }

    @Test fun tagsAreSharedAndCaseInsensitive() = runTest {
        items.save(ItemDraft(name = "A", tags = listOf("Paint"), locationId = binId))
        items.save(ItemDraft(name = "B", tags = listOf("paint", "Outdoor"), locationId = binId))
        assertEquals(listOf("Outdoor", "Paint"), items.tags.first().map { it.name })
        val paint = items.tags.first().first { it.name == "Paint" }
        assertEquals(2, search { copy(tagId = paint.id) }.size)
    }
}
