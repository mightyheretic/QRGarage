package com.example.qrgarage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {

    @Insert
    suspend fun insert(location: LocationEntity)

    @Update
    suspend fun update(location: LocationEntity)

    @Query("SELECT * FROM locations WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun getById(id: String): LocationEntity?

    @Query("SELECT * FROM locations WHERE id = :id")
    fun observeById(id: String): Flow<LocationEntity?>

    @Query("SELECT * FROM locations WHERE code = :code COLLATE NOCASE")
    suspend fun getByCode(code: String): LocationEntity?

    @Query("SELECT COUNT(*) FROM locations WHERE code = :code COLLATE NOCASE")
    suspend fun countWithCode(code: String): Int

    @Query("SELECT COUNT(*) FROM locations")
    suspend fun countAllIncludingDeleted(): Int

    /** LOC-15: live siblings under [parentId] (null = top level) already using [name]. */
    @Query(
        "SELECT COUNT(*) FROM locations WHERE deletedAt IS NULL AND id != :excludeId " +
            "AND name = :name COLLATE NOCASE " +
            "AND ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId)"
    )
    suspend fun countSiblingsNamed(parentId: String?, name: String, excludeId: String = ""): Int

    @Query("SELECT * FROM locations WHERE parentId IS NULL AND deletedAt IS NULL ORDER BY createdAt LIMIT 1")
    suspend fun firstRoot(): LocationEntity?

    @Query("SELECT * FROM locations WHERE parentId = :parentId AND deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeChildren(parentId: String): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE parentId = :parentId AND deletedAt IS NULL")
    suspend fun getChildren(parentId: String): List<LocationEntity>

    @Query("SELECT COUNT(*) FROM locations WHERE parentId = :id AND deletedAt IS NULL")
    suspend fun liveChildCount(id: String): Int

    @Query("SELECT COUNT(*) FROM items WHERE locationId = :id AND deletedAt IS NULL")
    suspend fun liveItemCount(id: String): Int

    /** DB-3: ancestors of [id], root first, ending with the location itself. */
    @Query(
        """
        WITH RECURSIVE anc(id, depth) AS (
            SELECT id, 0 FROM locations WHERE id = :id
            UNION ALL
            SELECT l.parentId, anc.depth + 1 FROM locations l JOIN anc ON l.id = anc.id
            WHERE l.parentId IS NOT NULL AND anc.depth < 64
        )
        SELECT l.* FROM anc JOIN locations l ON l.id = anc.id ORDER BY anc.depth DESC
        """
    )
    suspend fun getPath(id: String): List<LocationEntity>

    /** DB-3: [id] plus every live descendant id. */
    @Query(
        """
        WITH RECURSIVE sub(id) AS (
            SELECT :id
            UNION
            SELECT l.id FROM locations l JOIN sub ON l.parentId = sub.id WHERE l.deletedAt IS NULL
        )
        SELECT id FROM sub
        """
    )
    suspend fun getDescendantIds(id: String): List<String>

    @Query("SELECT * FROM locations WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<LocationEntity>>

    @Query("UPDATE locations SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE locations SET deletedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    @Query("DELETE FROM locations WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT COUNT(*) FROM locations WHERE deletedAt IS NULL")
    suspend fun liveCount(): Int
}
