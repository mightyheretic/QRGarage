package com.example.qrgarage.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.qrgarage.data.db.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DB-1: every exported schema version must migrate to the current one. When adding version N,
 * add its migration to [AppDatabase.MIGRATIONS]; this test then covers 1 → N automatically.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrateAllVersionsToLatest() = runTest {
        for (start in 1..AppDatabase.VERSION) {
            InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(dbName)
            helper.createDatabase(dbName, start).apply {
                execSQL(
                    "INSERT INTO locations (id, name, code, kind, parentId, createdAt, updatedAt, deletedAt, deviceId) " +
                        "VALUES ('loc-1', 'Garage', 'ABCDE', 'GARAGE', NULL, 1, 1, NULL, 'dev')"
                )
                execSQL(
                    "INSERT INTO items (id, name, description, locationId, notes, createdAt, updatedAt, deviceId) " +
                        "VALUES ('item-1', 'M6 bolts', '', 'loc-1', '', 1, 1, 'dev')"
                )
                close()
            }
            helper.runMigrationsAndValidate(dbName, AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS)

            val db = Room.databaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java, dbName,
            ).addMigrations(*AppDatabase.MIGRATIONS).build()
            try {
                assertEquals("M6 bolts", db.itemDao().getById("item-1")?.name)
                assertEquals("Garage", db.locationDao().getById("loc-1")?.name)
            } finally {
                db.close()
            }
        }
    }
}
