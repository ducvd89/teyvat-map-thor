package com.teyvatmap.thor.sync

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DriveClientTest {
    private lateinit var server: MockWebServer
    private val tokens = AtomicInteger(0)

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.close() }

    private fun client(retries: Int = 2) = DriveClient(
        OkHttpClient(), token = { "tok${tokens.incrementAndGet()}" },
        apiBase = server.url("/").toString().trimEnd('/'), retries = retries, backoffMs = 1,
    )

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test fun findListsAppDataFolderAndSendsBearerToken() = runBlocking {
        server.enqueue(ok("""{"files":[{"id":"abc","name":"marks.json","modifiedTime":"2026-10-21T00:00:00Z"}]}"""))
        val f = client().find()
        assertEquals(DriveFile("abc", "2026-10-21T00:00:00Z"), f)
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/drive/v3/files", r.url.encodedPath)
        assertEquals("appDataFolder", r.url.queryParameter("spaces"))
        assertTrue(r.url.queryParameter("q")!!.contains("name = 'marks.json'"))
        assertEquals("Bearer tok1", r.headers["Authorization"])
    }

    @Test fun findReturnsNullWhenNoFile() = runBlocking {
        server.enqueue(ok("""{"files":[]}"""))
        assertNull(client().find())
    }

    @Test fun downloadUsesAltMedia() = runBlocking {
        val payload = MarksJson.gzip(MarksDoc(device = "d", marks = mapOf("1" to MarkEntry("F", 5))))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(payload)).build())
        val bytes = client().download("abc")
        assertArrayEquals(payload, bytes)
        assertEquals("F", MarksJson.decodeBytes(bytes).marks.getValue("1").s)
        val r = server.takeRequest()
        assertEquals("/drive/v3/files/abc", r.url.encodedPath)
        assertEquals("media", r.url.queryParameter("alt"))
    }

    @Test fun createIsMultipartIntoAppDataFolder() = runBlocking {
        server.enqueue(ok("""{"id":"new1","modifiedTime":"t"}"""))
        val f = client().create("marks.json", byteArrayOf(1, 2, 3))
        assertEquals("new1", f.id)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/upload/drive/v3/files", r.url.encodedPath)
        assertEquals("multipart", r.url.queryParameter("uploadType"))
        assertTrue(r.headers["Content-Type"]!!.startsWith("multipart/related"))
        val body = r.body!!.utf8()
        assertTrue(body.contains("\"parents\":[\"appDataFolder\"]"))
        assertTrue(body.contains("\"name\":\"marks.json\""))
    }

    @Test fun updateIsPatchWithMediaUpload() = runBlocking {
        server.enqueue(ok("""{"id":"abc","modifiedTime":"t2"}"""))
        client().update("abc", byteArrayOf(9, 8, 7))
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertEquals("/upload/drive/v3/files/abc", r.url.encodedPath)
        assertEquals("media", r.url.queryParameter("uploadType"))
        assertArrayEquals(byteArrayOf(9, 8, 7), r.body!!.toByteArray())
    }

    @Test fun retriesServerErrorsThenSucceeds() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).body("busy").build())
        server.enqueue(MockResponse.Builder().code(429).body("slow down").build())
        server.enqueue(ok("""{"files":[]}"""))
        assertNull(client(retries = 3).find())
        assertEquals(3, server.requestCount)
    }

    @Test fun givesUpAfterRetries() = runBlocking {
        repeat(5) { server.enqueue(MockResponse.Builder().code(500).body("x").build()) }
        try { client(retries = 2).find(); fail() } catch (e: DriveException) { assertEquals(500, e.httpCode) }
        assertEquals(3, server.requestCount)
    }

    @Test fun unauthorizedGetsOneFreshTokenThenFails() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("expired").build())
        server.enqueue(ok("""{"files":[]}"""))
        assertNull(client().find())
        assertEquals("Bearer tok1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer tok2", server.takeRequest().headers["Authorization"])

        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(401).build())
        try { client().find(); fail() } catch (_: DriveAuthException) { }
    }

    @Test fun clientErrorsAreNotRetried() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("nope").build())
        try { client().download("x"); fail() } catch (e: DriveException) { assertEquals(404, e.httpCode) }
        assertEquals(1, server.requestCount)
    }
}
