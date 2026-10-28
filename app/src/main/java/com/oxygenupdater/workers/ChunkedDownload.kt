package com.oxygenupdater.workers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.min

/**
 * OTA servers (e.g. Aliyun OSS, used by OPPO/OnePlus) limit throughput per connection, so a ZIP
 * is split into [ChunkSize] chunks which are fetched over several parallel connections (see [downloadConnectionsFor]).
 * Small chunks (instead of one big range per connection) keep all connections busy until the very end.
 *
 * Measured on a OnePlus Nord CE6 OTA link (desktop connection): ~11 MB/s with one connection, ~18 MB/s with four.
 */
const val ChunkSize = 32L * 1024 * 1024 // 32 MiB

/** Value of the `download_connections` pref that lets [downloadConnectionsFor] pick the count automatically */
const val DownloadConnectionsAuto = 0
const val MaxDownloadConnections = 8

/** Transport of the active network, as far as it matters for [downloadConnectionsFor] */
enum class NetworkTransport { WifiOrEthernet, Cellular, Other }

/**
 * Number of parallel connections to use for a download.
 *
 * [forced] (1..[MaxDownloadConnections], from the "Download connections" setting) overrides everything, so that
 * testers can compare counts on their own device. Otherwise: 1 in battery saver mode, 4 on unmetered Wi-Fi/Ethernet,
 * 2 on cellular, metered Wi-Fi (e.g. a phone hotspot) and anything unknown.
 *
 * CPU count is deliberately not a factor: the download is I/O-bound, and a handful of blocking reads plus
 * [RandomAccessFile] writes cost next to nothing even on a weak core. What extra connections do cost is more TCP flows
 * competing on a congested cellular link, and writes landing at several offsets at once on slow flash.
 *
 * Known limitation: below Android 10, a VPN network reports only `TRANSPORT_VPN` (the underlying Wi-Fi/cellular
 * transports were added in API 29), so VPN users on those versions get the conservative 2.
 */
fun downloadConnectionsFor(
    transport: NetworkTransport,
    metered: Boolean,
    powerSave: Boolean,
    forced: Int = DownloadConnectionsAuto,
) = when {
    forced in 1..MaxDownloadConnections -> forced
    powerSave -> 1
    transport == NetworkTransport.WifiOrEthernet && !metered -> 4
    else -> 2
}

private const val BufferSize = 64 * 1024
private const val ChunkRetries = 3
private const val ChunkRetryDelayMs = 1000L

/**
 * Tracks how many bytes of each chunk of a [totalBytes]-sized file have been written.
 * Thread-safe: each chunk is written by only one coroutine at a time, but progress is read from others.
 */
class DownloadChunks private constructor(
    val totalBytes: Long,
    val chunkSize: Long,
    private val written: AtomicLongArray,
) {

    val count get() = written.length()

    fun start(index: Int) = index * chunkSize
    fun length(index: Int) = min(chunkSize, totalBytes - start(index))
    fun done(index: Int) = written[index]
    fun isComplete(index: Int) = written[index] >= length(index)
    fun add(index: Int, bytes: Int) = written.addAndGet(index, bytes.toLong())

    fun bytesDone(): Long {
        var sum = 0L
        for (i in 0 until count) sum += written[i]
        return sum
    }

    /** Format: `totalBytes,chunkSize,done0,done1,...` */
    fun serialize() = buildString {
        append(totalBytes).append(',').append(chunkSize)
        for (i in 0 until count) append(',').append(written[i])
    }

    companion object {
        fun create(totalBytes: Long, chunkSize: Long = ChunkSize): DownloadChunks {
            require(totalBytes >= 0 && chunkSize > 0)
            val count = ((totalBytes + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
            return DownloadChunks(totalBytes, chunkSize, AtomicLongArray(count))
        }

        /** @return `null` if [value] is missing or malformed, in which case the download should start over */
        fun parse(value: String?): DownloadChunks? {
            val parts = value?.split(',')?.map { it.toLongOrNull() ?: return null } ?: return null
            if (parts.size < 3) return null

            val (totalBytes, chunkSize) = parts
            if (totalBytes < 0 || chunkSize <= 0) return null

            val chunks = create(totalBytes, chunkSize)
            if (parts.size - 2 != chunks.count) return null

            for (i in 0 until chunks.count) {
                val done = parts[i + 2]
                if (done < 0 || done > chunks.length(i)) return null
                chunks.written[i] = done
            }
            return chunks
        }
    }
}

/** Thrown when a request couldn't be made at all, i.e. there's no HTTP response to inspect */
class DownloadRequestException(cause: Throwable) : IOException(cause.message, cause)

/** Wraps any exception thrown by [block] (except cancellation) in [DownloadRequestException] */
inline fun <T> wrapRequestErrors(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw DownloadRequestException(e)
}

/**
 * Downloads every incomplete chunk in [chunks] into [file], over at most [connections] parallel requests.
 * Each request asks only for the remaining bytes of its chunk, so partially downloaded chunks are resumed.
 *
 * I/O errors are retried per chunk (up to [ChunkRetries] times) before giving up.
 *
 * @param fetch performs a GET request with the given `Range` header value
 * @throws HttpException if the server responds with an error code
 * @throws IOException if the server ignores the `Range` header, or on persistent I/O errors
 */
suspend fun downloadChunks(
    file: File,
    chunks: DownloadChunks,
    connections: Int,
    retryDelayMs: Long = ChunkRetryDelayMs,
    fetch: suspend (rangeHeader: String) -> Response<ResponseBody>,
) = coroutineScope {
    val pending = (0 until chunks.count).filterNot(chunks::isComplete)
    val next = AtomicInteger()

    repeat(min(connections, pending.size)) {
        launch(Dispatchers.IO) {
            while (true) {
                val index = pending.getOrNull(next.getAndIncrement()) ?: break

                var attempt = 0
                while (true) try {
                    downloadChunk(file, chunks, index, fetch)
                    break
                } catch (e: IOException) {
                    if (++attempt > ChunkRetries) throw e
                    delay(retryDelayMs * attempt)
                }
            }
        }
    }
}

private suspend fun downloadChunk(
    file: File,
    chunks: DownloadChunks,
    index: Int,
    fetch: suspend (rangeHeader: String) -> Response<ResponseBody>,
) {
    if (chunks.isComplete(index)) return

    val length = chunks.length(index)
    val from = chunks.start(index) + chunks.done(index)
    val to = chunks.start(index) + length - 1

    val response = wrapRequestErrors { fetch("bytes=$from-$to") }

    if (!response.isSuccessful) {
        response.errorBody()?.close()
        throw HttpException(response)
    }

    val body = response.body() ?: throw IOException("Empty body for chunk $index")
    if (response.code() != HttpURLConnection.HTTP_PARTIAL) {
        body.close()
        throw IOException("Server ignored Range header for chunk $index (HTTP ${response.code()})")
    }

    body.byteStream().use { stream ->
        RandomAccessFile(file, "rw").use { randomAccessFile ->
            randomAccessFile.seek(from)

            val buffer = ByteArray(BufferSize)
            while (!chunks.isComplete(index)) {
                currentCoroutineContext().ensureActive()

                val remaining = length - chunks.done(index)
                val bytes = stream.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                if (bytes < 0) throw EOFException("Chunk $index ended early ($remaining bytes left)")

                randomAccessFile.write(buffer, 0, bytes)
                chunks.add(index, bytes)
            }
        }
    }
}

/** @return total file size from a `206 Partial Content` response's `Content-Range` header, e.g. `bytes 0-0/1234` */
fun Response<*>.totalBytesFromContentRange() = if (code() == HttpURLConnection.HTTP_PARTIAL) {
    headers()["Content-Range"]?.substringAfterLast('/')?.toLongOrNull()
} else null
