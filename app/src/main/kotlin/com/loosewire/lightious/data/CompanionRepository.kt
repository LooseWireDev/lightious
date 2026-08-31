package com.loosewire.lightious.data

import kotlinx.coroutines.CancellationException

class CompanionRepository internal constructor(
    private val store: CompanionStore,
    private val apiFactory: (String) -> CompanionApi = { instanceUrl -> CompanionApi(instanceUrl) },
) {
    suspend fun load(instanceUrl: String): CompanionState =
        if (instanceUrl.isBlank()) CompanionState() else store.load(instanceUrl)

    suspend fun beginPairing(
        instanceUrl: String,
        deviceLabel: String = "Light Phone III",
    ): Result<PendingPairing> = repositoryRunCatching {
        val normalized = normalizeInstanceUrl(instanceUrl)
        val deviceBearer = generateDeviceBearer()
        val pending = apiFactory(normalized).use { api ->
            api.createPairing(deviceLabel, deviceBearerDigest(deviceBearer)).getOrThrow()
        }
        require(pending.pairingId.matches(Regex("^[0-9a-f]{32}$"))) {
            "The server returned an invalid pairing ID."
        }
        require(pending.userCode.matches(Regex("^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$"))) {
            "The server returned an invalid pairing code."
        }
        require(validPollSecret(pending.pollSecret)) {
            "The server returned an invalid polling credential."
        }
        pending.copy(deviceBearer = deviceBearer)
    }

    suspend fun pollPairing(pending: PendingPairing): Result<PairingStatus> =
        apiFactory(pending.instanceUrl).use { api -> api.pairingStatus(pending) }

    suspend fun activatePairing(pending: PendingPairing): Result<CompanionState> = repositoryRunCatching {
        require(validDeviceBearer(pending.deviceBearer)) { "Pairing must be restarted on this phone." }
        val activated = apiFactory(pending.instanceUrl).use { api ->
            api.activatePairing(pending).getOrThrow()
        }
        require(activated.deviceId.matches(Regex("^[0-9a-f]{32}$"))) {
            "The server returned an invalid device ID."
        }
        val session = CompanionSession(
            instanceUrl = pending.instanceUrl,
            deviceId = activated.deviceId,
            account = activated.account,
            deviceBearer = pending.deviceBearer,
        )
        store.saveSession(session)
        val profile = sync(pending.instanceUrl).getOrNull()
        CompanionState(session.copy(account = profile?.account ?: session.account), profile)
    }

    suspend fun sync(instanceUrl: String): Result<CompanionProfile> = repositoryRunCatching {
        val normalized = normalizeInstanceUrl(instanceUrl)
        val session = store.load(normalized).session
            ?: throw IllegalStateException("This phone is not paired with the companion.")
        val profile = apiFactory(normalized).use { api -> api.sync(session.deviceBearer).getOrThrow() }
        require(profile.deviceId == session.deviceId) { "The companion returned a different device." }
        store.saveProfile(normalized, profile)
        profile
    }

    suspend fun authorizePlayback(instanceUrl: String, videoId: String): PlaybackAccess {
        val cached = load(instanceUrl)
        if (cached.session == null) {
            return PlaybackAccess(allowed = true, policy = PlaybackPolicy.WATCH_AND_LISTEN)
        }

        val profile = sync(instanceUrl).getOrElse { error ->
            return PlaybackAccess(
                allowed = false,
                message = error.message ?: "Could not verify the companion policy.",
            )
        }
        if (profile.mode == ExperienceMode.EXPLORE) {
            return PlaybackAccess(allowed = true, policy = PlaybackPolicy.WATCH_AND_LISTEN)
        }

        val item = profile.items.firstOrNull { it.videoId == videoId }
            ?: return PlaybackAccess(
                allowed = false,
                message = "This video is not in your Focused library.",
            )
        return PlaybackAccess(allowed = true, policy = item.playbackPolicy)
    }

    suspend fun forget() {
        store.clear()
    }
}

private suspend fun <T> repositoryRunCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
