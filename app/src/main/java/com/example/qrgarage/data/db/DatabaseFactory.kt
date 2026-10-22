package com.example.qrgarage.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.qrgarage.backup.RestoreSwap

object DatabaseFactory {

    fun build(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    /**
     * Opens the app database. If a restore was staged (BK-4), it is swapped in first, before
     * anything holds the database open, and rolled back if the restored copy fails to open or
     * migrate.
     */
    fun open(context: Context): AppDatabase {
        RestoreSwap.applyIfStaged(context) { verify(context) }
        return build(context)
    }

    /** Opens, migrates and sanity-checks the on-disk database, then closes it. */
    private fun verify(context: Context) {
        val db = build(context)
        try {
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM items").use { it.moveToFirst() }
        } finally {
            db.close()
        }
    }
}
