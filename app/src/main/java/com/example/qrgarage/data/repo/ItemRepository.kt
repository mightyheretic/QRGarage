package com.example.qrgarage.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.example.qrgarage.data.db.AppDatabase
import com.example.qrgarage.data.db.CategoryEntity
import com.example.qrgarage.data.db.ChangeLogEntity
import com.example.qrgarage.data.db.ChangeType
import com.example.qrgarage.data.db.ItemEntity
import com.example.qrgarage.data.db.ItemRow
import com.example.qrgarage.data.db.ItemTagCrossRef
import com.example.qrgarage.data.db.LoanEntity
import com.example.qrgarage.data.db.PhotoEntity
import com.example.qrgarage.data.db.SearchParams
import com.example.qrgarage.data.db.SearchQuery
import com.example.qrgarage.data.db.TagEntity
import com.example.qrgarage.data.photos.PhotoStorage
import com.example.qrgarage.data.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the add/edit form produces. [photos] are relative paths in display order. */
data class ItemDraft(
    val id: String? = null,
    val name: String,
    val description: String = "",
    val locationId: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val categoryId: String? = null,
    /** When set, a category with this name is found or created and used instead of [categoryId]. */
    val newCategoryName: String? = null,
    val purchaseDate: Long? = null,
    val purchasePriceCents: Long? = null,
    val notes: String = "",
    val lowStockThreshold: Double? = null,
    val tags: List<String> = emptyList(),
    val photos: List<String> = emptyList(),
)

data class ItemDetail(
    val item: ItemEntity,
    val photos: List<PhotoEntity>,
    val tags: List<TagEntity>,
    val category: CategoryEntity?,
    val activeLoan: LoanEntity?,
    val history: List<ChangeLogEntity>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ItemRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val locations: LocationRepository,
    private val photoStorage: PhotoStorage,
) {
    private val itemDao = db.itemDao()
    private val tagDao = db.tagDao()
    private val categoryDao = db.categoryDao()
    private val photoDao = db.photoDao()
    private val loanDao = db.loanDao()
    private val logDao = db.changeLogDao()

    val tags: Flow<List<TagEntity>> = tagDao.observeAll()
    val categories: Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun observeRecent(limit: Int = 30): Flow<List<ItemRow>> = itemDao.observeRecent(limit)
    fun observeInLocation(locationId: String): Flow<List<ItemRow>> = itemDao.observeInLocation(locationId)
    fun observeTrash(): Flow<List<ItemRow>> = itemDao.observeDeleted()

    /** SEARCH-1/2/5: paged FTS search; Room runs it off the main thread. */
    fun search(params: SearchParams): Flow<PagingData<ItemRow>> =
        Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) {
            itemDao.searchPaged(SearchQuery.build(params))
        }.flow

    suspend fun searchOnce(params: SearchParams): List<ItemRow> =
        itemDao.searchOnce(SearchQuery.build(params))

    suspend fun get(id: String): ItemEntity? = itemDao.getById(id)
    suspend fun tagsFor(id: String): List<TagEntity> = tagDao.getForItem(id)
    suspend fun photosFor(id: String): List<PhotoEntity> = photoDao.getForItem(id)

    fun observeDetail(id: String): Flow<ItemDetail?> =
        itemDao.observeById(id).flatMapLatest { item ->
            if (item == null) return@flatMapLatest flowOf(null)
            combine(
                photoDao.observeForItem(id),
                tagDao.observeForItem(id),
                loanDao.observeActive(id),
                logDao.observeForItem(id),
                categories,
            ) { photos, tags, loan, history, cats ->
                ItemDetail(item, photos, tags, cats.firstOrNull { it.id == item.categoryId }, loan, history)
            }
        }

    suspend fun markViewed(id: String) = itemDao.markViewed(id, System.currentTimeMillis())

    /** ITEM-1..11: inserts or updates an item, its tags and photos. Returns the item id. */
    suspend fun save(draft: ItemDraft): String {
        val device = settings.deviceId()
        val removedFiles = mutableListOf<String>()
        val id = db.withTransaction {
            val now = System.currentTimeMillis()
            val categoryId = resolveCategory(draft, now, device)
            val existing = draft.id?.let { itemDao.getById(it) }
            val id = existing?.id ?: draft.id ?: UUID.randomUUID().toString()
            val updated = ItemEntity(
                id = id,
                name = draft.name.trim(),
                description = draft.description.trim(),
                locationId = draft.locationId,
                quantity = draft.quantity,
                unit = draft.unit?.trim()?.takeIf { it.isNotEmpty() },
                categoryId = categoryId,
                purchaseDate = draft.purchaseDate,
                purchasePriceCents = draft.purchasePriceCents,
                notes = draft.notes.trim(),
                lowStockThreshold = draft.lowStockThreshold,
                lastViewedAt = existing?.lastViewedAt,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                deletedAt = existing?.deletedAt,
                deviceId = device,
            )
            if (existing == null) {
                itemDao.insert(updated)
                log(id, ChangeType.CREATED, "Added", now, device)
            } else {
                itemDao.update(updated)
                if (existing.locationId != updated.locationId) {
                    log(id, ChangeType.MOVED, "Moved", now, device)
                }
                val changed = changedFields(existing, updated)
                if (changed.isNotEmpty()) log(id, ChangeType.EDITED, "Changed ${changed.joinToString()}", now, device)
            }

            val oldTags = tagDao.getForItem(id).map { it.name.lowercase() }.toSet()
            tagDao.clearForItem(id)
            draft.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.forEach { name ->
                val tag = tagDao.getByName(name)?.let { t ->
                    if (t.deletedAt != null) t.copy(deletedAt = null, updatedAt = now).also { tagDao.update(it) } else t
                } ?: TagEntity(UUID.randomUUID().toString(), name, now, now, null, device).also { tagDao.insert(it) }
                tagDao.insertCrossRef(ItemTagCrossRef(id, tag.id, now))
            }
            if (existing != null && oldTags != draft.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()) {
                log(id, ChangeType.EDITED, "Changed tags", now, device)
            }

            val currentPhotos = photoDao.getAllForItem(id)
            currentPhotos.filter { it.relativePath !in draft.photos }.forEach {
                photoDao.hardDelete(it.id)
                removedFiles += it.relativePath
            }
            draft.photos.take(PhotoStorage.MAX_PER_ITEM).forEachIndexed { index, path ->
                val row = currentPhotos.firstOrNull { it.relativePath == path }
                when {
                    row == null -> photoDao.insert(
                        PhotoEntity(UUID.randomUUID().toString(), id, path, index, now, now, null, device)
                    )
                    row.sortOrder != index || row.deletedAt != null ->
                        photoDao.update(row.copy(sortOrder = index, deletedAt = null, updatedAt = now))
                }
            }

            refreshFts(id)
            id
        }
        removedFiles.forEach(photoStorage::delete)
        return id
    }

    /** ITEM-12/14: moves items and returns their previous locations for undo. */
    suspend fun move(ids: Collection<String>, locationId: String): Map<String, String> {
        val device = settings.deviceId()
        return db.withTransaction {
            val now = System.currentTimeMillis()
            itemDao.getByIds(ids.toList()).filter { it.locationId != locationId }.associate { item ->
                itemDao.setLocation(item.id, locationId, now)
                log(item.id, ChangeType.MOVED, "Moved", now, device)
                item.id to item.locationId
            }
        }
    }

    suspend fun undoMove(previous: Map<String, String>) {
        val device = settings.deviceId()
        db.withTransaction {
            val now = System.currentTimeMillis()
            previous.forEach { (id, loc) ->
                itemDao.setLocation(id, locations.liveOrRoot(loc).id, now)
                log(id, ChangeType.MOVED, "Move undone", now, device)
            }
        }
    }

    /** ITEM-13: soft delete; rows stay in Trash until permanently deleted. */
    suspend fun softDelete(ids: Collection<String>) {
        val device = settings.deviceId()
        db.withTransaction {
            val now = System.currentTimeMillis()
            ids.forEach {
                itemDao.softDelete(it, now)
                log(it, ChangeType.DELETED, "Moved to trash", now, device)
            }
        }
    }

    /** Restores from Trash; also restores the item's location if that was trashed too. */
    suspend fun restore(ids: Collection<String>) {
        val device = settings.deviceId()
        db.withTransaction {
            val now = System.currentTimeMillis()
            itemDao.getByIds(ids.toList()).forEach { item ->
                val loc = locations.get(item.locationId)
                when {
                    loc == null -> itemDao.setLocation(item.id, locations.ensureRoot().id, now)
                    loc.deletedAt != null -> locations.restore(loc.id)
                }
                itemDao.restore(item.id, now)
                log(item.id, ChangeType.RESTORED, "Restored", now, device)
            }
        }
    }

    suspend fun deletePermanently(id: String) {
        val files = db.withTransaction {
            val files = photoDao.getAllForItem(id).map { it.relativePath }
            itemDao.deleteFts(id)
            itemDao.hardDelete(id)
            files
        }
        files.forEach(photoStorage::delete)
    }

    /** ITEM-11: one active loan per item. */
    suspend fun lend(itemId: String, borrower: String, lentAt: Long, dueAt: Long?) {
        val device = settings.deviceId()
        db.withTransaction {
            val now = System.currentTimeMillis()
            loanDao.getActive(itemId)?.let { loanDao.update(it.copy(returnedAt = now, updatedAt = now)) }
            loanDao.insert(
                LoanEntity(UUID.randomUUID().toString(), itemId, borrower.trim(), lentAt, dueAt, null, now, now, null, device)
            )
            log(itemId, ChangeType.LENT, "Lent to ${borrower.trim()}", now, device)
        }
    }

    suspend fun markReturned(itemId: String) {
        val device = settings.deviceId()
        db.withTransaction {
            val now = System.currentTimeMillis()
            val loan = loanDao.getActive(itemId) ?: return@withTransaction
            loanDao.update(loan.copy(returnedAt = now, updatedAt = now))
            log(itemId, ChangeType.RETURNED, "Returned by ${loan.borrower}", now, device)
        }
    }

    /** Rebuilds the whole FTS index, e.g. after an import. */
    suspend fun rebuildSearchIndex() = db.withTransaction {
        itemDao.clearFts()
        itemDao.insertAllFts()
    }

    private suspend fun refreshFts(id: String) {
        itemDao.deleteFts(id)
        itemDao.insertFts(id)
    }

    private suspend fun resolveCategory(draft: ItemDraft, now: Long, device: String): String? {
        val newName = draft.newCategoryName?.trim()?.takeIf { it.isNotEmpty() } ?: return draft.categoryId
        categoryDao.getByName(newName)?.let { return it.id }
        val category = CategoryEntity(UUID.randomUUID().toString(), newName, now, now, null, device)
        categoryDao.insert(category)
        return category.id
    }

    private suspend fun log(itemId: String, type: ChangeType, summary: String, now: Long, device: String) {
        logDao.insert(ChangeLogEntity(UUID.randomUUID().toString(), itemId, now, type, summary, device))
    }

    private fun changedFields(a: ItemEntity, b: ItemEntity): List<String> = buildList {
        if (a.name != b.name) add("name")
        if (a.description != b.description) add("description")
        if (a.quantity != b.quantity || a.unit != b.unit) add("quantity")
        if (a.categoryId != b.categoryId) add("category")
        if (a.purchaseDate != b.purchaseDate || a.purchasePriceCents != b.purchasePriceCents) add("purchase info")
        if (a.notes != b.notes) add("notes")
        if (a.lowStockThreshold != b.lowStockThreshold) add("low-stock threshold")
    }
}
