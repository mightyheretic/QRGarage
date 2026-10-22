package com.example.qrgarage.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.qrgarage.data.db.AppDatabase
import com.example.qrgarage.data.db.LocationKind
import com.example.qrgarage.data.repo.ItemDraft
import com.example.qrgarage.data.repo.ItemRepository
import com.example.qrgarage.data.repo.LocationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

@HiltAndroidTest
class BackupTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject @ApplicationContext lateinit var context: Context
    @Inject lateinit var db: AppDatabase
    @Inject lateinit var backups: BackupManager
    @Inject lateinit var items: ItemRepository
    @Inject lateinit var locations: LocationRepository

    @Before fun setUp() = hilt.inject()
    @After fun tearDown() = db.close()

    /** BK-1 / BK-2: zip with a consistent DB snapshot, photos and a manifest. */
    @Test fun archiveContainsSnapshotPhotosAndManifest() = runTest {
        val root = locations.ensureRoot()
        val bin = locations.create("Bin", LocationKind.BIN, root.id)
        repeat(3) { items.save(ItemDraft(name = "Thing $it", locationId = bin.id)) }

        val zipFile = File(context.cacheDir, "test-backup.zip")
        val manifest = backups.createArchive(zipFile, includePhotos = true)
        assertEquals(3, manifest.itemCount)
        assertEquals(AppDatabase.VERSION, manifest.schemaVersion)
        assertTrue(manifest.includesPhotos)

        ZipFile(zipFile).use { zip ->
            val m = Json.decodeFromString<BackupManifest>(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
            assertEquals(manifest, m)
            val dbCopy = File(context.cacheDir, "test-extracted.db")
            zip.getInputStream(zip.getEntry("garage.db")).use { input -> dbCopy.outputStream().use { input.copyTo(it) } }
            SQLiteDatabase.openDatabase(dbCopy.path, null, SQLiteDatabase.OPEN_READONLY).use { sdb ->
                assertEquals(AppDatabase.VERSION, sdb.version)
                sdb.rawQuery("SELECT COUNT(*) FROM items", null).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
                sdb.rawQuery("SELECT COUNT(*) FROM item_fts WHERE item_fts MATCH 'thin*'", null).use { c ->
                    c.moveToFirst(); assertEquals(3, c.getInt(0))
                }
            }
            dbCopy.delete()
        }
        zipFile.delete()
    }

    /** BK-4: a restore that fails verification is rolled back and the original data kept. */
    @Test fun failedRestoreRollsBack() {
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        dbFile.parentFile!!.mkdirs()
        dbFile.writeText("original")
        val staging = RestoreSwap.stagingDir(context).apply { deleteRecursively(); mkdirs() }
        File(staging, RestoreSwap.STAGED_DB).writeText("restored")
        RestoreSwap.markStaged(context)

        RestoreSwap.applyIfStaged(context) { error("verification failed") }

        assertEquals("original", dbFile.readText())
        assertFalse(staging.exists())
        assertEquals("verification failed", RestoreSwap.consumeFailure(context))
        dbFile.delete()
    }

    @Test fun successfulRestoreSwapsIn() {
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        dbFile.parentFile!!.mkdirs()
        dbFile.writeText("original")
        val staging = RestoreSwap.stagingDir(context).apply { deleteRecursively(); mkdirs() }
        File(staging, RestoreSwap.STAGED_DB).writeText("restored")
        RestoreSwap.markStaged(context)

        RestoreSwap.applyIfStaged(context) { }

        assertEquals("restored", dbFile.readText())
        assertFalse(File(context.filesDir, "pre-restore").exists())
        assertEquals(null, RestoreSwap.consumeFailure(context))
        dbFile.delete()
    }
}
