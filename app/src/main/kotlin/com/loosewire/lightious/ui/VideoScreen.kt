package com.loosewire.lightious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.loosewire.lightious.LightiousServices
import com.loosewire.lightious.cancelDownload
import com.loosewire.lightious.enqueueDownload
import com.loosewire.lightious.data.ClientSettings
import com.loosewire.lightious.data.DownloadKind
import com.loosewire.lightious.data.DownloadState
import com.loosewire.lightious.data.DownloadedMedia
import com.loosewire.lightious.data.InvidiousApi
import com.loosewire.lightious.data.PlaybackPolicy
import com.loosewire.lightious.data.StreamSelection
import com.loosewire.lightious.data.VideoDetails
import com.loosewire.lightious.data.VideoPlaybackSource
import com.loosewire.lightious.data.VideoSummary
import com.loosewire.lightious.data.selectDownloadPlan
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface VideoMode {
    data object Loading : VideoMode
    data class Loaded(
        val details: VideoDetails,
        val playbackPolicy: PlaybackPolicy,
    ) : VideoMode
    data class Failed(val message: String) : VideoMode
}

data class VideoUiState(
    val mode: VideoMode = VideoMode.Loading,
    val errorMessage: String? = null,
    val checkingAction: Boolean = false,
    val download: DownloadedMedia? = null,
)

class VideoViewModel(
    private val settings: ClientSettings,
    private val audio: LightAudio,
    private val initialVideo: VideoSummary,
    private val services: LightiousServices,
    private val scheduleDownload: (String, String, DownloadKind) -> Boolean,
    private val cancelScheduledDownload: (String, String) -> Unit,
) : LightViewModel<Unit>() {
    private val player = audio.newPlayer(
        usage = LightAudioUsage.Speech,
        playback = LightAudioPlayback.Detached,
    )

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()
    private val _audioStarted = MutableStateFlow(false)
    val audioStarted: StateFlow<Boolean> = _audioStarted.asStateFlow()
    val audioPlaying = player.isPlaying
    val audioPositionMs = player.positionMs
    val audioDurationMs = player.durationMs
    val audioError = player.error
    private val playbackActionGate = PlaybackActionGate()
    private var playbackRecorded = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val ownerDeviceId = services.companion.load(settings.instanceUrl).session?.deviceId
                ?: return@launch
            services.downloads.observeAll().collect { downloads ->
                _uiState.update { state ->
                    state.copy(
                        download = downloads.firstOrNull { download ->
                            download.ownerDeviceId == ownerDeviceId &&
                                download.videoId == initialVideo.videoId
                        },
                    )
                }
            }
        }
        load()
    }

    fun load() {
        _uiState.update { state -> state.copy(mode = VideoMode.Loading, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            when (val resolution = resolvePlayback(FreshPlaybackAction.DISPLAY)) {
                is FreshPlaybackResolution.Allowed -> {
                    _uiState.update { state ->
                        state.copy(mode = VideoMode.Loaded(resolution.details, resolution.policy))
                    }
                }
                is FreshPlaybackResolution.Denied -> {
                    _uiState.update { state ->
                        state.copy(mode = VideoMode.Failed(resolution.message))
                    }
                }
            }
        }
    }

    fun playAudio() {
        if (_uiState.value.mode !is VideoMode.Loaded || _audioStarted.value) return
        if (!beginPlaybackAction()) return

        viewModelScope.launch {
            try {
                when (val resolution = freshPlayback(FreshPlaybackAction.AUDIO)) {
                    is FreshPlaybackResolution.Denied -> applyDeniedResolution(resolution)
                    is FreshPlaybackResolution.Allowed -> {
                        val details = resolution.details
                        _uiState.update {
                            it.copy(
                                mode = VideoMode.Loaded(details, resolution.policy),
                                errorMessage = null,
                            )
                        }
                        if (!player.awaitReady()) {
                            _uiState.update { it.copy(errorMessage = "Audio player is unavailable.") }
                            return@launch
                        }
                        queueAudio(details, resumePositionMs = 0L)
                        _audioStarted.value = true
                        player.play()
                        recordPlayback(details.summary)
                    }
                }
            } finally {
                finishPlaybackAction()
            }
        }
    }

    fun watch(onAuthorized: (VideoDetails, VideoPlaybackSource) -> Unit) {
        if (_uiState.value.mode !is VideoMode.Loaded) return
        if (!beginPlaybackAction()) return

        viewModelScope.launch {
            try {
                when (val resolution = freshPlayback(FreshPlaybackAction.WATCH)) {
                    is FreshPlaybackResolution.Denied -> applyDeniedResolution(resolution)
                    is FreshPlaybackResolution.Allowed -> {
                        val details = resolution.details
                        val source = checkNotNull(details.watchSource)
                        _uiState.update {
                            it.copy(
                                mode = VideoMode.Loaded(details, resolution.policy),
                                errorMessage = null,
                            )
                        }
                        player.pause()
                        recordPlayback(details.summary)
                        onAuthorized(details, source)
                    }
                }
            } finally {
                finishPlaybackAction()
            }
        }
    }

    fun toggleAudio() {
        if (audioPlaying.value) {
            player.pause()
            return
        }
        if (!_audioStarted.value || !beginPlaybackAction()) return
        viewModelScope.launch {
            try {
                when (val resolution = freshPlayback(FreshPlaybackAction.AUDIO)) {
                    is FreshPlaybackResolution.Denied -> applyDeniedResolution(resolution)
                    is FreshPlaybackResolution.Allowed -> {
                        val details = resolution.details
                        val resumePositionMs = player.positionMs.value
                        _uiState.update {
                            it.copy(
                                mode = VideoMode.Loaded(details, resolution.policy),
                                errorMessage = null,
                            )
                        }
                        if (!player.awaitReady()) {
                            _uiState.update { it.copy(errorMessage = "Audio player is unavailable.") }
                            return@launch
                        }
                        queueAudio(details, resumePositionMs = resumePositionMs)
                        player.play()
                    }
                }
            } finally {
                finishPlaybackAction()
            }
        }
    }

    fun skipAudioBack() = player.skipBack()
    fun skipAudioForward() = player.skipForward()

    fun download() {
        if (_uiState.value.mode !is VideoMode.Loaded || !beginPlaybackAction()) return
        viewModelScope.launch {
            try {
                when (val resolution = freshPlayback(FreshPlaybackAction.DISPLAY)) {
                    is FreshPlaybackResolution.Denied -> applyDeniedResolution(resolution)
                    is FreshPlaybackResolution.Allowed -> withContext(Dispatchers.IO) {
                        val session = services.companion.load(settings.instanceUrl).session
                            ?: error("Pair this phone before downloading.")
                        val kind = selectDownloadPlan(
                            resolution.details,
                            resolution.policy,
                            settings.audioLanguage,
                        ).getOrThrow().kind
                        services.downloads.queue(session.deviceId, resolution.details.summary, kind)
                        if (!scheduleDownload(session.deviceId, initialVideo.videoId, kind)) {
                            services.downloads.markFailed(
                                session.deviceId,
                                initialVideo.videoId,
                                "The background download service is unavailable.",
                            )
                        }
                        _uiState.update { state ->
                            state.copy(
                                mode = VideoMode.Loaded(resolution.details, resolution.policy),
                                errorMessage = null,
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(errorMessage = error.userMessage("Could not start the download."))
                }
            } finally {
                finishPlaybackAction()
            }
        }
    }

    fun cancelDownload() {
        val download = _uiState.value.download ?: return
        cancelScheduledDownload(download.ownerDeviceId, download.videoId)
        viewModelScope.launch(Dispatchers.IO) {
            services.downloads.cancel(download.ownerDeviceId, download.videoId)
        }
    }
    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private fun queueAudio(details: VideoDetails, resumePositionMs: Long) {
        val audioUrl = checkNotNull(details.audioUrl)
        player.setMediaQueue(
            listOf(
                LightAudioItem(
                    source = LightAudioSource.UrlSource(audioUrl),
                    metadata = LightMediaMetadata(
                        title = details.summary.title,
                        artist = details.summary.author,
                        album = "Lightious",
                        durationMs = details.summary.lengthSeconds
                            .takeIf { it > 0L }
                            ?.times(1_000L),
                    ),
                ),
            ),
        )
        if (resumePositionMs > 0L) {
            player.seekTo(resumePositionMs)
        }
    }

    private fun recordPlayback(video: VideoSummary) {
        if (playbackRecorded) return
        playbackRecorded = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentSettings = services.settings.load()
                val account = services.accounts.load(currentSettings.instanceUrl)
                services.historySyncer.recordPlayback(video, currentSettings, account)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Playback must remain available if local persistence or optional sync fails.
            }
        }
    }

    private suspend fun resolvePlayback(action: FreshPlaybackAction): FreshPlaybackResolution =
        resolveFreshPlayback(
            videoId = initialVideo.videoId,
            action = action,
            fetchDetails = ::fetchDetails,
            authorize = { details ->
                services.companion.authorizePlayback(
                    settings.instanceUrl,
                    details.summary.videoId,
                    details.summary.authorId,
                    details.summary.isShort,
                )
            },
        )

    private suspend fun fetchDetails(videoId: String): Result<VideoDetails> {
        val companion = services.companion.loadActiveState(settings.instanceUrl).getOrElse { error ->
            return Result.failure(error)
        }
        return InvidiousApi(
            baseUrl = settings.instanceUrl,
            proxyMedia = settings.proxyMedia,
            deviceBearer = companion.session?.deviceBearer,
            audioLanguage = settings.audioLanguage,
        ).use { api ->
            api.video(videoId)
        }
    }

    private suspend fun freshPlayback(action: FreshPlaybackAction): FreshPlaybackResolution =
        withContext(Dispatchers.IO) { resolvePlayback(action) }

    private fun beginPlaybackAction(): Boolean {
        if (!playbackActionGate.tryAcquire()) return false
        _uiState.update { it.copy(checkingAction = true, errorMessage = null) }
        return true
    }

    private fun finishPlaybackAction() {
        playbackActionGate.release()
        _uiState.update { it.copy(checkingAction = false) }
    }

    private fun applyDeniedResolution(resolution: FreshPlaybackResolution.Denied) {
        val details = resolution.details
        val policy = resolution.policy
        if (resolution.invalidateAudio) {
            player.pause()
            player.setMediaQueue(emptyList())
            _audioStarted.value = false
        }
        if (details == null && !resolution.invalidateAudio) {
            _uiState.update { it.copy(errorMessage = resolution.message) }
            return
        }
        if (details != null && policy != null) {
            _uiState.update {
                it.copy(
                    mode = VideoMode.Loaded(details, policy),
                    errorMessage = resolution.message,
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    mode = VideoMode.Failed(resolution.message),
                    errorMessage = null,
                )
            }
        }
    }
}

class VideoScreen(
    private val sealedActivity: SealedLightActivity,
    private val settings: ClientSettings,
    private val initialVideo: VideoSummary,
    private val services: LightiousServices,
) : LightScreen<Unit, VideoViewModel>(sealedActivity) {
    override val viewModelClass = VideoViewModel::class.java

    override fun createViewModel() = VideoViewModel(
        settings = settings,
        audio = DefaultLightAudio(sealedActivity),
        initialVideo = initialVideo,
        services = services,
        scheduleDownload = { ownerDeviceId, videoId, kind ->
            enqueueDownload(lightContext, ownerDeviceId, videoId, kind)
        },
        cancelScheduledDownload = { ownerDeviceId, videoId ->
            cancelDownload(lightContext, ownerDeviceId, videoId)
        },
    )

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val audioStarted by viewModel.audioStarted.collectAsState()
        val audioPlaying by viewModel.audioPlaying.collectAsState()
        val audioPosition by viewModel.audioPositionMs.collectAsState()
        val audioDuration by viewModel.audioDurationMs.collectAsState()
        val audioError by viewModel.audioError.collectAsState()

        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        center = LightTopBarCenter.Text("Video"),
                        rightButton = (state.mode as? VideoMode.Loaded)?.let { mode ->
                            videoDownloadButton(
                                download = state.download,
                                desiredKind = mode.playbackPolicy.downloadKind,
                                checkingAction = state.checkingAction,
                                onDownload = viewModel::download,
                                onCancel = viewModel::cancelDownload,
                            )
                        },
                    )

                    when (val mode = state.mode) {
                        VideoMode.Loading -> VideoLoadingContent(Modifier.weight(1f))
                        is VideoMode.Failed -> VideoFailureContent(
                            message = mode.message,
                            onRetry = viewModel::load,
                            modifier = Modifier.weight(1f),
                        )
                        is VideoMode.Loaded -> {
                            VideoDetailsContent(
                                details = mode.details,
                                playbackPolicy = mode.playbackPolicy,
                                audioPlaying = audioPlaying,
                                audioPositionMs = audioPosition,
                                audioDurationMs = audioDuration,
                                audioError = audioError?.let { "${it.kind}: ${it.diagnostic}" },
                                download = state.download,
                                modifier = Modifier.weight(1f),
                            )
                            VideoActions(
                                details = mode.details,
                                playbackPolicy = mode.playbackPolicy,
                                audioStarted = audioStarted,
                                audioPlaying = audioPlaying,
                                checkingAction = state.checkingAction,
                                onWatch = {
                                    viewModel.watch { _, source ->
                                        navigateTo(
                                            screenFactory = { activity ->
                                                VideoPlaybackScreen(
                                                    sealedActivity = activity,
                                                    playbackSource = source,
                                                )
                                            },
                                        )
                                    }
                                },
                                onListen = viewModel::playAudio,
                                onToggleAudio = viewModel::toggleAudio,
                                onSkipBack = viewModel::skipAudioBack,
                                onSkipForward = viewModel::skipAudioForward,
                            )
                        }
                    }
                }

                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

private fun videoDownloadButton(
    download: DownloadedMedia?,
    desiredKind: DownloadKind,
    checkingAction: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
): LightBarButton = when (videoDownloadAction(download, desiredKind)) {
    VideoDownloadAction.CANCEL -> LightBarButton.LightIcon(
        icon = LightIcons.CLOSE,
        onClick = onCancel,
        contentDescription = "Cancel download",
    )
    VideoDownloadAction.AVAILABLE -> LightBarButton.LightIcon(
        icon = LightIcons.DOWNLOAD_ARROW,
        onClick = null,
        contentDescription = "Available offline",
    )
    VideoDownloadAction.DOWNLOAD,
    VideoDownloadAction.REPLACE,
    VideoDownloadAction.RETRY,
    -> LightBarButton.LightIcon(
        icon = if (videoDownloadAction(download, desiredKind) == VideoDownloadAction.RETRY) {
            LightIcons.REFRESH
        } else {
            LightIcons.DOWNLOAD_ARROW
        },
        onClick = onDownload.takeUnless { checkingAction },
        contentDescription = when (videoDownloadAction(download, desiredKind)) {
            VideoDownloadAction.REPLACE -> "Replace with ${desiredKind.wireValue} download"
            VideoDownloadAction.RETRY -> "Retry download"
            else -> "Download"
        },
    )
}

internal enum class VideoDownloadAction {
    DOWNLOAD,
    REPLACE,
    RETRY,
    CANCEL,
    AVAILABLE,
}

internal fun videoDownloadAction(
    download: DownloadedMedia?,
    desiredKind: DownloadKind,
): VideoDownloadAction = when (download?.state) {
    DownloadState.QUEUED,
    DownloadState.DOWNLOADING,
    -> VideoDownloadAction.CANCEL
    DownloadState.COMPLETE -> if (download.kind == desiredKind) {
        VideoDownloadAction.AVAILABLE
    } else {
        VideoDownloadAction.REPLACE
    }
    DownloadState.FAILED,
    DownloadState.CANCELLED,
    -> VideoDownloadAction.RETRY
    null -> VideoDownloadAction.DOWNLOAD
}

private val PlaybackPolicy.downloadKind: DownloadKind
    get() = when (this) {
        PlaybackPolicy.LISTEN_ONLY -> DownloadKind.AUDIO
        PlaybackPolicy.WATCH_AND_LISTEN -> DownloadKind.VIDEO
    }

@Composable
private fun VideoLoadingContent(modifier: Modifier = Modifier) {
    LightText(
        text = "Loading video…",
        variant = LightTextVariant.Copy,
        modifier = modifier
            .fillMaxWidth()
            .padding(1f.gridUnitsAsDp()),
    )
}

@Composable
private fun VideoFailureContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )
        LightText(
            text = "RETRY",
            variant = LightTextVariant.Button,
            underline = true,
            modifier = Modifier
                .padding(top = 1f.gridUnitsAsDp())
                .lightClickable(onClick = onRetry),
        )
    }
}

@Composable
private fun VideoDetailsContent(
    details: VideoDetails,
    playbackPolicy: PlaybackPolicy,
    audioPlaying: Boolean,
    audioPositionMs: Long,
    audioDurationMs: Long,
    audioError: String?,
    download: DownloadedMedia?,
    modifier: Modifier = Modifier,
) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = details.summary.title,
            variant = LightTextVariant.Subheading,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
        )
        LightText(
            text = details.summary.author,
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier.padding(top = 0.35f.gridUnitsAsDp()),
        )
        LightText(
            text = videoMetadataLine(details.summary),
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
        )
        LightText(
            text = if (playbackPolicy == PlaybackPolicy.LISTEN_ONLY) {
                "LISTEN ONLY"
            } else {
                streamLabel(details.selection)
            },
            variant = LightTextVariant.Superfine,
            lighten = true,
            modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
        )
        download?.let { item ->
            LightText(
                text = downloadStatusLabel(item),
                variant = LightTextVariant.Fine,
                monospace = true,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
            item.errorMessage?.takeIf { item.state != DownloadState.COMPLETE }?.let { message ->
                LightText(
                    text = message,
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                )
            }
        }
        if (audioPlaying || audioPositionMs > 0L) {
            LightText(
                text = "LISTENING  ${playbackTimeLabel(audioPositionMs, audioDurationMs)}",
                variant = LightTextVariant.Fine,
                monospace = true,
                modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
            )
        }
        audioError?.let { message ->
            LightText(
                text = "Audio failed: $message",
                variant = LightTextVariant.Detail,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
        }
        if (details.description.isNotBlank()) {
            LightText(
                text = details.description,
                variant = LightTextVariant.Paragraph,
                maxLines = 20,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun VideoActions(
    details: VideoDetails,
    playbackPolicy: PlaybackPolicy,
    audioStarted: Boolean,
    audioPlaying: Boolean,
    checkingAction: Boolean,
    onWatch: () -> Unit,
    onListen: () -> Unit,
    onToggleAudio: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    val watchSource = if (playbackPolicy == PlaybackPolicy.WATCH_AND_LISTEN) {
        details.watchSource
    } else {
        null
    }
    if (audioStarted) {
        LightBottomBar(
            items = buildList {
                add(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SKIP_BACKWARD_FIFTEEN,
                        onClick = onSkipBack,
                        contentDescription = "Back 15 seconds",
                    ),
                )
                add(
                    LightBarButton.LightIcon(
                        icon = if (audioPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                        onClick = onToggleAudio.takeUnless { checkingAction && !audioPlaying },
                        contentDescription = if (audioPlaying) "Pause" else "Play",
                    ),
                )
                add(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SKIP_FORWARD_FIFTEEN,
                        onClick = onSkipForward,
                        contentDescription = "Forward 15 seconds",
                    ),
                )
                if (watchSource != null) {
                    add(
                        LightBarButton.LightIcon(
                            icon = LightIcons.MEDIA,
                            onClick = onWatch.takeUnless { checkingAction },
                            contentDescription = "Watch video",
                        ),
                    )
                }
            },
        )
    } else {
        LightBottomBar(
            items = buildList {
                if (watchSource != null) {
                    add(
                        LightBarButton.Text(
                            text = "WATCH",
                            onClick = onWatch.takeUnless { checkingAction },
                        ),
                    )
                }
                add(
                    LightBarButton.Text(
                        text = "LISTEN",
                        onClick = onListen.takeIf { details.audioUrl != null && !checkingAction },
                    ),
                )
            },
        )
    }
}

internal fun streamLabel(selection: StreamSelection): String = when {
    selection.liveHls != null -> "LIVE HLS"
    selection.progressive != null -> listOfNotNull(
        selection.progressive.qualityLabel,
        selection.progressive.container?.uppercase(),
    )
        .joinToString("  ·  ")
        .ifBlank { "PROGRESSIVE VIDEO" }
    selection.adaptiveVideo != null && selection.adaptiveAudio != null -> listOfNotNull(
        selection.adaptiveVideo.qualityLabel,
        selection.adaptiveVideo.container?.uppercase(),
        "ADAPTIVE",
    ).joinToString("  ·  ")
    else -> "NO COMPATIBLE VIDEO STREAM"
}
