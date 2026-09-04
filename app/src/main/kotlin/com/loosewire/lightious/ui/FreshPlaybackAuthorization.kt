package com.loosewire.lightious.ui

import com.loosewire.lightious.data.PlaybackAccess
import com.loosewire.lightious.data.PlaybackPolicy
import com.loosewire.lightious.data.SHORTS_BLOCKED_MESSAGE
import com.loosewire.lightious.data.VideoDetails
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

internal enum class FreshPlaybackAction {
    DISPLAY,
    AUDIO,
    WATCH,
}

internal sealed interface FreshPlaybackResolution {
    data class Allowed(
        val details: VideoDetails,
        val policy: PlaybackPolicy,
    ) : FreshPlaybackResolution

    data class Denied(
        val message: String,
        val details: VideoDetails? = null,
        val policy: PlaybackPolicy? = null,
        val invalidateAudio: Boolean = false,
    ) : FreshPlaybackResolution
}

internal suspend fun resolveFreshPlayback(
    videoId: String,
    action: FreshPlaybackAction,
    fetchDetails: suspend (String) -> Result<VideoDetails>,
    authorize: suspend (VideoDetails) -> PlaybackAccess,
): FreshPlaybackResolution {
    val details = fetchDetails(videoId).getOrElse { error ->
        return FreshPlaybackResolution.Denied(
            error.message ?: "Could not refresh this video.",
        )
    }
    if (details.summary.isShort) {
        return FreshPlaybackResolution.Denied(
            message = SHORTS_BLOCKED_MESSAGE,
            details = details,
            invalidateAudio = true,
        )
    }
    val access = try {
        authorize(details)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return FreshPlaybackResolution.Denied(
            error.message ?: "Could not verify companion playback access.",
        )
    }
    val policy = access.policy
    if (!access.allowed || policy == null) {
        return FreshPlaybackResolution.Denied(
            message = access.message ?: "The companion did not allow this video.",
            details = details,
            invalidateAudio = true,
        )
    }
    if (action == FreshPlaybackAction.WATCH && policy != PlaybackPolicy.WATCH_AND_LISTEN) {
        return FreshPlaybackResolution.Denied(
            message = "This video is now audio only.",
            details = details,
            policy = policy,
        )
    }
    if (action == FreshPlaybackAction.AUDIO && details.audioUrl == null) {
        return FreshPlaybackResolution.Denied(
            message = "This video has no compatible audio stream.",
            details = details,
            policy = policy,
            invalidateAudio = true,
        )
    }
    if (action == FreshPlaybackAction.WATCH && details.watchSource == null) {
        return FreshPlaybackResolution.Denied(
            message = "This video has no compatible video stream.",
            details = details,
            policy = policy,
        )
    }
    return FreshPlaybackResolution.Allowed(details, policy)
}

internal class PlaybackActionGate {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }
}
