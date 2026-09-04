package com.loosewire.lightious.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class DownloadTransfer(
    val downloadedBytes: Long,
    val totalBytes: Long?,
)

internal class RetryableMediaDownloadException(message: String) : Exception(message)

internal class MediaDownloadClient : AutoCloseable {
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 30_000L
            requestTimeoutMillis = 0L
        }
    }

    suspend fun download(
        stream: MediaStream,
        target: DownloadTarget,
        yieldIfNeeded: () -> Unit = {},
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadTransfer {
        requireDownloadUrl(stream.url)
        target.partialFile.parentFile?.let { directory ->
            check(directory.mkdirs() || directory.isDirectory) { "Could not create the download directory." }
        }
        completedFinalTransfer(stream, target)?.let { transfer ->
            onProgress(transfer.downloadedBytes, transfer.totalBytes)
            return transfer
        }
        completedPartialTransfer(stream, target)?.let { transfer ->
            onProgress(transfer.downloadedBytes, transfer.totalBytes)
            promoteDownload(target)
            return transfer
        }
        val expectedBytes = stream.contentLength?.takeIf { it > 0L }
        if (expectedBytes != null && target.partialFile.length() > expectedBytes) {
            target.partialFile.delete()
        }
        val resumeAt = target.partialFile.length().coerceAtLeast(0L)
        return client.prepareGet(stream.url) {
            accept(ContentType.Any)
            header(HttpHeaders.AcceptEncoding, "identity")
            if (resumeAt > 0L) header(HttpHeaders.Range, "bytes=$resumeAt-")
        }.execute { response ->
            if (resumeAt > 0L && response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                target.partialFile.delete()
                throw RetryableMediaDownloadException(
                    "The saved partial was no longer resumable; restarting the download.",
                )
            }
            if (response.status != HttpStatusCode.OK && response.status != HttpStatusCode.PartialContent) {
                throw InvidiousApiException(
                    "Media download returned HTTP ${response.status.value}.",
                    statusCode = response.status.value,
                )
            }

            val append = resumeAt > 0L && response.status == HttpStatusCode.PartialContent
            if (append && !contentRangeStartsAt(response.headers[HttpHeaders.ContentRange], resumeAt)) {
                target.partialFile.delete()
                throw RetryableMediaDownloadException(
                    "The media server returned an invalid resume range; restarting the download.",
                )
            }
            val startingBytes = if (append) resumeAt else 0L
            val responseBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val totalBytes = contentRangeTotal(response.headers[HttpHeaders.ContentRange])
                ?: responseBytes?.let { length -> startingBytes + length }
                ?: stream.contentLength
            if (totalBytes != null && totalBytes > MAX_DOWNLOAD_BYTES) {
                throw IllegalStateException("This media file is too large to download safely.")
            }
            val remainingBytes = totalBytes?.minus(startingBytes)?.coerceAtLeast(0L)
            if (
                remainingBytes != null &&
                target.partialFile.parentFile?.usableSpace?.let { it < remainingBytes } == true
            ) {
                throw IllegalStateException("There is not enough free space for this download.")
            }

            var downloaded = startingBytes
            var lastReported = downloaded
            FileOutputStream(target.partialFile, append).buffered().use { output ->
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    downloaded += count
                    yieldIfNeeded()
                    if (downloaded > MAX_DOWNLOAD_BYTES) {
                        throw IllegalStateException("This media file is too large to download safely.")
                    }
                    if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                        onProgress(downloaded, totalBytes)
                        lastReported = downloaded
                    }
                }
                output.flush()
            }
            if (totalBytes != null && downloaded != totalBytes) {
                throw RetryableMediaDownloadException(
                    "The media download ended early; continuing from the saved partial.",
                )
            }
            onProgress(downloaded, totalBytes ?: downloaded)
            promoteDownload(target)
            DownloadTransfer(downloaded, totalBytes ?: downloaded)
        }
    }

    override fun close() {
        client.close()
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256 * 1024L
        const val MAX_DOWNLOAD_BYTES = 2_000_000_000L
    }
}

internal fun completedFinalTransfer(
    stream: MediaStream,
    target: DownloadTarget,
): DownloadTransfer? {
    val expectedBytes = stream.contentLength?.takeIf { it > 0L } ?: return null
    if (!target.finalFile.isFile || target.finalFile.length() != expectedBytes) return null
    return DownloadTransfer(expectedBytes, expectedBytes)
}

internal fun completedPartialTransfer(
    stream: MediaStream,
    target: DownloadTarget,
): DownloadTransfer? {
    val expectedBytes = stream.contentLength?.takeIf { it > 0L } ?: return null
    if (!target.partialFile.isFile || target.partialFile.length() != expectedBytes) return null
    return DownloadTransfer(expectedBytes, expectedBytes)
}

internal fun promoteDownload(target: DownloadTarget) {
    check(target.partialFile.isFile) { "Partial download is missing." }
    try {
        Files.move(
            target.partialFile.toPath(),
            target.finalFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            target.partialFile.toPath(),
            target.finalFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

private fun requireDownloadUrl(value: String) {
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
        "The media server returned an invalid download URL."
    }
}

private fun contentRangeStartsAt(value: String?, expectedStart: Long): Boolean {
    val match = CONTENT_RANGE.matchEntire(value.orEmpty()) ?: return false
    return match.groupValues[1].toLongOrNull() == expectedStart
}

private fun contentRangeTotal(value: String?): Long? {
    val match = CONTENT_RANGE.matchEntire(value.orEmpty()) ?: return null
    return match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
}

private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
