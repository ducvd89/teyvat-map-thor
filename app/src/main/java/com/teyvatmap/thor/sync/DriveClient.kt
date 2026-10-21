package com.teyvatmap.thor.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class DriveException(message: String, val httpCode: Int) : IOException(message)

/** HTTP 401: the access token was rejected (expired / revoked). */
class DriveAuthException(message: String) : IOException(message)

data class DriveFile(val id: String, val modifiedTime: String?)

/**
 * Minimal Drive v3 client for the hidden `appDataFolder` (PLAN §7): find / download / create / update one file.
 * Plain OkHttp, no Google client library. [token] is asked for a fresh access token for every call (and once more
 * after a 401). [http] must not carry other services' header interceptors. Transient errors (429, 5xx, I/O) are
 * retried with exponential backoff.
 */
class DriveClient(
    private val http: OkHttpClient,
    private val token: suspend () -> String,
    private val apiBase: String = "https://www.googleapis.com",
    private val retries: Int = 3,
    private val backoffMs: Long = 500,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The oldest file called [name] in the app data folder, or null. */
    suspend fun find(name: String = FILE_NAME): DriveFile? {
        val url = apiBase.toHttpUrl().newBuilder().addPathSegments("drive/v3/files")
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '$name' and trashed = false")
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .addQueryParameter("orderBy", "createdTime")
            .addQueryParameter("pageSize", "10")
            .build()
        val body = call(url) { get() }
        val files = json.parseToJsonElement(body.decodeToString()).jsonObject["files"]?.jsonArray.orEmpty()
        val f = files.firstOrNull()?.jsonObject ?: return null
        return DriveFile(f.getValue("id").jsonPrimitive.content, f["modifiedTime"]?.jsonPrimitive?.content)
    }

    suspend fun download(fileId: String): ByteArray {
        val url = apiBase.toHttpUrl().newBuilder().addPathSegments("drive/v3/files/$fileId").addQueryParameter("alt", "media").build()
        return call(url) { get() }
    }

    /** Multipart create into `appDataFolder`. */
    suspend fun create(name: String, content: ByteArray): DriveFile {
        val url = apiBase.toHttpUrl().newBuilder().addPathSegments("upload/drive/v3/files")
            .addQueryParameter("uploadType", "multipart").addQueryParameter("fields", "id,modifiedTime").build()
        val meta = """{"name":"$name","parents":["appDataFolder"]}"""
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(content.toRequestBody(MEDIA_TYPE))
            .build()
        return parseFile(call(url) { post(body) })
    }

    /** Replaces the content of an existing file (PATCH uploadType=media). */
    suspend fun update(fileId: String, content: ByteArray): DriveFile {
        val url = apiBase.toHttpUrl().newBuilder().addPathSegments("upload/drive/v3/files/$fileId")
            .addQueryParameter("uploadType", "media").addQueryParameter("fields", "id,modifiedTime").build()
        return parseFile(call(url) { patch(content.toRequestBody(MEDIA_TYPE)) })
    }

    private fun parseFile(body: ByteArray): DriveFile {
        val o: JsonObject = json.parseToJsonElement(body.decodeToString()).jsonObject
        return DriveFile(o.getValue("id").jsonPrimitive.content, o["modifiedTime"]?.jsonPrimitive?.content)
    }

    private suspend fun call(url: HttpUrl, configure: Request.Builder.() -> Unit): ByteArray {
        var attempt = 0
        var refreshed = false
        while (true) {
            try {
                return withContext(Dispatchers.IO) {
                    val rb = Request.Builder().url(url).header("Authorization", "Bearer ${token()}")
                    rb.configure()
                    http.newCall(rb.build()).execute().use { resp ->
                        val bytes = resp.body.bytes()
                        when {
                            resp.isSuccessful -> bytes
                            resp.code == 401 -> throw DriveAuthException("HTTP 401: ${snippet(bytes)}")
                            else -> throw DriveException("HTTP ${resp.code}: ${snippet(bytes)}", resp.code)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthException) {
                if (refreshed) throw e
                refreshed = true // the token provider hands out a fresh token on the next call
            } catch (e: DriveException) {
                if (!(e.httpCode == 429 || e.httpCode in 500..599) || attempt >= retries) throw e
                delay(backoffMs shl attempt++)
            } catch (e: IOException) {
                if (attempt >= retries) throw e
                delay(backoffMs shl attempt++)
            }
        }
    }

    private fun snippet(b: ByteArray) = b.decodeToString().take(200)

    companion object {
        const val FILE_NAME = "marks.json"
        private val MEDIA_TYPE = "application/gzip".toMediaType()
    }
}
