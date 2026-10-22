package com.example.qrgarage.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class DriveHttpException(val code: Int, message: String) : IOException("Drive error $code: $message")

@Serializable
data class DriveFile(
    val id: String,
    val name: String = "",
    val createdTime: String? = null,
    val size: String? = null,
    val appProperties: Map<String, String> = emptyMap(),
)

@Serializable
private data class DriveFileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

@Serializable
private data class DriveAbout(val user: DriveUser? = null)

@Serializable
private data class DriveUser(val emailAddress: String? = null, val displayName: String? = null)

/** Minimal Drive v3 REST client for the hidden `appDataFolder` (BK-3). */
@Singleton
class DriveClient @Inject constructor(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun accountEmail(token: String): String? = io {
        val url = "$API/about".toHttpUrl().newBuilder().addQueryParameter("fields", "user(emailAddress)").build()
        call(Request.Builder().url(url).auth(token).build()) { json.decodeFromString<DriveAbout>(it).user?.emailAddress }
    }

    suspend fun listBackups(token: String): List<DriveFile> = io {
        val out = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("q", "name contains '$BACKUP_PREFIX' and trashed = false")
                .addQueryParameter("orderBy", "createdTime desc")
                .addQueryParameter("pageSize", "100")
                .addQueryParameter("fields", "nextPageToken,files(id,name,createdTime,size,appProperties)")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = call(Request.Builder().url(url).auth(token).build()) {
                json.decodeFromString<DriveFileList>(it)
            }
            out += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        out
    }

    /** Resumable upload into `appDataFolder`; works for large photo backups. */
    suspend fun upload(token: String, file: File, name: String, appProperties: Map<String, String>): String = io {
        val metadata = buildJsonObject {
            put("name", name)
            putJsonArray("parents") { add("appDataFolder") }
            putJsonObject("appProperties") { appProperties.forEach { (k, v) -> put(k, v) } }
        }.toString()
        val start = Request.Builder()
            .url("$UPLOAD/files?uploadType=resumable&fields=id")
            .auth(token)
            .header("X-Upload-Content-Type", ZIP)
            .header("X-Upload-Content-Length", file.length().toString())
            .post(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        val session = http.newCall(start).execute().use { r ->
            r.ensureOk()
            r.header("Location") ?: throw IOException("Drive did not return an upload session")
        }
        val put = Request.Builder().url(session).auth(token).put(file.asRequestBody(ZIP.toMediaType())).build()
        call(put) { json.decodeFromString<DriveFile>(it).id }
    }

    suspend fun download(token: String, fileId: String, dest: File) = io {
        val req = Request.Builder().url("$API/files/$fileId?alt=media").auth(token).build()
        http.newCall(req).execute().use { r ->
            r.ensureOk()
            dest.outputStream().use { out -> r.body.byteStream().copyTo(out) }
        }
    }

    suspend fun delete(token: String, fileId: String) = io {
        val req = Request.Builder().url("$API/files/$fileId").auth(token).delete().build()
        http.newCall(req).execute().use { r -> if (r.code != 404) r.ensureOk() }
    }

    private fun <T> call(request: Request, parse: (String) -> T): T =
        http.newCall(request).execute().use { r ->
            r.ensureOk()
            parse(r.body.string())
        }

    private fun Response.ensureOk() {
        if (code == 401) throw AuthRequiredException()
        if (!isSuccessful) throw DriveHttpException(code, body.string().take(300))
    }

    private fun Request.Builder.auth(token: String) = header("Authorization", "Bearer $token")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val ZIP = "application/zip"
        const val BACKUP_PREFIX = "qrgarage-backup-"
    }
}
