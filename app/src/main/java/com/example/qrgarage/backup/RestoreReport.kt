package com.example.qrgarage.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class MissingPhoto(val itemId: String, val itemName: String, val relativePath: String)

/**
 * BK-9: what a restore brought back. Written by [RestoreSwap] right after a successful swap,
 * announced once on the next launch and kept on the Backup screen until dismissed, so photos
 * that could not be restored are reported instead of silently showing as blank images.
 */
@Serializable
data class RestoreReport(
    val restoredAt: Long,
    val backupCreatedAt: Long? = null,
    val backupIncludedPhotos: Boolean,
    val restoredPhotoCount: Int,
    val missing: List<MissingPhoto>,
    val announced: Boolean = false,
) {
    val summary: String
        get() = when {
            missing.isEmpty() -> "Restore complete. $restoredPhotoCount photo${plural(restoredPhotoCount)} restored."
            !backupIncludedPhotos ->
                "Restore complete, but the backup didn't include photos: ${missing.size} photo${plural(missing.size)} " +
                    "missing (${names()})."
            else -> "Restore complete, but ${missing.size} photo${plural(missing.size)} couldn't be restored (${names()})."
        }

    private fun names(limit: Int = 3): String {
        val items = missing.map { it.itemName }.distinct()
        val shown = items.take(limit).joinToString(", ")
        return if (items.size > limit) "$shown and ${items.size - limit} more" else shown
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    companion object {
        private const val FILE = "restore-report.json"
        private val json = Json { ignoreUnknownKeys = true }

        private fun file(context: Context) = File(context.filesDir, FILE)

        fun read(context: Context): RestoreReport? =
            runCatching { json.decodeFromString<RestoreReport>(file(context).readText()) }.getOrNull()

        /** Returns the report once, the first time it is asked for after a restore. */
        fun consumeAnnouncement(context: Context): RestoreReport? {
            val report = read(context)?.takeIf { !it.announced } ?: return null
            save(context, report.copy(announced = true))
            return report
        }

        fun dismiss(context: Context) {
            file(context).delete()
        }

        private fun save(context: Context, report: RestoreReport) {
            file(context).writeText(json.encodeToString(serializer(), report))
        }

        /** Audits the swapped-in database against the swapped-in photo folder. */
        fun write(context: Context, dbFile: File, photosDir: File, manifest: BackupManifest?) {
            val referenced = mutableListOf<MissingPhoto>()
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                db.rawQuery(
                    "SELECT p.itemId, i.name, p.relativePath FROM photos p JOIN items i ON i.id = p.itemId " +
                        "WHERE p.deletedAt IS NULL AND i.deletedAt IS NULL ORDER BY i.name COLLATE NOCASE",
                    null,
                ).use { c ->
                    while (c.moveToNext()) referenced += MissingPhoto(c.getString(0), c.getString(1), c.getString(2))
                }
            } finally {
                db.close()
            }
            val (present, missing) = referenced.partition { File(photosDir, it.relativePath).isFile }
            save(
                context,
                RestoreReport(
                    restoredAt = System.currentTimeMillis(),
                    backupCreatedAt = manifest?.createdAt,
                    backupIncludedPhotos = manifest?.includesPhotos ?: true,
                    restoredPhotoCount = present.size,
                    missing = missing,
                ),
            )
        }
    }
}
