package com.example.qrgarage.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import com.example.qrgarage.BuildConfig
import com.example.qrgarage.data.db.AppDatabase
import com.example.qrgarage.data.photos.PhotoStorage
import com.example.qrgarage.data.settings.BackupPhotoSummary
import com.example.qrgarage.data.settings.BackupStatus
import com.example.qrgarage.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** BK-2: describes a backup archive. Also mirrored into Drive appProperties for the restore list. */
@Serializable
data class BackupManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val appVersionCode: Int,
    val schemaVersion: Int,
    val createdAt: Long,
    val deviceId: String,
    val deviceName: String,
    val itemCount: Int,
    val photoCount: Int,
    val includesPhotos: Boolean,
    /** BK-7: photos referenced by the database whose files were already missing on the device. */
    val missingPhotoCount: Int = 0,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** One entry in the SET-BK-6 restore list. */
data class RemoteBackup(
    val fileId: String,
    val createdAt: Long,
    val deviceName: String,
    val itemCount: Int?,
    val schemaVersion: Int?,
    val includesPhotos: Boolean?,
    val photoCount: Int?,
    val sizeBytes: Long?,
    val isThisDevice: Boolean,
) {
    val isCompatible: Boolean get() = (schemaVersion ?: 0) <= AppDatabase.VERSION
}

class RestoreRefusedException(message: String) : Exception(message)

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val photos: PhotoStorage,
    private val settings: SettingsRepository,
    private val auth: DriveAuth,
    private val drive: DriveClient,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val workDir get() = File(context.cacheDir, "backup").apply { mkdirs() }

    /** Full backup: snapshot, zip, upload, prune (BK-1, BK-3, SET-BK-5). */
    suspend fun backUpNow() {
        settings.recordBackupStatus(BackupStatus.RUNNING)
        val cfg = settings.settings.first().backup
        val zip = File(workDir, "upload.zip")
        try {
            val manifest = createArchive(zip, cfg.includePhotos)
            withToken { token ->
                val name = "${DriveClient.BACKUP_PREFIX}${fileStamp(manifest.createdAt)}.zip"
                drive.upload(token, zip, name, manifest.toAppProperties())
                prune(token, cfg.keepCount)
            }
            settings.recordBackupStatus(
                BackupStatus.SUCCESS, at = manifest.createdAt,
                photos = BackupPhotoSummary(manifest.includesPhotos, manifest.photoCount, manifest.missingPhotoCount),
            )
        } catch (t: Throwable) {
            settings.recordBackupStatus(BackupStatus.FAILED, error = t.message ?: t.javaClass.simpleName)
            throw t
        } finally {
            zip.delete()
        }
    }

    suspend fun listBackups(): List<RemoteBackup> {
        val myDevice = settings.deviceId()
        return withToken { token -> drive.listBackups(token) }.map { f ->
            val p = f.appProperties
            RemoteBackup(
                fileId = f.id,
                createdAt = p["createdAt"]?.toLongOrNull()
                    ?: f.createdTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
                deviceName = p["deviceName"] ?: "Unknown device",
                itemCount = p["itemCount"]?.toIntOrNull(),
                schemaVersion = p["schemaVersion"]?.toIntOrNull(),
                includesPhotos = p["includesPhotos"]?.toBooleanStrictOrNull(),
                photoCount = p["photoCount"]?.toIntOrNull(),
                sizeBytes = f.size?.toLongOrNull(),
                isThisDevice = p["deviceId"] == myDevice,
            )
        }.sortedByDescending { it.createdAt }
    }

    /**
     * BK-4: downloads [fileId], verifies its manifest and database, and stages it. The swap
     * itself happens on the next process start ([RestoreSwap]); the caller restarts the app.
     */
    suspend fun stageRestore(fileId: String): BackupManifest {
        val zip = File(workDir, "restore.zip")
        val staging = RestoreSwap.stagingDir(context)
        try {
            withToken { token -> drive.download(token, fileId, zip) }
            return withContext(Dispatchers.IO) {
                staging.deleteRecursively()
                staging.mkdirs()
                extract(zip, staging)
                val manifestFile = File(staging, MANIFEST)
                if (!manifestFile.exists()) throw RestoreRefusedException("This file is not a QR Garage backup.")
                val manifest = json.decodeFromString<BackupManifest>(manifestFile.readText())
                checkCompatible(manifest)
                // A photo-including backup replaces the photo folder on swap, even when empty.
                if (manifest.includesPhotos) File(staging, RestoreSwap.STAGED_PHOTOS).mkdirs()
                validateDatabase(File(staging, RestoreSwap.STAGED_DB), manifest.schemaVersion)
                RestoreSwap.markStaged(context)
                manifest
            }
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        } finally {
            zip.delete()
        }
    }

    /**
     * Writes the BK-1 archive: consistent DB snapshot, photos (optional), manifest.json. Photos
     * are the files the database references (BK-7); orphaned files are left out.
     */
    suspend fun createArchive(dest: File, includePhotos: Boolean): BackupManifest = withContext(Dispatchers.IO) {
        val snapshot = File(workDir, "snapshot.db")
        snapshot.delete()
        try {
            snapshotDatabase(snapshot)
            validateDatabase(snapshot, AppDatabase.VERSION)
            val referenced = db.photoDao().allPaths().map(photos::fileFor)
            val (present, missing) = referenced.partition { it.isFile }
            val photoFiles = if (includePhotos) present else emptyList()
            val manifest = BackupManifest(
                appVersion = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                schemaVersion = AppDatabase.VERSION,
                createdAt = System.currentTimeMillis(),
                deviceId = settings.deviceId(),
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                itemCount = db.itemDao().liveCount(),
                photoCount = photoFiles.size,
                includesPhotos = includePhotos,
                missingPhotoCount = if (includePhotos) missing.size else 0,
            )
            ZipOutputStream(dest.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(RestoreSwap.STAGED_DB))
                snapshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                photoFiles.forEach { f ->
                    zip.putNextEntry(ZipEntry("${RestoreSwap.STAGED_PHOTOS}/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            manifest
        } finally {
            snapshot.delete()
        }
    }

    /**
     * BK-1: a consistent copy of the live database. `VACUUM INTO` (SQLite 3.27, API 30+) copies
     * from a single read transaction. Older devices checkpoint the WAL into the main file first.
     */
    private fun snapshotDatabase(dest: File) {
        val live = db.openHelper.writableDatabase
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            live.execSQL("VACUUM INTO ?", arrayOf(dest.absolutePath))
        } else {
            live.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c ->
                if (c.moveToFirst() && c.getInt(0) != 0) throw IOException("Database busy, try again")
            }
            context.getDatabasePath(AppDatabase.NAME).copyTo(dest, overwrite = true)
        }
    }

    private fun validateDatabase(file: File, expectedVersion: Int) {
        if (!file.exists()) throw RestoreRefusedException("The backup has no database.")
        val sdb = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            sdb.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (!c.moveToFirst() || c.getString(0) != "ok") throw RestoreRefusedException("The backup database is damaged.")
            }
            if (sdb.version != expectedVersion) {
                throw RestoreRefusedException("Backup schema mismatch (file v${sdb.version}, manifest v$expectedVersion).")
            }
            sdb.rawQuery("SELECT COUNT(*) FROM items", null).use { it.moveToFirst() }
        } finally {
            sdb.close()
        }
    }

    private fun checkCompatible(m: BackupManifest) {
        if (m.formatVersion > BackupManifest.FORMAT_VERSION || m.schemaVersion > AppDatabase.VERSION) {
            throw RestoreRefusedException(
                "This backup was made by a newer version of QR Garage (${m.appVersion}). Update the app to restore it."
            )
        }
    }

    /** Extracts with zip-slip protection; only expected entries are accepted. */
    private fun extract(zip: File, dir: File) {
        val root = dir.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                val allowed = entry.name == MANIFEST || entry.name == RestoreSwap.STAGED_DB ||
                    (entry.name.startsWith("${RestoreSwap.STAGED_PHOTOS}/") && entry.name.count { it == '/' } == 1)
                if (!allowed) continue
                val out = File(root, entry.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw RestoreRefusedException("Invalid backup file.")
                out.parentFile?.mkdirs()
                out.outputStream().use { zin.copyTo(it) }
            }
        }
    }

    private suspend fun prune(token: String, keep: Int) {
        drive.listBackups(token).sortedByDescending { it.createdTime }.drop(keep).forEach {
            drive.delete(token, it.id)
        }
    }

    /** Runs [block] with a token; on a 401 the cached token is dropped and retried once. */
    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = auth.tokenOrThrow()
        return try {
            block(token)
        } catch (e: AuthRequiredException) {
            auth.invalidate(token)
            block(auth.tokenOrThrow())
        }
    }

    private fun BackupManifest.toAppProperties() = mapOf(
        "createdAt" to createdAt.toString(),
        "deviceId" to deviceId,
        "deviceName" to deviceName.take(60),
        "itemCount" to itemCount.toString(),
        "schemaVersion" to schemaVersion.toString(),
        "includesPhotos" to includesPhotos.toString(),
        "photoCount" to photoCount.toString(),
        "appVersion" to appVersion,
    )

    private fun fileStamp(millis: Long) =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    companion object {
        const val MANIFEST = "manifest.json"
    }
}
