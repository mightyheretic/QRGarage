package com.example.qrgarage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Insert
    suspend fun insert(tag: TagEntity)

    @Update
    suspend fun update(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query(
        "SELECT t.* FROM tags t JOIN item_tags it ON it.tagId = t.id " +
            "WHERE it.itemId = :itemId AND t.deletedAt IS NULL ORDER BY t.name COLLATE NOCASE"
    )
    fun observeForItem(itemId: String): Flow<List<TagEntity>>

    @Query(
        "SELECT t.* FROM tags t JOIN item_tags it ON it.tagId = t.id " +
            "WHERE it.itemId = :itemId AND t.deletedAt IS NULL ORDER BY t.name COLLATE NOCASE"
    )
    suspend fun getForItem(itemId: String): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: ItemTagCrossRef)

    @Query("DELETE FROM item_tags WHERE itemId = :itemId")
    suspend fun clearForItem(itemId: String)
}

@Dao
interface CategoryDao {
    @Insert
    suspend fun insert(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE AND deletedAt IS NULL LIMIT 1")
    suspend fun getByName(name: String): CategoryEntity?
}

@Dao
interface PhotoDao {
    @Insert
    suspend fun insert(photo: PhotoEntity)

    @Update
    suspend fun update(photo: PhotoEntity)

    @Query("SELECT * FROM photos WHERE itemId = :itemId AND deletedAt IS NULL ORDER BY sortOrder")
    fun observeForItem(itemId: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE itemId = :itemId AND deletedAt IS NULL ORDER BY sortOrder")
    suspend fun getForItem(itemId: String): List<PhotoEntity>

    /** Every photo row, including soft-deleted ones, for file cleanup on hard delete. */
    @Query("SELECT * FROM photos WHERE itemId = :itemId")
    suspend fun getAllForItem(itemId: String): List<PhotoEntity>

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int

    /** Every referenced photo file, including those of trashed items (they can be restored). */
    @Query("SELECT DISTINCT relativePath FROM photos")
    suspend fun allPaths(): List<String>
}

@Dao
interface LoanDao {
    @Insert
    suspend fun insert(loan: LoanEntity)

    @Update
    suspend fun update(loan: LoanEntity)

    @Query(
        "SELECT * FROM loans WHERE itemId = :itemId AND returnedAt IS NULL AND deletedAt IS NULL LIMIT 1"
    )
    fun observeActive(itemId: String): Flow<LoanEntity?>

    @Query(
        "SELECT * FROM loans WHERE itemId = :itemId AND returnedAt IS NULL AND deletedAt IS NULL LIMIT 1"
    )
    suspend fun getActive(itemId: String): LoanEntity?
}

@Dao
interface ChangeLogDao {
    @Insert
    suspend fun insert(entry: ChangeLogEntity)

    @Query("SELECT * FROM change_log WHERE itemId = :itemId ORDER BY timestamp DESC LIMIT 50")
    fun observeForItem(itemId: String): Flow<List<ChangeLogEntity>>
}
