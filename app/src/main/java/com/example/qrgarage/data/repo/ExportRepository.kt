package com.example.qrgarage.data.repo

import com.example.qrgarage.data.db.AppDatabase
import com.example.qrgarage.data.photos.PhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ExportedItem(
    val id: String,
    val name: String,
    val description: String,
    val location: String,
    val locationCode: String?,
    val quantity: Double?,
    val unit: String?,
    val category: String?,
    val tags: List<String>,
    val purchaseDate: String?,
    val purchasePrice: String?,
    val notes: String,
    val lowStockThreshold: Double?,
    val lentTo: String?,
    val photoCount: Int,
    /** SET-OTH-6: paths inside an export ZIP, e.g. "photos/1f0c….jpg" (DB-4 relative paths). */
    val photos: List<String> = emptyList(),
)

/**
 * SET-OTH-1 / SET-OTH-6: human-readable export that does not depend on Drive. CSV and JSON carry
 * data only; [Format.ZIP] bundles items.json, items.csv and a photos/ folder, with each item's
 * photo paths relative to the archive root.
 */
@Singleton
class ExportRepository @Inject constructor(
    private val db: AppDatabase,
    private val photoStorage: PhotoStorage,
) {

    enum class Format(val mime: String, val extension: String) {
        CSV("text/csv", "csv"),
        JSON("application/json", "json"),
        ZIP("application/zip", "zip"),
    }

    suspend fun export(format: Format, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val rows = collect()
        when (format) {
            Format.JSON -> out.bufferedWriter().use { writeJson(rows, it) }
            Format.CSV -> out.bufferedWriter().use { writeCsv(rows, it) }
            Format.ZIP -> ZipOutputStream(out.buffered()).use { zip ->
                val writer = zip.writer()
                zip.putNextEntry(ZipEntry(JSON_ENTRY))
                writeJson(rows, writer)
                writer.flush()
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(CSV_ENTRY))
                writeCsv(rows, writer)
                writer.flush()
                zip.closeEntry()
                rows.flatMap { it.photos }.distinct().forEach { path ->
                    val file = photoStorage.fileFor(path.removePrefix("$PHOTO_DIR/"))
                    if (!file.isFile) return@forEach
                    zip.putNextEntry(ZipEntry(path))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        rows.size
    }

    private fun writeJson(rows: List<ExportedItem>, w: Writer) = w.write(json.encodeToString(rows))

    private fun writeCsv(rows: List<ExportedItem>, w: Writer) {
        w.write(HEADER.joinToString(",") + "\n")
        rows.forEach { r ->
            w.write(
                listOf(
                    r.id, r.name, r.description, r.location, r.locationCode,
                    r.quantity?.toString(), r.unit, r.category, r.tags.joinToString("; "),
                    r.purchaseDate, r.purchasePrice, r.notes, r.lowStockThreshold?.toString(),
                    r.lentTo, r.photoCount.toString(), r.photos.joinToString("; "),
                ).joinToString(",") { csv(it) } + "\n"
            )
        }
    }

    private suspend fun collect(): List<ExportedItem> {
        val tree = LocationTree(db.locationDao().getAll())
        val categories = mutableMapOf<String, String?>()
        return db.itemDao().getAllLive().map { item ->
            val photos = db.photoDao().getForItem(item.id)
            val category = item.categoryId?.let { id ->
                categories.getOrPut(id) { db.categoryDao().getById(id)?.name }
            }
            ExportedItem(
                id = item.id,
                name = item.name,
                description = item.description,
                location = tree.path(item.locationId).joinToString(" > ") { it.name },
                locationCode = tree[item.locationId]?.code,
                quantity = item.quantity,
                unit = item.unit,
                category = category,
                tags = db.tagDao().getForItem(item.id).map { it.name },
                purchaseDate = item.purchaseDate?.let {
                    DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC))
                },
                purchasePrice = item.purchasePriceCents?.let { "%d.%02d".format(it / 100, it % 100) },
                notes = item.notes,
                lowStockThreshold = item.lowStockThreshold,
                lentTo = db.loanDao().getActive(item.id)?.borrower,
                photoCount = photos.size,
                photos = photos.map { "$PHOTO_DIR/${it.relativePath}" },
            )
        }
    }

    private fun csv(value: String?): String {
        val v = value ?: return ""
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${v.replace("\"", "\"\"")}\"" else v
    }

    companion object {
        const val JSON_ENTRY = "items.json"
        const val CSV_ENTRY = "items.csv"
        const val PHOTO_DIR = "photos"
        private val json = Json { prettyPrint = true }
        val HEADER = listOf(
            "id", "name", "description", "location", "location_code", "quantity", "unit", "category",
            "tags", "purchase_date", "purchase_price", "notes", "low_stock_threshold", "lent_to", "photos",
            "photo_files",
        )
    }
}
