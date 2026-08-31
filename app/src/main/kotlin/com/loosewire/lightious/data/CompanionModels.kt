package com.loosewire.lightious.data

import kotlinx.serialization.Serializable

@Serializable
enum class ExperienceMode(val wireValue: String) {
    EXPLORE("explore"),
    FOCUSED("focused"),
    ;

    companion object {
        fun fromWire(value: String): ExperienceMode? = entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
enum class PlaybackPolicy(val wireValue: String) {
    LISTEN_ONLY("listen_only"),
    WATCH_AND_LISTEN("watch_and_listen"),
    ;

    companion object {
        fun fromWire(value: String): PlaybackPolicy? = entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
data class CuratedVideo(
    val id: String,
    val videoId: String,
    val title: String,
    val author: String,
    val lengthSeconds: Long,
    val thumbnailUrl: String? = null,
    val playbackPolicy: PlaybackPolicy,
) {
    fun asVideoSummary(): VideoSummary = VideoSummary(
        videoId = videoId,
        title = title,
        author = author,
        lengthSeconds = lengthSeconds,
        viewCount = 0L,
        publishedText = if (playbackPolicy == PlaybackPolicy.LISTEN_ONLY) "LISTEN ONLY" else "VIDEO ENABLED",
        liveNow = false,
        thumbnailUrl = thumbnailUrl,
    )
}

@Serializable
data class CompanionProfile(
    val deviceId: String,
    val account: String,
    val revision: Long,
    val mode: ExperienceMode,
    val items: List<CuratedVideo>,
)

data class CompanionSession(
    val instanceUrl: String,
    val deviceId: String,
    val account: String,
    val deviceBearer: String,
)

data class CompanionState(
    val session: CompanionSession? = null,
    val profile: CompanionProfile? = null,
)

data class PendingPairing(
    val instanceUrl: String,
    val pairingId: String,
    val userCode: String,
    val pollSecret: String,
    val deviceBearer: String,
    val verificationUrl: String,
    val expiresAt: String,
)

data class PairingStatus(
    val state: String,
    val deviceLabel: String,
    val account: String? = null,
    val expiresAt: String,
) {
    val isClaimed: Boolean
        get() = state == "claimed"

    val isTerminal: Boolean
        get() = state == "expired" || state == "consumed" || state == "rejected"
}

data class PlaybackAccess(
    val allowed: Boolean,
    val policy: PlaybackPolicy? = null,
    val message: String? = null,
)
