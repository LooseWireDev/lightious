package com.loosewire.lightious.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DownloadModelsTest {
    @Test
    fun `listen-only selects one adaptive audio stream`() {
        val audio = stream(MediaStreamKind.ADAPTIVE_AUDIO, "audio/mp4", hasAudio = true)
        val plan = selectDownloadPlan(details(adaptiveAudio = audio), PlaybackPolicy.LISTEN_ONLY)
            .getOrThrow()

        assertEquals(DownloadKind.AUDIO, plan.kind)
        assertEquals(audio, plan.stream)
    }

    @Test
    fun `watch selects one muxed progressive stream at or below 720p`() {
        val progressive = stream(
            MediaStreamKind.PROGRESSIVE,
            "video/mp4",
            hasAudio = true,
            hasVideo = true,
            height = 720,
        )
        val plan = selectDownloadPlan(details(progressive = progressive), PlaybackPolicy.WATCH_AND_LISTEN)
            .getOrThrow()

        assertEquals(DownloadKind.VIDEO, plan.kind)
        assertEquals(progressive, plan.stream)
    }

    @Test
    fun `watch download refuses to replace a selected adaptive language with embedded original audio`() {
        val progressive = stream(
            MediaStreamKind.PROGRESSIVE,
            "video/mp4",
            hasAudio = true,
            hasVideo = true,
            height = 360,
        )
        val selectedSpanish = stream(MediaStreamKind.ADAPTIVE_AUDIO, "audio/mp4", hasAudio = true)
            .copy(audioLanguage = "es")
        val result = selectDownloadPlan(
            details(
                progressive = progressive,
                adaptiveAudio = selectedSpanish,
                preferAdaptivePair = true,
            ),
            PlaybackPolicy.WATCH_AND_LISTEN,
            AudioLanguagePreference.SPANISH,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Listen Only") == true)
    }

    @Test
    fun `explicitly matching embedded language remains downloadable`() {
        val progressive = stream(
            MediaStreamKind.PROGRESSIVE,
            "video/mp4",
            hasAudio = true,
            hasVideo = true,
            height = 360,
        ).copy(audioLanguage = "es-MX")

        val plan = selectDownloadPlan(
            details(progressive = progressive),
            PlaybackPolicy.WATCH_AND_LISTEN,
            AudioLanguagePreference.SPANISH,
        ).getOrThrow()

        assertEquals(progressive, plan.stream)
    }

    @Test
    fun `transient download failures are retryable but policy failures are not`() {
        assertTrue(InvidiousApiException("busy", 503).isRetryableDownloadFailure())
        assertTrue(java.net.SocketTimeoutException("slow").isRetryableDownloadFailure())
        assertTrue(RetryableMediaDownloadException("restart").isRetryableDownloadFailure())
        assertTrue(!InvidiousApiException("forbidden", 403).isRetryableDownloadFailure())
        assertTrue(!IllegalStateException("policy denied").isRetryableDownloadFailure())
    }

    @Test
    fun `download time slice yields deterministically before worker timeout`() {
        var elapsedRealtimeMillis = 1_000L
        val timeSlice = DownloadTimeSlice(
            elapsedRealtimeMillis = { elapsedRealtimeMillis },
            budgetMillis = 10L,
        )

        elapsedRealtimeMillis = 1_009L
        timeSlice.throwIfExpired()
        elapsedRealtimeMillis = 1_010L

        assertFailsWith<RetryableMediaDownloadException> { timeSlice.throwIfExpired() }
    }

    @Test
    fun `download selection rejects live detached and oversized video`() {
        assertTrue(
            selectDownloadPlan(
                details(liveHls = stream(MediaStreamKind.LIVE_HLS, "application/x-mpegURL", true, true)),
                PlaybackPolicy.WATCH_AND_LISTEN,
            ).isFailure,
        )
        assertTrue(
            selectDownloadPlan(
                details(
                    adaptiveAudio = stream(MediaStreamKind.ADAPTIVE_AUDIO, "audio/mp4", true),
                    adaptiveVideo = stream(MediaStreamKind.ADAPTIVE_VIDEO, "video/mp4", hasVideo = true),
                ),
                PlaybackPolicy.WATCH_AND_LISTEN,
            ).isFailure,
        )
        assertTrue(
            selectDownloadPlan(
                details(
                    progressive = stream(
                        MediaStreamKind.PROGRESSIVE,
                        "video/mp4",
                        hasAudio = true,
                        hasVideo = true,
                        height = 1080,
                    ),
                ),
                PlaybackPolicy.WATCH_AND_LISTEN,
            ).isFailure,
        )
    }

    @Test
    fun `download selection rejects an explicitly flagged Short`() {
        val audio = stream(MediaStreamKind.ADAPTIVE_AUDIO, "audio/mp4", hasAudio = true)

        val result = selectDownloadPlan(
            details(adaptiveAudio = audio, isShort = true),
            PlaybackPolicy.LISTEN_ONLY,
        )

        assertTrue(result.isFailure)
        assertEquals(SHORTS_BLOCKED_MESSAGE, result.exceptionOrNull()?.message)
    }

    @Test
    fun `job tags reject traversal and malformed identities`() {
        assertFailsWith<IllegalArgumentException> {
            downloadJobTag(OWNER, "../video.mp4", DownloadKind.VIDEO)
        }
        assertFailsWith<IllegalArgumentException> {
            downloadJobTag("../owner", VIDEO_ID, DownloadKind.AUDIO)
        }
        assertEquals(
            "lightious-download-$OWNER-$VIDEO_ID-audio",
            downloadJobTag(OWNER, VIDEO_ID, DownloadKind.AUDIO),
        )
    }

    private fun details(
        progressive: MediaStream? = null,
        adaptiveAudio: MediaStream? = null,
        adaptiveVideo: MediaStream? = null,
        liveHls: MediaStream? = null,
        preferAdaptivePair: Boolean = false,
        isShort: Boolean = false,
    ) = VideoDetails(
        summary = VideoSummary(
            VIDEO_ID,
            "Title",
            "Author",
            60,
            0,
            "",
            liveHls != null,
            isShort = isShort,
        ),
        description = "",
        formatStreams = listOfNotNull(progressive),
        adaptiveFormats = listOfNotNull(adaptiveAudio, adaptiveVideo),
        hlsUrl = liveHls?.url,
        dashUrl = null,
        selection = StreamSelection(
            liveHls,
            progressive,
            adaptiveAudio,
            adaptiveVideo,
            preferAdaptivePair,
        ),
    )

    private fun stream(
        kind: MediaStreamKind,
        mimeType: String,
        hasAudio: Boolean = false,
        hasVideo: Boolean = false,
        height: Int? = null,
    ) = MediaStream(
        url = "https://media.example/file",
        kind = kind,
        mimeType = mimeType,
        container = mimeType.substringAfter('/'),
        hasAudio = hasAudio,
        hasVideo = hasVideo,
        height = height,
    )

    private companion object {
        const val OWNER = "0123456789abcdef0123456789abcdef"
        const val VIDEO_ID = "dQw4w9WgXcQ"
    }
}
