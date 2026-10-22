package com.example.qrgarage.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.qrgarage.data.repo.LocationLabel

/*
 * Every syncable table carries id (UUID), createdAt, updatedAt, deletedAt (tombstone) and
 * deviceId so a later shared/merge mode stays feasible (section 8, MU-1).
 */

/** LOC-1: hierarchy level. Depth is flexible; the kind is a label, not a constraint. */
enum class LocationKind(val label: String) {
    GARAGE("Garage"),
    ZONE("Zone"),
    SHELF_UNIT("Shelf unit"),
    SHELF("Shelf / row"),
    BIN("Shelf / row / bin");

    /** The kind suggested for a new child of this location. */
    val childKind: LocationKind get() = entries.getOrElse(ordinal + 1) { BIN }
}

@Entity(
    tableName = "locations",
    indices = [
        Index("parentId"),
        Index(value = ["code"], unique = true),
        Index("updatedAt"),
        Index("deletedAt"),
    ],
)
data class LocationEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** LOC-2: short human-friendly code for manual entry; the QR encodes [id]. */
    val code: String,
    val kind: LocationKind,
    val parentId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val deviceId: String,
) {
    /**
     * LOC-9..13: wheel values read back from a generated label ("Shelf A Row 01 Bin 2"); null
     * for free-text names such as zones. The format lives in the name, so no extra columns.
     */
    val wheels: LocationLabel.Parts? get() = LocationLabel.parse(name)
}

@Entity(
    tableName = "categories",
    indices = [Index("updatedAt"), Index("deletedAt")],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val deviceId: String,
)

@Entity(
    tableName = "items",
    indices = [
        Index("locationId"),
        Index("categoryId"),
        Index("updatedAt"),
        Index("deletedAt"),
        Index("lastViewedAt"),
        Index("createdAt"),
    ],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val locationId: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val categoryId: String? = null,
    val purchaseDate: Long? = null,
    /** Minor currency units (cents) to avoid floating-point money. */
    val purchasePriceCents: Long? = null,
    val notes: String = "",
    val lowStockThreshold: Double? = null,
    /** Local-only "recently viewed" marker; does not bump [updatedAt]. */
    val lastViewedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val deviceId: String,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true), Index("updatedAt"), Index("deletedAt")],
)
data class TagEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val deviceId: String,
)

/** DB-6: item ↔ tag join table. */
@Entity(
    tableName = "item_tags",
    primaryKeys = ["itemId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(ItemEntity::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ItemTagCrossRef(
    val itemId: String,
    val tagId: String,
    val createdAt: Long,
)

@Entity(
    tableName = "photos",
    indices = [Index("itemId"), Index("updatedAt"), Index("deletedAt")],
    foreignKeys = [
        ForeignKey(ItemEntity::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    /** DB-4: relative to the app photo directory, so backups are portable. */
    val relativePath: String,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val deviceId: String,
)

/** ITEM-11 / DB-6: loans in their own table; at most one active (returnedAt == null) per item. */
@Entity(
    tableName = "loans",
    indices = [Index("itemId"), Index("returnedAt"), Index("updatedAt"), Index("deletedAt")],
    foreignKeys = [
        ForeignKey(ItemEntity::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class LoanEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val borrower: String,
    val lentAt: Long,
    val dueAt: Long? = null,
    val returnedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val deviceId: String,
)

enum class ChangeType { CREATED, EDITED, MOVED, DELETED, RESTORED, LENT, RETURNED }

/** ITEM-15: optional per-item change history. */
@Entity(
    tableName = "change_log",
    indices = [Index("itemId"), Index("timestamp")],
    foreignKeys = [
        ForeignKey(ItemEntity::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ChangeLogEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val timestamp: Long,
    val type: ChangeType,
    val summary: String,
    val deviceId: String,
)

/**
 * SEARCH-1 / DB-2: full-text index over item name, description, tag names and notes.
 *
 * Tags live in a join table, so this cannot be a plain `contentEntity` mirror of `items`.
 * Rows are rebuilt by [ItemDao.refreshFts] inside the same transaction as every write that
 * affects an item's searchable text.
 */
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
    notIndexed = ["itemId"],
)
@Entity(tableName = "item_fts")
data class ItemFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int = 0,
    val itemId: String,
    val name: String,
    val description: String,
    val tags: String,
    val notes: String,
)
