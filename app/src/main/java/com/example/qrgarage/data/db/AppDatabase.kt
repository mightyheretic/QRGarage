package com.example.qrgarage.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        LocationEntity::class,
        CategoryEntity::class,
        ItemEntity::class,
        TagEntity::class,
        ItemTagCrossRef::class,
        PhotoEntity::class,
        LoanEntity::class,
        ChangeLogEntity::class,
        ItemFts::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    abstract fun itemDao(): ItemDao
    abstract fun tagDao(): TagDao
    abstract fun categoryDao(): CategoryDao
    abstract fun photoDao(): PhotoDao
    abstract fun loanDao(): LoanDao
    abstract fun changeLogDao(): ChangeLogDao

    companion object {
        /** Bump together with a new entry in [MIGRATIONS] and a migration test (DB-1). */
        const val VERSION = 1
        const val NAME = "garage.db"

        /**
         * DB-1: every schema change ships an explicit migration; destructive fallback is never
         * enabled. Also used when restoring older backups (BK-4).
         */
        val MIGRATIONS: Array<Migration> = arrayOf()
    }
}
