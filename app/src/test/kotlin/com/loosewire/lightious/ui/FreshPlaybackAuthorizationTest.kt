package com.loosewire.lightious.ui

import com.loosewire.lightious.data.MediaStream
import com.loosewire.lightious.data.MediaStreamKind
import com.loosewire.lightious.data.PlaybackAccess
import com.loosewire.lightious.data.PlaybackPolicy
import com.loosewire.lightious.data.StreamSelection
import com.loosewire.lightious.data.SHORTS_BLOCKED_MESSAGE
import com.loosewire.lightious.data.VideoDetails
import com.loosewire.lightious.data.VideoPlaybackSource
import com.loosewire.lightious.data.VideoSummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FreshPlaybackAuthorizationTest {
    @Test
    fun `removal after display denies a new audio start`() = runTest {
        var access = PlaybackAccess(true, PlaybackPolicy.WATCH_AND_LISTEN)
        var detailsRequests = 0
        val fetchDetails: suspend (String) -> Result<VideoDetails> = {
            detailsRequests += 1
            Result.success(details("https://media.example/$detailsRequests"))
        }
        val authorize: suspend (VideoDetails) -> PlaybackAccess = { access }

        assertIs<FreshPlaybackResolution.Allowed>(
            resolveFreshPlayback(VIDEO_ID, FreshPlaybackAction.DISPLAY, fetchDetails, authorize),
        )
        access = PlaybackAccess(false, message = "Removed from Focused library.")

        val resolution = resolveFreshPlayback(
            VIDEO_ID,
            FreshPlaybackAction.AUDIO,
            fetchDetails,
            authorize,
        )

        val denied = assertIs<FreshPlaybackResolution.Denied>(resolution)
        assertEquals("Removed from Focused library.", denied.message)
        assertEquals(null, denied.policy)
        assertTrue(denied.invalidateAudio)
        assertEquals(2, detailsRequests)
    }

    @Test
    fun `removal after audio authorization denies a paused queue resume`() = runTest {
        var access = PlaybackAccess(true, PlaybackPolicy.LISTEN_ONLY)
        var authorizationChecks = 0
        val fetchDetails: suspend (String) -> Result<VideoDetails> = {
            Result.success(details("https://media.example/audio"))
        }
        val authorize: suspend (VideoDetails) -> PlaybackAccess = {
            authorizationChecks += 1
            access
        }

        assertIs<FreshPlaybackResolution.Allowed>(
            resolveFreshPlayback(VIDEO_ID, FreshPlaybackAction.AUDIO, fetchDetails, authorize),
        )
        access = PlaybackAccess(false, message = "Audio permission was removed.")

        val resolution = resolveFreshPlayback(
            VIDEO_ID,
            FreshPlaybackAction.AUDIO,
            fetchDetails,
            authorize,
        )

        val denied = assertIs<FreshPlaybackResolution.Denied>(resolution)
        assertTrue(denied.invalidateAudio)
        assertEquals(2, authorizationChecks)
    }

    @Test
    fun `watch downgrade after display is denied and returns fresh listen policy`() = runTest {
        var policy = PlaybackPolicy.WATCH_AND_LISTEN
        val fetchDetails: suspend (String) -> Result<VideoDetails> = {
            Result.success(details("https://media.example/fresh"))
        }
        val authorize: suspend (VideoDetails) -> PlaybackAccess = {
            PlaybackAccess(true, policy)
        }

        assertIs<FreshPlaybackResolution.Allowed>(
            resolveFreshPlayback(VIDEO_ID, FreshPlaybackAction.DISPLAY, fetchDetails, authorize),
        )
        policy = PlaybackPolicy.LISTEN_ONLY

        val resolution = resolveFreshPlayback(
            VIDEO_ID,
            FreshPlaybackAction.WATCH,
            fetchDetails,
            authorize,
        )

        val denied = assertIs<FreshPlaybackResolution.Denied>(resolution)
        assertEquals(PlaybackPolicy.LISTEN_ONLY, denied.policy)
        assertEquals("This video is now audio only.", denied.message)
        assertFalse(denied.invalidateAudio)
    }

    @Test
    fun `watch authorization returns the freshly fetched media source`() = runTest {
        var detailsRequests = 0
        val fetchDetails: suspend (String) -> Result<VideoDetails> = {
            detailsRequests += 1
            Result.success(details("https://media.example/source-$detailsRequests"))
        }
        val authorize: suspend (VideoDetails) -> PlaybackAccess = {
            PlaybackAccess(true, PlaybackPolicy.WATCH_AND_LISTEN)
        }

        resolveFreshPlayback(VIDEO_ID, FreshPlaybackAction.DISPLAY, fetchDetails, authorize)
        val resolution = resolveFreshPlayback(
            VIDEO_ID,
            FreshPlaybackAction.WATCH,
            fetchDetails,
            authorize,
        )

        val allowed = assertIs<FreshPlaybackResolution.Allowed>(resolution)
        val source = assertIs<VideoPlaybackSource.Single>(allowed.details.watchSource)
        assertEquals("https://media.example/source-2", source.stream.url)
    }

    @Test
    fun `explicitly flagged Short is denied before companion authorization`() = runTest {
        var authorizationChecks = 0

        val resolution = resolveFreshPlayback(
            VIDEO_ID,
            FreshPlaybackAction.WATCH,
            fetchDetails = { Result.success(details("https://media.example/short", isShort = true)) },
            authorize = {
                authorizationChecks += 1
                PlaybackAccess(true, PlaybackPolicy.WATCH_AND_LISTEN)
            },
        )

        val denied = assertIs<FreshPlaybackResolution.Denied>(resolution)
        assertEquals(SHORTS_BLOCKED_MESSAGE, denied.message)
        assertTrue(denied.invalidateAudio)
        assertEquals(0, authorizationChecks)
    }

    @Test
    fun `playback action gate rejects double starts until released`() {
        val gate = PlaybackActionGate()

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
    }

    private fun details(mediaUrl: String, isShort: Boolean = false): VideoDetails {
        val stream = MediaStream(
            url = mediaUrl,
            kind = MediaStreamKind.PROGRESSIVE,
            hasAudio = true,
            hasVideo = true,
        )
        return VideoDetails(
            summary = VideoSummary(
                videoId = VIDEO_ID,
                title = "A video",
                author = "A channel",
                lengthSeconds = 60,
                viewCount = 0,
                publishedText = "",
                liveNow = false,
                authorId = CHANNEL_ID,
                isShort = isShort,
            ),
            description = "",
            formatStreams = listOf(stream),
            adaptiveFormats = emptyList(),
            hlsUrl = null,
            dashUrl = null,
            selection = StreamSelection(progressive = stream),
        )
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        const val CHANNEL_ID = "UCXuqSBlHAE6Xw-yeJA0Tunw"
    }
}
