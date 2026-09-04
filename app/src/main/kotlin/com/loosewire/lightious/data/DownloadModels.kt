package com.loosewire.lightious.data

import java.security.MessageDigest
import java.util.Locale

enum class DownloadKind(val wireValue: String) {
    AUDIO("audio"),
    VIDEO("video"),
    ;

    companion object {
        fun fromWire(value: String): DownloadKind? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class DownloadState(val wireValue: String) {
    QUEUED("queued"),
    DOWNLOADING("downloading"),
    COMPLETE("complete"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWire(value: String): DownloadState? = entries.firstOrNull { it.wireValue == value }
    }
}

data class DownloadedMedia(
    val ownerDeviceId: String,
    val videoId: String,
    val title: String,
    val author: String,
    val authorId: String?,
    val lengthSeconds: Long,
    val kind: DownloadKind,
    val state: DownloadState,
    val fileName: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val streamFingerprint: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
    val updatedAt: Long,
    val isShort: Boolean = false,
) {
    val isPlayable: Boolean
        get() = !isShort && state == DownloadState.COMPLETE && fileName != null

    fun asVideoSummary(): VideoSummary = VideoSummary(
        videoId = videoId,
        title = title,
        author = author,
        lengthSeconds = lengthSeconds,
        viewCount = 0L,
        publishedText = if (kind == DownloadKind.AUDIO) "DOWNLOADED AUDIO" else "DOWNLOADED VIDEO",
        liveNow = false,
        authorId = authorId,
        isShort = isShort,
    )
}

data class DownloadPlan(
    val kind: DownloadKind,
    val stream: MediaStream,
)

internal fun selectDownloadPlan(
    details: VideoDetails,
    policy: PlaybackPolicy,
    audioLanguage: AudioLanguagePreference = AudioLanguagePreference.ORIGINAL,
): Result<DownloadPlan> = runCatching {
    require(!details.summary.isShort) { SHORTS_BLOCKED_MESSAGE }
    require(!details.summary.liveNow && details.selection.liveHls == null) {
        "Live and HLS videos cannot be downloaded."
    }
    when (policy) {
        PlaybackPolicy.LISTEN_ONLY -> {
            val audio = details.selection.adaptiveAudio
                ?.takeIf { stream -> stream.kind == MediaStreamKind.ADAPTIVE_AUDIO && stream.hasAudio && !stream.hasVideo }
                ?: throw IllegalStateException("No downloadable audio-only stream is available.")
            DownloadPlan(DownloadKind.AUDIO, audio)
        }
        PlaybackPolicy.WATCH_AND_LISTEN -> {
            val video = details.selection.progressive
                ?.takeIf { stream ->
                        stream.kind == MediaStreamKind.PROGRESSIVE &&
                        stream.hasAudio &&
                        stream.hasVideo &&
                        stream.inferredHeight() in 1..MAX_OFFLINE_VIDEO_HEIGHT
                }
                ?: throw IllegalStateException(
                    "No downloadable 720p-or-lower video with built-in audio is available.",
                )
            require(video.matchesDownloadLanguage(audioLanguage)) {
                "Offline video cannot preserve ${audioLanguage.displayName}. " +
                    "Set this item to Listen Only in the companion to save the selected audio track."
            }
            DownloadPlan(DownloadKind.VIDEO, video)
        }
    }
}

private fun MediaStream.matchesDownloadLanguage(preference: AudioLanguagePreference): Boolean {
    val expected = preference.languageCode ?: return true
    val normalizedLanguage = audioLanguage?.let { tag ->
        tag.substringBeforeLast('.', missingDelimiterValue = tag).lowercase(Locale.ROOT)
    }
    if (normalizedLanguage == expected || normalizedLanguage?.startsWith("$expected-") == true) {
        return true
    }
    return audioTrackName
        ?.trim()
        ?.startsWith(preference.displayName, ignoreCase = true) == true
}

internal fun Throwable.isRetryableDownloadFailure(): Boolean {
    if (this is RetryableMediaDownloadException) return true
    if (
        this is InvidiousApiException &&
        statusCode != null &&
        (statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599)
    ) {
        return true
    }
    return generateSequence(this) { error -> error.cause }
        .any { error -> error is java.io.IOException }
}

internal class DownloadTimeSlice(
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val budgetMillis: Long = DOWNLOAD_TIME_SLICE_MILLIS,
) {
    private val startedAtMillis = elapsedRealtimeMillis()

    init {
        require(budgetMillis > 0L) { "The download time slice must be positive." }
    }

    fun throwIfExpired() {
        val elapsedMillis = (elapsedRealtimeMillis() - startedAtMillis).coerceAtLeast(0L)
        if (elapsedMillis >= budgetMillis) {
            throw RetryableMediaDownloadException(
                "Download time slice completed; continuing from the saved partial.",
            )
        }
    }
}

internal fun downloadJobTag(
    ownerDeviceId: String,
    videoId: String,
    kind: DownloadKind,
): String {
    require(ownerDeviceId.matches(Regex("^[0-9a-f]{32}$"))) { "Invalid download owner." }
    require(extractYouTubeVideoId(videoId) == videoId) { "Invalid download video ID." }
    return "lightious-download-$ownerDeviceId-$videoId-${kind.wireValue}"
}

internal fun downloadStreamFingerprint(stream: MediaStream): String {
    val canonical = listOf(
        stream.kind.name,
        stream.itag,
        stream.mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT),
        stream.container?.trim()?.lowercase(Locale.ROOT),
        stream.codecs,
        stream.bitrate,
        stream.width,
        stream.height,
        stream.contentLength,
        stream.audioTrackId,
        stream.audioTrackName,
        stream.audioTrackIsDefault,
        stream.audioLanguage?.lowercase(Locale.ROOT),
        stream.audioContent.name,
        stream.audioDynamicRangeCompressed,
    ).joinToString(separator = "\u0000") { value -> value?.toString().orEmpty() }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal fun DownloadedMedia.matchesDownloadSearch(terms: List<String>): Boolean {
    if (isShort) return false
    val searchable = listOf(
        title,
        author,
        videoId,
        if (kind == DownloadKind.AUDIO) "audio listen downloaded" else "video watch downloaded",
    ).joinToString(" ").lowercase(Locale.ROOT)
    return terms.all(searchable::contains)
}

private const val MAX_OFFLINE_VIDEO_HEIGHT = 720
internal const val DOWNLOAD_TIME_SLICE_MILLIS = 8L * 60L * 1_000L
