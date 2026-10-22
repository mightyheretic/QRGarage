package com.example.qrgarage.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** Columns of [ItemRow]; shared by every list/search query so they stay in step. */
internal const val ITEM_ROW_COLUMNS = """
    i.id, i.name, i.locationId, i.quantity, i.unit, i.lowStockThreshold, i.deletedAt,
    (SELECT p.relativePath FROM photos p
        WHERE p.itemId = i.id AND p.deletedAt IS NULL ORDER BY p.sortOrder LIMIT 1) AS thumbnailPath,
    EXISTS(SELECT 1 FROM loans l
        WHERE l.itemId = i.id AND l.returnedAt IS NULL AND l.deletedAt IS NULL) AS isLent
"""

/** Lightweight projection for lists and search results (PHOTO-3, SEARCH-3). */
data class ItemRow(
    val id: String,
    val name: String,
    val locationId: String,
    val quantity: Double?,
    val unit: String?,
    val lowStockThreshold: Double?,
    val deletedAt: Long?,
    val thumbnailPath: String?,
    val isLent: Boolean,
) {
    val isLowStock: Boolean
        get() = quantity != null && lowStockThreshold != null && quantity <= lowStockThreshold
}

@Dao
interface ItemDao {

    @Insert
    suspend fun insert(item: ItemEntity)

    @Update
    suspend fun update(item: ItemEntity)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getById(id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE id = :id")
    fun observeById(id: String): Flow<ItemEntity?>

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ItemEntity>

    @Query(
        "SELECT $ITEM_ROW_COLUMNS FROM items i WHERE i.deletedAt IS NULL " +
            "ORDER BY MAX(i.createdAt, COALESCE(i.lastViewedAt, 0)) DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int): Flow<List<ItemRow>>

    @Query(
        "SELECT $ITEM_ROW_COLUMNS FROM items i WHERE i.locationId = :locationId AND i.deletedAt IS NULL " +
            "ORDER BY i.name COLLATE NOCASE"
    )
    fun observeInLocation(locationId: String): Flow<List<ItemRow>>

    @Query(
        "SELECT $ITEM_ROW_COLUMNS FROM items i WHERE i.deletedAt IS NOT NULL ORDER BY i.deletedAt DESC"
    )
    fun observeDeleted(): Flow<List<ItemRow>>

    @RawQuery(
        observedEntities = [
            ItemEntity::class, PhotoEntity::class, LoanEntity::class,
            ItemTagCrossRef::class, ItemFts::class, LocationEntity::class,
        ]
    )
    fun searchPaged(query: SupportSQLiteQuery): PagingSource<Int, ItemRow>

    @RawQuery
    suspend fun searchOnce(query: SupportSQLiteQuery): List<ItemRow>

    @Query("UPDATE items SET lastViewedAt = :now WHERE id = :id")
    suspend fun markViewed(id: String, now: Long)

    @Query("UPDATE items SET locationId = :locationId, updatedAt = :now WHERE id = :id")
    suspend fun setLocation(id: String, locationId: String, now: Long)

    @Query("UPDATE items SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE items SET deletedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT id FROM items WHERE locationId = :locationId AND deletedAt IS NOT NULL")
    suspend fun deletedIdsInLocation(locationId: String): List<String>

    @Query("SELECT id FROM items WHERE locationId = :locationId AND deletedAt IS NULL")
    suspend fun liveIdsInLocation(locationId: String): List<String>

    @Query("SELECT COUNT(*) FROM items WHERE deletedAt IS NULL")
    suspend fun liveCount(): Int

    @Query("SELECT * FROM items WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    suspend fun getAllLive(): List<ItemEntity>

    // --- Full-text index maintenance (DB-2) -------------------------------------------------

    @Query("DELETE FROM item_fts WHERE itemId = :itemId")
    suspend fun deleteFts(itemId: String)

    @Query(
        """
        INSERT INTO item_fts(itemId, name, description, tags, notes)
        SELECT i.id, i.name, i.description,
            COALESCE((SELECT group_concat(t.name, ' ') FROM item_tags it
                JOIN tags t ON t.id = it.tagId
                WHERE it.itemId = i.id AND t.deletedAt IS NULL), ''),
            i.notes
        FROM items i WHERE i.id = :itemId
        """
    )
    suspend fun insertFts(itemId: String)

    @Query("DELETE FROM item_fts")
    suspend fun clearFts()

    @Query(
        """
        INSERT INTO item_fts(itemId, name, description, tags, notes)
        SELECT i.id, i.name, i.description,
            COALESCE((SELECT group_concat(t.name, ' ') FROM item_tags it
                JOIN tags t ON t.id = it.tagId
                WHERE it.itemId = i.id AND t.deletedAt IS NULL), ''),
            i.notes
        FROM items i
        """
    )
    suspend fun insertAllFts()
}
