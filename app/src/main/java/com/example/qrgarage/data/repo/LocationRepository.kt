package com.example.qrgarage.data.repo

import androidx.room.withTransaction
import com.example.qrgarage.data.db.AppDatabase
import com.example.qrgarage.data.db.LocationEntity
import com.example.qrgarage.data.db.LocationKind
import com.example.qrgarage.data.settings.SettingsRepository
import com.example.qrgarage.qr.QrPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ScanResolution {
    data class Found(val location: LocationEntity) : ScanResolution
    data class InTrash(val location: LocationEntity) : ScanResolution
    data class Error(val message: String) : ScanResolution
}

data class DeleteBlockers(val itemCount: Int, val childCount: Int) {
    val isEmpty get() = itemCount == 0 && childCount == 0
}

/**
 * What the add/edit dialog produces. With [wheels] set, the name is generated from the wheel
 * selections (LOC-13) and [name] is ignored; otherwise [name] is a free-text label.
 */
data class LocationInput(
    val kind: LocationKind,
    val name: String = "",
    val wheels: LocationLabel.Parts? = null,
) {
    val label: String get() = wheels?.label ?: name.trim().ifEmpty { kind.label }
}

/** LOC-15: a generated label that already exists under the same parent. */
class DuplicateLocationException(val label: String) : Exception("“$label” already exists in this location")

@Singleton
class LocationRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val dao = db.locationDao()
    private val itemDao = db.itemDao()
    private val random = SecureRandom()
    private val rootMutex = Mutex()

    val tree: Flow<LocationTree> = dao.observeAll().map(::LocationTree)

    fun observe(id: String): Flow<LocationEntity?> = dao.observeById(id)
    fun observeChildren(id: String): Flow<List<LocationEntity>> = dao.observeChildren(id)
    fun observeTrash(): Flow<List<LocationEntity>> = dao.observeDeleted()

    suspend fun get(id: String): LocationEntity? = dao.getById(id)
    suspend fun path(id: String): List<LocationEntity> = dao.getPath(id)
    suspend fun descendantIds(id: String): List<String> = dao.getDescendantIds(id)

    /** A fresh install starts with a single "Garage" root so items always have a home. */
    suspend fun ensureRoot(): LocationEntity = rootMutex.withLock {
        dao.firstRoot() ?: create("Garage", LocationKind.GARAGE, parentId = null)
    }

    /** Falls back to the first root when [id] is missing or deleted. */
    suspend fun liveOrRoot(id: String?): LocationEntity =
        id?.let { dao.getById(it) }?.takeIf { it.deletedAt == null } ?: ensureRoot()

    suspend fun create(name: String, kind: LocationKind, parentId: String?): LocationEntity =
        create(LocationInput(kind, name), parentId)

    /** Creates a location; throws [DuplicateLocationException] for a repeated generated label (LOC-15). */
    suspend fun create(input: LocationInput, parentId: String?): LocationEntity = db.withTransaction {
        checkUnique(input, parentId, excludeId = "")
        val now = System.currentTimeMillis()
        val location = LocationEntity(
            id = UUID.randomUUID().toString(),
            name = input.label,
            code = uniqueCode(),
            kind = input.kind,
            parentId = parentId,
            createdAt = now,
            updatedAt = now,
            deviceId = settings.deviceId(),
        )
        dao.insert(location)
        location
    }

    /**
     * Renames or changes the wheel values / kind of [id]. The QR payload is the stable id, so
     * printed labels keep resolving whatever the new label is (LOC-14).
     */
    suspend fun update(id: String, input: LocationInput) = db.withTransaction {
        val loc = dao.getById(id) ?: return@withTransaction
        checkUnique(input, loc.parentId, excludeId = id)
        val label = if (input.wheels == null && input.name.isBlank()) loc.name else input.label
        dao.update(loc.copy(name = label, kind = input.kind, updatedAt = System.currentTimeMillis()))
    }

    /** LOC-15: only generated labels are policed; free text may repeat as in v1. */
    private suspend fun checkUnique(input: LocationInput, parentId: String?, excludeId: String) {
        val wheels = input.wheels ?: return
        if (dao.countSiblingsNamed(parentId, wheels.label, excludeId) > 0) throw DuplicateLocationException(wheels.label)
    }

    /** Re-parents [id]. Moving under itself or a descendant is refused. */
    suspend fun move(id: String, newParentId: String?): Boolean = db.withTransaction {
        val loc = dao.getById(id) ?: return@withTransaction false
        if (newParentId != null && newParentId in dao.getDescendantIds(id)) return@withTransaction false
        dao.update(loc.copy(parentId = newParentId, updatedAt = System.currentTimeMillis()))
        true
    }

    suspend fun deleteBlockers(id: String) =
        DeleteBlockers(itemCount = dao.liveItemCount(id), childCount = dao.liveChildCount(id))

    /** LOC-7: only an empty location can be deleted; returns false if it still has contents. */
    suspend fun softDelete(id: String): Boolean = db.withTransaction {
        if (!deleteBlockers(id).isEmpty) return@withTransaction false
        dao.softDelete(id, System.currentTimeMillis())
        true
    }

    /** LOC-7: moves direct children and items into [targetId], then deletes [id]. */
    suspend fun reassignAndDelete(id: String, targetId: String): Boolean = db.withTransaction {
        if (targetId in dao.getDescendantIds(id)) return@withTransaction false
        val now = System.currentTimeMillis()
        dao.getChildren(id).forEach { dao.update(it.copy(parentId = targetId, updatedAt = now)) }
        itemDao.liveIdsInLocation(id).forEach { itemDao.setLocation(it, targetId, now) }
        dao.softDelete(id, now)
        true
    }

    /** Restores [id] and any deleted ancestors; re-homes it under a root if its parent is gone. */
    suspend fun restore(id: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        var cur = dao.getById(id) ?: return@withTransaction
        while (true) {
            if (cur.deletedAt != null) dao.restore(cur.id, now)
            val parentId = cur.parentId ?: break
            val parent = dao.getById(parentId)
            if (parent == null) {
                val rootId = dao.firstRoot()?.id?.takeIf { it != cur.id }
                dao.update(cur.copy(parentId = rootId, deletedAt = null, updatedAt = now))
                break
            }
            cur = parent
        }
    }

    /** Permanently removes a trashed location together with trashed items still pointing at it. */
    suspend fun deletePermanently(id: String, deleteItem: suspend (String) -> Unit) {
        itemDao.deletedIdsInLocation(id).forEach { deleteItem(it) }
        db.withTransaction {
            if (itemDao.liveIdsInLocation(id).isEmpty()) dao.hardDelete(id)
        }
    }

    /** LOC-3 / LOC-5: resolves raw scanner text or a typed short code to a location. */
    suspend fun resolveScan(raw: String): ScanResolution {
        val found = when (val parsed = QrPayload.parse(raw)) {
            is QrPayload.Parsed.Location -> dao.getById(parsed.id)
            is QrPayload.Parsed.ShortCode -> dao.getByCode(parsed.code)
            is QrPayload.Parsed.UnsupportedVersion -> return ScanResolution.Error(
                "This label was made by a newer version of QR Garage (v${parsed.version}). Update the app to use it."
            )
            QrPayload.Parsed.NotOurs -> return ScanResolution.Error("That code isn't a QR Garage location label.")
            QrPayload.Parsed.Malformed -> return ScanResolution.Error("That label looks damaged or invalid. Try entering its code.")
        }
        return when {
            found == null -> ScanResolution.Error("No location matches that label. It may have been permanently deleted.")
            found.deletedAt != null -> ScanResolution.InTrash(found)
            else -> ScanResolution.Found(found)
        }
    }

    private suspend fun uniqueCode(): String {
        repeat(50) {
            val code = buildString {
                repeat(QrPayload.CODE_LENGTH) {
                    append(QrPayload.CODE_ALPHABET[random.nextInt(QrPayload.CODE_ALPHABET.length)])
                }
            }
            if (dao.countWithCode(code) == 0) return code
        }
        error("Could not allocate a unique location code")
    }
}
