package com.example.qrgarage.data.repo

import com.example.qrgarage.data.db.AppDatabase
import com.example.qrgarage.data.db.LocationEntity
import com.example.qrgarage.data.db.LocationKind
import com.example.qrgarage.data.photos.PhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ImportResult(val imported: Int, val skipped: Int, val photos: Int, val missingPhotos: Int)

/**
 * SET-OTH-2 / SET-OTH-6: reads what [ExportRepository] writes: a ZIP (items.json or items.csv
 * plus photos/), or a bare CSV or JSON file. Import only adds: items whose id already exists
 * are skipped, never overwritten. Locations are matched by short code, then by path, and
 * missing path segments are created.
 */
@Singleton
class ImportRepository @Inject constructor(
    private val db: AppDatabase,
    private val items: ItemRepository,
    private val locations: LocationRepository,
    private val photoStorage: PhotoStorage,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun import(input: InputStream, workDir: File): ImportResult = withContext(Dispatchers.IO) {
        val dir = File(workDir, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val rows = readRows(BufferedInputStream(input), dir)
            apply(rows, File(dir, ExportRepository.PHOTO_DIR))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun readRows(input: BufferedInputStream, dir: File): List<ExportedItem> {
        input.mark(4)
        val magic = ByteArray(2).also { input.read(it) }
        input.reset()
        if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
            extract(input, dir)
            File(dir, ExportRepository.JSON_ENTRY).takeIf { it.exists() }?.let { return parseJson(it.readText()) }
            File(dir, ExportRepository.CSV_ENTRY).takeIf { it.exists() }?.let { return parseCsv(it.readText()) }
            throw IOException("The ZIP has no items.json or items.csv")
        }
        val text = input.bufferedReader().readText().removePrefix("﻿")
        return if (text.trimStart().startsWith("[")) parseJson(text) else parseCsv(text)
    }

    /** Zip-slip safe; keeps only the data files and photos/<file>. */
    private fun extract(input: InputStream, dir: File) {
        val root = dir.canonicalFile
        ZipInputStream(input).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                val allowed = name == ExportRepository.JSON_ENTRY || name == ExportRepository.CSV_ENTRY ||
                    (name.startsWith("${ExportRepository.PHOTO_DIR}/") && name.count { it == '/' } == 1)
                if (!allowed) continue
                val out = File(root, name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw IOException("Invalid ZIP entry: $name")
                out.parentFile?.mkdirs()
                out.outputStream().use { zin.copyTo(it) }
            }
        }
    }

    private fun parseJson(text: String): List<ExportedItem> = json.decodeFromString(text)

    private fun parseCsv(text: String): List<ExportedItem> {
        val records = Csv.parse(text)
        if (records.isEmpty()) return emptyList()
        val header = records.first().map { it.trim().lowercase() }
        val col = { row: List<String>, name: String -> header.indexOf(name).takeIf { it >= 0 }?.let(row::getOrNull)?.takeIf { it.isNotEmpty() } }
        if ("name" !in header) throw IOException("The CSV needs at least a “name” column")
        return records.drop(1).filter { r -> r.any { it.isNotBlank() } }.map { r ->
            ExportedItem(
                id = col(r, "id") ?: UUID.randomUUID().toString(),
                name = col(r, "name").orEmpty(),
                description = col(r, "description").orEmpty(),
                location = col(r, "location").orEmpty(),
                locationCode = col(r, "location_code"),
                quantity = col(r, "quantity")?.toDoubleOrNull(),
                unit = col(r, "unit"),
                category = col(r, "category"),
                tags = col(r, "tags")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                purchaseDate = col(r, "purchase_date"),
                purchasePrice = col(r, "purchase_price"),
                notes = col(r, "notes").orEmpty(),
                lowStockThreshold = col(r, "low_stock_threshold")?.toDoubleOrNull(),
                lentTo = col(r, "lent_to"),
                photoCount = 0,
                photos = col(r, "photo_files")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            )
        }
    }

    private suspend fun apply(rows: List<ExportedItem>, photoDir: File): ImportResult {
        var imported = 0
        var skipped = 0
        var photos = 0
        var missingPhotos = 0
        val resolved = mutableMapOf<String, String>()
        for (row in rows) {
            if (row.name.isBlank() || items.get(row.id) != null) {
                skipped++
                continue
            }
            val locationId = resolved.getOrPut("${row.locationCode}|${row.location}") { resolveLocation(row) }
            val photoPaths = row.photos.take(PhotoStorage.MAX_PER_ITEM).mapNotNull { path ->
                val source = File(photoDir, path.substringAfterLast('/'))
                if (!source.isFile) {
                    missingPhotos++
                    return@mapNotNull null
                }
                val name = "${UUID.randomUUID()}.jpg"
                source.copyTo(photoStorage.fileFor(name))
                photos++
                name
            }
            val category = row.category?.trim()?.takeIf { it.isNotEmpty() }
            items.save(
                ItemDraft(
                    id = row.id,
                    name = row.name,
                    description = row.description,
                    locationId = locationId,
                    quantity = row.quantity,
                    unit = row.unit?.takeIf { row.quantity != null },
                    categoryId = category?.let { db.categoryDao().getByName(it)?.id },
                    newCategoryName = category?.takeIf { db.categoryDao().getByName(it) == null },
                    purchaseDate = row.purchaseDate?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() },
                    purchasePriceCents = row.purchasePrice?.let { p ->
                        runCatching { BigDecimal(p.trim()).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact() }.getOrNull()
                    },
                    notes = row.notes,
                    lowStockThreshold = row.lowStockThreshold,
                    tags = row.tags,
                    photos = photoPaths,
                )
            )
            row.lentTo?.takeIf { it.isNotBlank() }?.let { items.lend(row.id, it, System.currentTimeMillis(), null) }
            imported++
        }
        return ImportResult(imported, skipped, photos, missingPhotos)
    }

    /** By live short code first; otherwise walks "Garage > Left wall > Shelf A Row 01", creating gaps. */
    private suspend fun resolveLocation(row: ExportedItem): String {
        row.locationCode?.let { code -> db.locationDao().getByCode(code)?.takeIf { it.deletedAt == null }?.let { return it.id } }
        val segments = row.location.split(" > ").map { it.trim() }.filter { it.isNotEmpty() }
        val matchedRoot = segments.firstOrNull()?.let { first ->
            db.locationDao().getAll().firstOrNull { it.parentId == null && it.name.equals(first, ignoreCase = true) }
        }
        var current: LocationEntity = matchedRoot ?: locations.ensureRoot()
        val rest = if (matchedRoot != null) segments.drop(1) else segments
        for (segment in rest) {
            val existing = db.locationDao().getChildren(current.id).firstOrNull { it.name.equals(segment, ignoreCase = true) }
            current = existing ?: run {
                val wheels = LocationLabel.parse(segment)
                val kind = when {
                    wheels?.bin != null -> LocationKind.BIN
                    wheels != null -> LocationKind.SHELF
                    else -> current.kind.childKind
                }
                locations.create(LocationInput(kind, segment, wheels), current.id)
            }
        }
        return current.id
    }
}

/** Minimal RFC 4180 reader: quoted fields, doubled quotes, embedded commas and newlines. */
internal object Csv {
    fun parse(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' && field.isEmpty() -> quoted = true
                c == ',' -> { record += field.toString(); field.clear() }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    record += field.toString(); field.clear()
                    records += record; record = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record += field.toString()
            records += record
        }
        return records
    }
}
