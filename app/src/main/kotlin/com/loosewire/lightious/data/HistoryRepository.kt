package com.loosewire.lightious.data

import java.util.Locale

class HistoryRepository internal constructor(
    private val dao: HistoryDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun searchHistory(): List<SearchHistoryEntry> =
        dao.listSearchHistory(MAX_SEARCH_HISTORY).map { entity ->
            SearchHistoryEntry(
                query = entity.displayQuery,
                lastSearchedAt = entity.lastSearchedAt,
                useCount = entity.useCount,
            )
        }

    suspend fun recordSearch(query: String) {
        val display = query.trim().replace(Regex("\\s+"), " ")
        if (display.isEmpty() || extractYouTubeVideoId(display) != null || isYouTubeShortsUrl(display)) return
        val normalized = display.lowercase(Locale.ROOT)
        val existing = dao.findSearch(normalized)
        dao.upsertSearch(
            SearchHistoryEntity(
                normalizedQuery = normalized,
                displayQuery = display,
                lastSearchedAt = now(),
                useCount = (existing?.useCount ?: 0) + 1,
            ),
        )
        dao.pruneSearchHistory(MAX_SEARCH_HISTORY)
    }

    suspend fun clearSearchHistory() = dao.clearSearchHistory()

    suspend fun watchHistory(): List<WatchHistoryEntry> =
        dao.listWatchHistory(MAX_WATCH_HISTORY).map { entity ->
            WatchHistoryEntry(
                video = VideoSummary(
                    videoId = entity.videoId,
                    title = entity.title,
                    author = entity.author,
                    lengthSeconds = entity.lengthSeconds,
                    viewCount = entity.viewCount,
                    publishedText = entity.publishedText,
                    liveNow = entity.liveNow,
                    thumbnailUrl = entity.thumbnailUrl,
                ),
                lastWatchedAt = entity.lastWatchedAt,
            )
        }

    suspend fun recordWatch(video: VideoSummary) {
        if (video.isShort) return
        dao.upsertWatch(
            WatchHistoryEntity(
                videoId = video.videoId,
                title = video.title,
                author = video.author,
                lengthSeconds = video.lengthSeconds,
                viewCount = video.viewCount,
                publishedText = video.publishedText,
                liveNow = video.liveNow,
                thumbnailUrl = video.thumbnailUrl,
                lastWatchedAt = now(),
            ),
        )
        dao.pruneWatchHistory(MAX_WATCH_HISTORY)
    }

    suspend fun clearWatchHistory(accountKey: String? = null) =
        dao.clearWatchHistoryAndPending(accountKey)

    suspend fun reconcile(profile: CompanionProfile) {
        profile.knownShortVideoIds().forEach { videoId ->
            dao.deleteWatch(videoId)
            dao.deletePendingServerWatchForVideo(videoId)
        }
    }

    suspend fun enqueueServerWatch(accountKey: String, videoId: String) {
        dao.enqueueServerWatch(
            ServerHistoryOutboxEntity(
                accountKey = accountKey,
                videoId = videoId,
                queuedAt = now(),
            ),
        )
    }

    internal suspend fun pendingServerWatches(accountKey: String): List<String> =
        dao.listPendingServerWatches(accountKey, MAX_OUTBOX_BATCH).map { it.videoId }

    internal suspend fun completeServerWatch(accountKey: String, videoId: String) {
        dao.deletePendingServerWatch(accountKey, videoId)
    }

    suspend fun clearPendingServerWatches(accountKey: String) {
        dao.clearPendingServerWatches(accountKey)
    }

    private companion object {
        const val MAX_SEARCH_HISTORY = 30
        const val MAX_WATCH_HISTORY = 500
        const val MAX_OUTBOX_BATCH = 25
    }
}

class HistorySyncer(
    private val history: HistoryRepository,
    private val companion: CompanionRepository? = null,
) {
    suspend fun recordPlayback(
        video: VideoSummary,
        settings: ClientSettings,
        account: AccountSession?,
    ) {
        if (video.isShort) return
        if (settings.saveWatchHistory) {
            history.recordWatch(video)
        }
        if (!settings.syncAccountHistory) return

        val syncTarget = resolveSyncTarget(settings.instanceUrl, account) ?: return
        history.enqueueServerWatch(syncTarget.accountKey, video.videoId)
        flush(syncTarget, settings.proxyMedia)
    }

    suspend fun flush(account: AccountSession, proxyMedia: Boolean) {
        flush(
            ServerHistorySyncTarget(
                accountKey = account.accountKey,
                instanceUrl = account.instanceUrl,
                token = account.token,
            ),
            proxyMedia,
        )
    }

    private suspend fun resolveSyncTarget(
        instanceUrl: String,
        account: AccountSession?,
    ): ServerHistorySyncTarget? {
        val normalizedInstance = normalizeInstanceUrl(instanceUrl)
        val cachedCompanion = companion?.load(normalizedInstance) ?: CompanionState()
        val activeCompanion = if (cachedCompanion.session != null) {
            companion?.loadActiveState(normalizedInstance)
        } else {
            null
        }
        return selectHistorySyncTarget(
            instanceUrl = normalizedInstance,
            account = account,
            cachedCompanion = cachedCompanion,
            activeCompanion = activeCompanion,
        )
    }

    private suspend fun flush(syncTarget: ServerHistorySyncTarget, proxyMedia: Boolean) {
        val pending = history.pendingServerWatches(syncTarget.accountKey)
        if (pending.isEmpty()) return
        InvidiousApi(
            baseUrl = syncTarget.instanceUrl,
            proxyMedia = proxyMedia,
            deviceBearer = syncTarget.deviceBearer,
        ).use { api ->
            for (videoId in pending) {
                val result = api.markWatched(syncTarget.token, videoId)
                if (result.isFailure) break
                history.completeServerWatch(syncTarget.accountKey, videoId)
            }
        }
    }
}

internal data class ServerHistorySyncTarget(
    val accountKey: String,
    val instanceUrl: String,
    val token: String = "",
    val deviceBearer: String? = null,
)

internal fun pairedHistoryAccountKey(instanceUrl: String, account: String): String =
    "paired:${normalizeInstanceUrl(instanceUrl)}:${account.trim()}"

internal fun selectHistorySyncTarget(
    instanceUrl: String,
    account: AccountSession?,
    cachedCompanion: CompanionState,
    activeCompanion: Result<CompanionState>? = null,
): ServerHistorySyncTarget? {
    val normalizedInstance = normalizeInstanceUrl(instanceUrl)
    if (cachedCompanion.session != null) {
        val activeSession = activeCompanion?.getOrNull()?.session ?: return null
        return ServerHistorySyncTarget(
            accountKey = pairedHistoryAccountKey(activeSession.instanceUrl, activeSession.account),
            instanceUrl = activeSession.instanceUrl,
            deviceBearer = activeSession.deviceBearer,
        )
    }
    return account
        ?.takeIf { normalizeInstanceUrl(it.instanceUrl) == normalizedInstance }
        ?.let { legacy ->
            ServerHistorySyncTarget(
                accountKey = legacy.accountKey,
                instanceUrl = normalizedInstance,
                token = legacy.token,
            )
        }
}
