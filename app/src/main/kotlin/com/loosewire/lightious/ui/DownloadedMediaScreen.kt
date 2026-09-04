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
import com.loosewire.lightious.data.DownloadKind
import com.loosewire.lightious.data.DownloadState
import com.loosewire.lightious.data.DownloadedMedia
import com.loosewire.lightious.data.VideoPlaybackSource
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class DownloadPrimaryAction {
    PLAY_AUDIO,
    WATCH_VIDEO,
    CANCEL,
    RETRY,
    NONE,
}

internal fun downloadedMediaPrimaryAction(download: DownloadedMedia?): DownloadPrimaryAction =
    when {
        download == null || download.isShort -> DownloadPrimaryAction.NONE
        else -> when (download.state) {
            DownloadState.QUEUED, DownloadState.DOWNLOADING -> DownloadPrimaryAction.CANCEL
            DownloadState.FAILED, DownloadState.CANCELLED -> DownloadPrimaryAction.RETRY
            DownloadState.COMPLETE -> if (download.kind == DownloadKind.AUDIO) {
                DownloadPrimaryAction.PLAY_AUDIO
            } else {
                DownloadPrimaryAction.WATCH_VIDEO
            }
        }
    }

internal data class DownloadedMediaUiState(
    val loaded: Boolean = false,
    val download: DownloadedMedia? = null,
    val audioStarted: Boolean = false,
    val errorMessage: String? = null,
)

internal class DownloadedMediaViewModel(
    private val services: LightiousServices,
    private val audio: LightAudio,
    private val ownerDeviceId: String,
    private val videoId: String,
    private val scheduleDownload: (String, String, DownloadKind) -> Boolean,
    private val cancelScheduledDownload: (String, String) -> Unit,
) : LightViewModel<Unit>() {
    private val player = audio.newPlayer(
        usage = LightAudioUsage.Speech,
        playback = LightAudioPlayback.Detached,
    )
    private val _uiState = MutableStateFlow(DownloadedMediaUiState())
    val uiState: StateFlow<DownloadedMediaUiState> = _uiState.asStateFlow()
    val audioPlaying = player.isPlaying
    val audioPositionMs = player.positionMs
    val audioDurationMs = player.durationMs
    private var playbackRecorded = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            services.downloads.observeAll().collect { downloads ->
                _uiState.update { state ->
                    state.copy(
                        loaded = true,
                        download = downloads.firstOrNull { download ->
                            download.ownerDeviceId == ownerDeviceId && download.videoId == videoId
                        },
                    )
                }
            }
        }
    }

    fun toggleAudio() {
        val download = _uiState.value.download
            ?.takeIf { it.kind == DownloadKind.AUDIO && it.state == DownloadState.COMPLETE }
            ?: return
        if (player.isPlaying.value) {
            player.pause()
            return
        }
        viewModelScope.launch {
            try {
                val file = services.downloads.localFile(download)
                    ?: error("The downloaded audio file is missing.")
                if (!player.awaitReady()) error("Audio player is unavailable.")
                if (!_uiState.value.audioStarted) {
                    player.setMediaQueue(
                        listOf(
                            LightAudioItem(
                                source = LightAudioSource.FileSource(file),
                                metadata = LightMediaMetadata(
                                    title = download.title,
                                    artist = download.author,
                                    album = "Lightious Downloads",
                                    durationMs = download.lengthSeconds
                                        .takeIf { it > 0L }
                                        ?.times(1_000L),
                                ),
                            ),
                        ),
                    )
                    _uiState.update { it.copy(audioStarted = true) }
                }
                recordPlayback(download)
                player.play()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not play this download.")) }
            }
        }
    }

    fun localVideoSource(): VideoPlaybackSource.Single? {
        val download = _uiState.value.download ?: return null
        val source = services.downloads.localVideoSource(download) ?: return null
        recordPlayback(download)
        return source
    }

    fun retry() {
        val download = _uiState.value.download ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                services.downloads.queue(ownerDeviceId, download.asVideoSummary(), download.kind)
                if (!scheduleDownload(ownerDeviceId, videoId, download.kind)) {
                    services.downloads.markFailed(
                        ownerDeviceId,
                        videoId,
                        "The background download service is unavailable.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not retry the download.")) }
            }
        }
    }

    fun cancel() {
        cancelScheduledDownload(ownerDeviceId, videoId)
        viewModelScope.launch(Dispatchers.IO) {
            services.downloads.cancel(ownerDeviceId, videoId)
        }
    }

    fun delete() {
        cancelScheduledDownload(ownerDeviceId, videoId)
        player.pause()
        viewModelScope.launch(Dispatchers.IO) {
            services.downloads.delete(ownerDeviceId, videoId)
        }
    }

    fun skipBack() = player.skipBack()
    fun skipForward() = player.skipForward()
    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun recordPlayback(download: DownloadedMedia) {
        if (playbackRecorded) return
        playbackRecorded = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = services.settings.load()
                val account = services.accounts.load(settings.instanceUrl)
                services.historySyncer.recordPlayback(download.asVideoSummary(), settings, account)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Offline playback must not depend on local history or optional sync.
            }
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}

internal class DownloadedMediaScreen(
    private val sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
    private val ownerDeviceId: String,
    private val videoId: String,
) : LightScreen<Unit, DownloadedMediaViewModel>(sealedActivity) {
    override val viewModelClass = DownloadedMediaViewModel::class.java

    override fun createViewModel() = DownloadedMediaViewModel(
        services = services,
        audio = DefaultLightAudio(sealedActivity),
        ownerDeviceId = ownerDeviceId,
        videoId = videoId,
        scheduleDownload = { owner, video, kind ->
            enqueueDownload(lightContext, owner, video, kind)
        },
        cancelScheduledDownload = { owner, video -> cancelDownload(lightContext, owner, video) },
    )

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val audioPlaying by viewModel.audioPlaying.collectAsState()
        val audioPositionMs by viewModel.audioPositionMs.collectAsState()
        val audioDurationMs by viewModel.audioDurationMs.collectAsState()

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
                        center = LightTopBarCenter.Text("Offline"),
                        rightButton = state.download?.let {
                            LightBarButton.LightIcon(
                                icon = LightIcons.DELETE,
                                onClick = {
                                    navigateTo(
                                        screenFactory = { activity ->
                                            ConfirmScreen(
                                                activity,
                                                title = "Delete Download",
                                                message = "Remove this saved copy from your phone?",
                                                confirmLabel = "DELETE",
                                            )
                                        },
                                        resultCallback = { confirmed ->
                                            if (confirmed) {
                                                viewModel.delete()
                                                goBack()
                                            }
                                        },
                                    )
                                },
                                contentDescription = "Delete download",
                            )
                        },
                    )
                    DownloadedMediaContent(
                        state = state,
                        audioPositionMs = audioPositionMs,
                        audioDurationMs = audioDurationMs,
                        modifier = Modifier.weight(1f),
                    )
                    DownloadedMediaActions(
                        state = state,
                        audioPlaying = audioPlaying,
                        onAudio = viewModel::toggleAudio,
                        onWatch = {
                            val source = viewModel.localVideoSource()
                            if (source != null) {
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
                        onRetry = viewModel::retry,
                        onCancel = viewModel::cancel,
                        onSkipBack = viewModel::skipBack,
                        onSkipForward = viewModel::skipForward,
                    )
                }
                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

@Composable
private fun DownloadedMediaContent(
    state: DownloadedMediaUiState,
    audioPositionMs: Long,
    audioDurationMs: Long,
    modifier: Modifier = Modifier,
) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        val download = state.download
        when {
            !state.loaded -> LightText(
                text = "Loading download…",
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            download == null -> LightText(
                text = "This download is no longer on the phone.",
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            else -> {
                LightText(
                    text = download.title,
                    variant = LightTextVariant.Subheading,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                LightText(
                    text = download.author,
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.35f.gridUnitsAsDp()),
                )
                LightText(
                    text = downloadStatusLabel(download),
                    variant = LightTextVariant.Fine,
                    monospace = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
                if (state.audioStarted) {
                    LightText(
                        text = "LISTENING  ${playbackTimeLabel(audioPositionMs, audioDurationMs)}",
                        variant = LightTextVariant.Fine,
                        monospace = true,
                        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                    )
                }
                download.errorMessage?.let { message ->
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadedMediaActions(
    state: DownloadedMediaUiState,
    audioPlaying: Boolean,
    onAudio: () -> Unit,
    onWatch: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    when (downloadedMediaPrimaryAction(state.download)) {
        DownloadPrimaryAction.PLAY_AUDIO -> LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.SKIP_BACKWARD_FIFTEEN,
                    onClick = onSkipBack.takeIf { state.audioStarted },
                    contentDescription = "Back 15 seconds",
                ),
                LightBarButton.LightIcon(
                    icon = if (audioPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                    onClick = onAudio,
                    contentDescription = if (audioPlaying) "Pause" else "Play",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.SKIP_FORWARD_FIFTEEN,
                    onClick = onSkipForward.takeIf { state.audioStarted },
                    contentDescription = "Forward 15 seconds",
                ),
            ),
        )
        DownloadPrimaryAction.WATCH_VIDEO -> LightBottomBar(
            items = listOf(LightBarButton.Text(text = "WATCH", onClick = onWatch)),
        )
        DownloadPrimaryAction.CANCEL -> LightBottomBar(
            items = listOf(LightBarButton.Text(text = "CANCEL", onClick = onCancel)),
        )
        DownloadPrimaryAction.RETRY -> LightBottomBar(
            items = listOf(LightBarButton.Text(text = "RETRY", onClick = onRetry)),
        )
        DownloadPrimaryAction.NONE -> Unit
    }
}
