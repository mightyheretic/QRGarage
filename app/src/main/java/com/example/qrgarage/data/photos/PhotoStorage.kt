package com.example.qrgarage.data.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * PHOTO-2 / DB-4: photos are downscaled JPEG files in app-private storage. The database only
 * stores paths relative to [photoDir], so a backup restored on another device still resolves.
 */
@Singleton
class PhotoStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val photoDir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    fun fileFor(relativePath: String): File = File(photoDir, relativePath)

    /** A FileProvider URI the camera app can write a full-size capture into. */
    fun newCaptureUri(): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "capture-${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Decodes, rotates per EXIF, downscales and compresses [uri]; returns the relative path. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // ITEM-16: a bounds-only decode always returns null, so only a failed open is an error.
        val stream = resolver.openInputStream(uri) ?: throw IOException("Cannot open image")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Cannot decode image")

        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)

        val scaled = scaleAndRotate(decoded, rotation)
        val name = "${UUID.randomUUID()}.jpg"
        File(photoDir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        if (uri.authority == "${context.packageName}.fileprovider") {
            runCatching { resolver.delete(uri, null, null) }
        }
        name
    }

    fun delete(relativePath: String) {
        fileFor(relativePath).delete()
    }

    /** Removes temporary camera captures left behind by cancelled flows. */
    fun clearCaptureCache() {
        File(context.cacheDir, "shared").listFiles { f -> f.name.startsWith("capture-") }
            ?.forEach { it.delete() }
    }

    private fun scaleAndRotate(src: Bitmap, rotation: Int): Bitmap {
        val longest = max(src.width, src.height)
        val scale = if (longest > MAX_EDGE) MAX_EDGE.toFloat() / longest else 1f
        if (scale == 1f && rotation == 0) return src
        val matrix = Matrix().apply {
            postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    companion object {
        const val DIR = "photos"
        const val MAX_EDGE = 1600
        const val QUALITY = 82
        const val MAX_PER_ITEM = 3
    }
}
