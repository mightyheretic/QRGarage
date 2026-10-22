package com.example.qrgarage.backup

import android.content.Context
import android.util.Log
import com.example.qrgarage.data.db.AppDatabase
import com.example.qrgarage.data.photos.PhotoStorage
import kotlinx.serialization.json.Json
import java.io.File

/**
 * BK-4: atomic restore swap, performed at process start before Room opens the database.
 *
 * [RestoreManager] extracts and validates a backup into [stagingDir] and writes a marker, then
 * restarts the app. Here the live database and photos are moved aside to [preRestoreDir], the
 * staged copies are moved into place, and the result is opened and migrated. On success the
 * pre-restore copies are deleted; on any failure they are moved back.
 */
object RestoreSwap {
    private const val TAG = "RestoreSwap"
    private const val MARKER = "restore-ready"
    private const val FAILED = "restore-failed"
    const val STAGED_DB = "garage.db"
    const val STAGED_PHOTOS = "photos"
    private val json = Json { ignoreUnknownKeys = true }

    fun stagingDir(context: Context) = File(context.filesDir, "restore-staging")
    private fun preRestoreDir(context: Context) = File(context.filesDir, "pre-restore")

    fun markStaged(context: Context) {
        File(stagingDir(context), MARKER).writeText(System.currentTimeMillis().toString())
    }

    /** Returns and clears the reason of the last failed restore, if any. */
    fun consumeFailure(context: Context): String? {
        val f = File(context.filesDir, FAILED)
        if (!f.exists()) return null
        return f.readText().also { f.delete() }
    }

    fun applyIfStaged(context: Context, verify: () -> Unit) {
        val staging = stagingDir(context)
        if (!File(staging, MARKER).exists()) return

        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        val photosDir = File(context.filesDir, PhotoStorage.DIR)
        val stagedDb = File(staging, STAGED_DB)
        val stagedPhotos = File(staging, STAGED_PHOTOS)
        val pre = preRestoreDir(context).apply { deleteRecursively(); mkdirs() }
        val preDb = File(pre, "db").apply { mkdirs() }
        val prePhotos = File(pre, PhotoStorage.DIR)

        try {
            dbFiles(dbFile).filter { it.exists() }.forEach { moveOrThrow(it, File(preDb, it.name)) }
            moveOrThrow(stagedDb, dbFile)
            if (stagedPhotos.exists()) {
                if (photosDir.exists()) moveOrThrow(photosDir, prePhotos)
                moveOrThrow(stagedPhotos, photosDir)
            }
            verify()
            // BK-9: record which photos came back; a failed audit never undoes a good restore.
            runCatching {
                val manifest = File(staging, BackupManager.MANIFEST).takeIf { it.exists() }
                    ?.let { json.decodeFromString<BackupManifest>(it.readText()) }
                RestoreReport.write(context, dbFile, photosDir, manifest)
            }.onFailure { Log.w(TAG, "Restore report failed", it) }
            pre.deleteRecursively()
            staging.deleteRecursively()
            Log.i(TAG, "Restore applied")
        } catch (t: Throwable) {
            Log.e(TAG, "Restore failed, rolling back", t)
            dbFiles(dbFile).forEach { it.delete() }
            preDb.listFiles()?.forEach { it.renameTo(File(dbFile.parentFile, it.name)) }
            if (prePhotos.exists()) {
                photosDir.deleteRecursively()
                prePhotos.renameTo(photosDir)
            }
            pre.deleteRecursively()
            staging.deleteRecursively()
            File(context.filesDir, FAILED).writeText(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun dbFiles(db: File) = listOf(db, File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal"))

    private fun moveOrThrow(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw IllegalStateException("Could not move ${from.name}")
    }
}
