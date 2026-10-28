package com.oxygenupdater.workers

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkedDownloadTest {

    private val data = Random(42).nextBytes(1000)

    @Test
    fun `chunks cover the whole file exactly`() {
        for ((total, chunkSize) in listOf(0L to 10L, 1L to 10L, 10L to 10L, 11L to 10L, 1000L to 64L)) {
            val chunks = DownloadChunks.create(total, chunkSize)
            var expectedStart = 0L
            for (i in 0 until chunks.count) {
                assertEquals(expectedStart, chunks.start(i), "start of chunk $i ($total/$chunkSize)")
                assertTrue(chunks.length(i) in 0..chunkSize, "length of chunk $i ($total/$chunkSize)")
                expectedStart += chunks.length(i)
            }
            assertEquals(total, expectedStart, "sum of chunk lengths ($total/$chunkSize)")
        }
    }

    @Test
    fun `serialized state round-trips`() {
        val chunks = DownloadChunks.create(1000, 64)
        chunks.add(0, 64)
        chunks.add(3, 10)
        chunks.add(15, 40) // last chunk is 1000 - 15 * 64 = 40 bytes long

        val parsed = DownloadChunks.parse(chunks.serialize())!!
        assertEquals(chunks.serialize(), parsed.serialize())
        assertEquals(114L, parsed.bytesDone())
        assertTrue(parsed.isComplete(0) && parsed.isComplete(15))
    }

    @Test
    fun `malformed state is rejected`() {
        assertNull(DownloadChunks.parse(null))
        assertNull(DownloadChunks.parse(""))
        assertNull(DownloadChunks.parse("1000,64"))
        assertNull(DownloadChunks.parse("1000,64,0")) // wrong chunk count
        assertNull(DownloadChunks.parse("100,64,0,abc"))
        assertNull(DownloadChunks.parse("100,64,65,0")) // more than chunk length
        assertNull(DownloadChunks.parse("100,0,0"))
    }

    @Test
    fun `downloads all chunks in parallel`() = runBlocking {
        val file = tempFile()
        val chunks = DownloadChunks.create(data.size.toLong(), 64)
        val ranges = Collections.synchronizedList(mutableListOf<String>())

        downloadChunks(file, chunks, connections = 4, retryDelayMs = 0) {
            ranges += it
            partialResponse(it)
        }

        assertContentEquals(data, file.readBytes())
        assertEquals(chunks.count, ranges.size)
        assertEquals(data.size.toLong(), chunks.bytesDone())
    }

    @Test
    fun `resumes only what is missing`() = runBlocking {
        val file = tempFile()
        file.writeBytes(data.copyOf().apply { fill(0, 100, 1000) }) // only first 100 bytes are valid

        val chunks = DownloadChunks.create(data.size.toLong(), 64)
        chunks.add(0, 64)
        chunks.add(1, 36)

        val ranges = Collections.synchronizedList(mutableListOf<String>())
        downloadChunks(file, chunks, connections = 2, retryDelayMs = 0) {
            ranges += it
            partialResponse(it)
        }

        assertContentEquals(data, file.readBytes())
        assertTrue("bytes=0-63" !in ranges)
        assertTrue("bytes=100-127" in ranges)
    }

    @Test
    fun `retries a chunk that fails midway`() = runBlocking {
        val file = tempFile()
        val chunks = DownloadChunks.create(data.size.toLong(), 256)
        val ranges = Collections.synchronizedList(mutableListOf<String>())

        downloadChunks(file, chunks, connections = 4, retryDelayMs = 0) {
            ranges += it
            // Break the first attempt of the second chunk after 10 bytes
            if (it == "bytes=256-511") partialResponse(it, failAfter = 10) else partialResponse(it)
        }

        assertContentEquals(data, file.readBytes())
        // Retry continues from where the broken attempt stopped
        assertTrue("bytes=266-511" in ranges, "$ranges")
    }

    @Test
    fun `fails if the server ignores Range`() {
        val chunks = DownloadChunks.create(data.size.toLong(), 64)
        assertFailsWith<IOException> {
            runBlocking {
                downloadChunks(tempFile(), chunks, connections = 1, retryDelayMs = 0) { Response.success(data.toResponseBody()) }
            }
        }
    }

    @Test
    fun `fails immediately on HTTP errors`() {
        val chunks = DownloadChunks.create(data.size.toLong(), 64)
        val requests = AtomicInteger()
        val e = assertFailsWith<HttpException> {
            runBlocking {
                downloadChunks(tempFile(), chunks, connections = 1, retryDelayMs = 0) {
                    requests.incrementAndGet()
                    Response.error(403, "".toResponseBody())
                }
            }
        }
        assertEquals(403, e.code())
        assertEquals(1, requests.get())
    }

    @Test
    fun `wraps request errors`() {
        val chunks = DownloadChunks.create(data.size.toLong(), 64)
        assertFailsWith<DownloadRequestException> {
            runBlocking {
                downloadChunks(tempFile(), chunks, connections = 1, retryDelayMs = 0) { throw IOException("no network") }
            }
        }
    }

    @Test
    fun `total size is read from Content-Range`() {
        assertEquals(1000L, partialResponse("bytes=0-0").totalBytesFromContentRange())
        assertNull(Response.success(data.toResponseBody()).totalBytesFromContentRange())
    }

    @Test
    fun `connection count follows transport and power state`() {
        val wifi = NetworkTransport.WifiOrEthernet
        assertEquals(4, downloadConnectionsFor(wifi, metered = false, powerSave = false))
        assertEquals(2, downloadConnectionsFor(wifi, metered = true, powerSave = false)) // e.g. phone hotspot
        assertEquals(2, downloadConnectionsFor(NetworkTransport.Cellular, metered = true, powerSave = false))
        assertEquals(2, downloadConnectionsFor(NetworkTransport.Cellular, metered = false, powerSave = false))
        assertEquals(2, downloadConnectionsFor(NetworkTransport.Other, metered = false, powerSave = false))

        for (transport in NetworkTransport.entries) {
            assertEquals(1, downloadConnectionsFor(transport, metered = false, powerSave = true), "$transport")
        }
    }

    @Test
    fun `forced connection count overrides everything, unless out of range`() {
        val wifi = NetworkTransport.WifiOrEthernet
        for (forced in 1..MaxDownloadConnections) {
            assertEquals(forced, downloadConnectionsFor(wifi, metered = false, powerSave = false, forced = forced))
            assertEquals(forced, downloadConnectionsFor(NetworkTransport.Cellular, metered = true, powerSave = true, forced = forced))
        }

        for (forced in listOf(DownloadConnectionsAuto, -1, MaxDownloadConnections + 1, Int.MAX_VALUE)) {
            assertEquals(4, downloadConnectionsFor(wifi, metered = false, powerSave = false, forced = forced), "forced=$forced")
            assertEquals(1, downloadConnectionsFor(wifi, metered = false, powerSave = true, forced = forced), "forced=$forced")
        }
    }

    private fun tempFile() = File.createTempFile("chunked", ".zip").apply { deleteOnExit() }

    /** Serves [rangeHeader] (`bytes=from-to`) from [data], optionally throwing after [failAfter] bytes */
    private fun partialResponse(rangeHeader: String, failAfter: Int = -1): Response<ResponseBody> {
        val (from, to) = rangeHeader.removePrefix("bytes=").split('-').map { it.toInt() }
        val bytes = data.copyOfRange(from, to + 1)

        val stream: InputStream = if (failAfter < 0) bytes.inputStream() else object : InputStream() {
            var position = 0
            override fun read() = if (position < failAfter) bytes[position++].toInt() and 0xFF else throw IOException("reset")
        }

        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = bytes.size.toLong()
            override fun source(): BufferedSource = stream.source().buffer()
        }

        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://example.com/ota.zip").build())
            .protocol(Protocol.HTTP_1_1)
            .code(206)
            .message("Partial Content")
            .header("Content-Range", "bytes $from-$to/${data.size}")
            .build()

        return Response.success(body, raw)
    }
}
