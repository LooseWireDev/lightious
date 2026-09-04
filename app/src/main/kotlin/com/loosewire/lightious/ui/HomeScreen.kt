package com.loosewire.lightious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.loosewire.lightious.LightiousServices
import com.loosewire.lightious.enqueueDownload
import com.loosewire.lightious.data.AccountSession
import com.loosewire.lightious.data.ClientSettings
import com.loosewire.lightious.data.CompanionProfile
import com.loosewire.lightious.data.CompanionState
import com.loosewire.lightious.data.CuratedVideo
import com.loosewire.lightious.data.DownloadedMedia
import com.loosewire.lightious.data.downloadJobTag
import com.loosewire.lightious.data.ExperienceMode
import com.loosewire.lightious.data.FocusedChannelEntry
import com.loosewire.lightious.data.FocusedLibraryFilter
import com.loosewire.lightious.data.FocusedPlaylistEntry
import com.loosewire.lightious.data.HomePage
import com.loosewire.lightious.data.InvidiousApi
import com.loosewire.lightious.data.effectiveExperienceMode
import com.loosewire.lightious.data.focusedChannels
import com.loosewire.lightious.data.focusedPlaylists
import com.loosewire.lightious.data.normalizeInstanceUrl
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightJobState
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface HomeMode {
    data class Loading(val message: String) : HomeMode
    data object Ready : HomeMode
    data class InstanceEditor(
        val initialValue: String,
        val session: Int,
    ) : HomeMode
}

enum class FocusedHomeTab {
    VIDEOS,
    CHANNELS,
    PLAYLISTS,
    DOWNLOADS,
}

data class HomeUiState(
    val settings: ClientSettings = ClientSettings(),
    val account: AccountSession? = null,
    val companion: CompanionState = CompanionState(),
    val downloads: List<DownloadedMedia> = emptyList(),
    val focusedTab: FocusedHomeTab = FocusedHomeTab.VIDEOS,
    val focusedFilter: FocusedLibraryFilter = FocusedLibraryFilter.ALL,
    val mode: HomeMode = HomeMode.Loading("Loading…"),
    val errorMessage: String? = null,
)

class HomeViewModel(
    private val services: LightiousServices,
    private val resumeInterruptedDownload: suspend (DownloadedMedia) -> Boolean,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var requestJob: Job? = null
    private var editorSession = 0

    init {
        viewModelScope.launch(Dispatchers.IO) {
            services.downloads.observeAll().collect { downloads ->
                _uiState.update { state -> state.copy(downloads = downloads) }
            }
        }
        reload()
    }

    fun reload() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = try {
                services.settings.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        mode = HomeMode.Loading("Could not load settings."),
                        errorMessage = error.userMessage("Could not read saved settings."),
                    )
                }
                return@launch
            }
            if (settings.instanceUrl.isBlank()) {
                showInstanceEditor("")
                return@launch
            }
            val account = try {
                services.accounts.load(settings.instanceUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val cachedCompanion = try {
                services.companion.load(settings.instanceUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                CompanionState()
            }
            _uiState.update { current ->
                current.copy(
                    settings = settings,
                    account = account,
                    companion = cachedCompanion.copy(profile = null),
                    mode = HomeMode.Ready,
                    errorMessage = null,
                )
            }
            var companion = cachedCompanion
            var syncError: String? = null
            if (cachedCompanion.session != null) {
                services.companion.sync(settings.instanceUrl).fold(
                    onSuccess = { profile -> companion = cachedCompanion.copy(profile = profile) },
                    onFailure = { error ->
                        // A paired Home must not display a stale Focused library.
                        // Playback also revalidates, but visibility is part of the
                        // companion contract rather than a security boundary.
                        companion = try {
                            services.companion.load(settings.instanceUrl).copy(profile = null)
                        } catch (loadError: CancellationException) {
                            throw loadError
                        } catch (_: Exception) {
                            CompanionState()
                        }
                        syncError = error.userMessage("Could not sync the companion.")
                    },
                )
            }
            _uiState.update { current ->
                current.copy(
                    settings = settings,
                    account = account,
                    companion = companion,
                    mode = HomeMode.Ready,
                    errorMessage = syncError,
                )
            }
            companion.session?.deviceId?.let { ownerDeviceId ->
                services.downloads.recoverInterruptedDownloads(ownerDeviceId).forEach { download ->
                    if (!resumeInterruptedDownload(download)) {
                        services.downloads.markFailed(
                            ownerDeviceId,
                            download.videoId,
                            "The background download service is unavailable.",
                            download.kind,
                        )
                    }
                }
            }
        }
    }

    fun submitInstance(value: CharSequence) {
        val normalized = runCatching { normalizeInstanceUrl(value.toString()) }
            .getOrElse { error ->
                showInstanceEditor(
                    value.toString(),
                    error.userMessage("Invalid instance URL."),
                )
                return
            }
        requestJob?.cancel()
        _uiState.update {
            it.copy(mode = HomeMode.Loading("Checking server…"), errorMessage = null)
        }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val currentSettings = _uiState.value.settings
            val probe = if (currentSettings.instanceUrl == normalized) {
                services.companion.probeSavedInstance(
                    instanceUrl = normalized,
                    proxyMedia = currentSettings.proxyMedia,
                    audioLanguage = currentSettings.audioLanguage,
                ).getOrElse { error ->
                    showInstanceEditor(normalized, error.userMessage("Could not verify companion access."))
                    return@launch
                }
            } else {
                InvidiousApi(
                    baseUrl = normalized,
                    proxyMedia = currentSettings.proxyMedia,
                    audioLanguage = currentSettings.audioLanguage,
                ).use { api ->
                    api.probe()
                }
            }
            if (!probe.apiAvailable) {
                showInstanceEditor(normalized, probe.message)
                return@launch
            }
            try {
                services.settings.saveInstance(normalized)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showInstanceEditor(normalized, error.userMessage("Could not save the server."))
                return@launch
            }
            reload()
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun selectFocusedTab(tab: FocusedHomeTab) {
        _uiState.update { state -> state.copy(focusedTab = tab) }
    }

    fun selectFocusedFilter(filter: FocusedLibraryFilter) {
        _uiState.update { state -> state.copy(focusedFilter = filter) }
    }

    private fun showInstanceEditor(initialValue: String, error: String? = null) {
        editorSession += 1
        _uiState.update {
            it.copy(
                mode = HomeMode.InstanceEditor(initialValue, editorSession),
                errorMessage = error,
            )
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {
    private val services by lazy { LightiousServices.from(lightContext) }

    override val viewModelClass = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(
        services = services,
        resumeInterruptedDownload = { download ->
            when (
                LightWork.getState(
                    lightContext,
                    downloadJobTag(download.ownerDeviceId, download.videoId, download.kind),
                )
            ) {
                LightJobState.Enqueued,
                LightJobState.Running,
                -> true
                else -> enqueueDownload(
                    lightContext,
                    download.ownerDeviceId,
                    download.videoId,
                    download.kind,
                )
            }
        },
    )

    override fun willShow() {
        viewModel.reload()
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val companionProfile = state.companion.profile
        val experienceMode = companionProfile.effectiveExperienceMode()
        val pairedDownloads = state.companion.session?.deviceId?.let { ownerDeviceId ->
            state.downloads.filter { download -> download.ownerDeviceId == ownerDeviceId }
        }.orEmpty()

        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (val mode = state.mode) {
                    is HomeMode.Loading -> LoadingContent(mode.message)
                    HomeMode.Ready -> when {
                        experienceMode == ExperienceMode.FOCUSED -> FocusedHomeContent(
                            profile = companionProfile,
                            downloads = pairedDownloads,
                            paired = state.companion.session != null,
                            selectedTab = state.focusedTab,
                            selectedFilter = state.focusedFilter,
                            onVideo = ::openFocusedVideo,
                            onChannel = ::openFocusedChannel,
                            onPlaylist = ::openFocusedPlaylist,
                            onDownload = ::openDownload,
                            onTab = viewModel::selectFocusedTab,
                            onFilter = viewModel::selectFocusedFilter,
                            onSearch = { openFocusedSearch(companionProfile, pairedDownloads) },
                            onRefresh = viewModel::reload,
                            onSettings = ::openSettings,
                        )
                        else -> HomeMenuContent(
                            settings = state.settings,
                            signedIn = state.account != null,
                            onPage = ::openPage,
                            onSettings = ::openSettings,
                        )
                    }
                    is HomeMode.InstanceEditor -> InitialInstanceEditor(mode, viewModel)
                }

                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }

    private fun openPage(page: HomePage) {
        when (page) {
            HomePage.SEARCH -> navigateTo(
                screenFactory = { activity -> SearchScreen(activity, services) },
            )
            HomePage.ACCOUNT_FEED,
            HomePage.POPULAR,
            -> navigateTo(
                screenFactory = { activity -> NetworkPageScreen(activity, services, page) },
            )
            HomePage.WATCH_HISTORY ->
                navigateTo(
                    screenFactory = { activity -> WatchHistoryScreen(activity, services) },
                )
            HomePage.SEARCH_HISTORY ->
                navigateTo(
                    screenFactory = { activity -> SearchHistoryScreen(activity, services) },
                )
        }
    }

    private fun openSettings() {
        navigateTo(screenFactory = { activity -> SettingsScreen(activity, services) })
    }

    private fun openFocusedVideo(video: CuratedVideo) {
        navigateTo(
            screenFactory = { activity ->
                VideoScreen(activity, viewModel.uiState.value.settings, video.asVideoSummary(), services)
            },
        )
    }

    private fun openFocusedChannel(channel: FocusedChannelEntry) {
        navigateTo(
            screenFactory = { activity ->
                FocusedChannelScreen(activity, services, viewModel.uiState.value.settings, channel)
            },
        )
    }

    private fun openFocusedPlaylist(playlist: FocusedPlaylistEntry) {
        navigateTo(
            screenFactory = { activity ->
                FocusedPlaylistScreen(activity, services, viewModel.uiState.value.settings, playlist)
            },
        )
    }

    private fun openDownload(download: DownloadedMedia) {
        navigateTo(
            screenFactory = { activity ->
                DownloadedMediaScreen(activity, services, download.ownerDeviceId, download.videoId)
            },
        )
    }

    private fun openFocusedSearch(profile: CompanionProfile?, downloads: List<DownloadedMedia>) {
        navigateTo(
            screenFactory = { activity ->
                FocusedLibrarySearchScreen(
                    activity,
                    services,
                    viewModel.uiState.value.settings,
                    profile,
                    downloads,
                )
            },
        )
    }
}

@Composable
private fun FocusedHomeContent(
    profile: CompanionProfile?,
    downloads: List<DownloadedMedia>,
    paired: Boolean,
    selectedTab: FocusedHomeTab,
    selectedFilter: FocusedLibraryFilter,
    onVideo: (CuratedVideo) -> Unit,
    onChannel: (FocusedChannelEntry) -> Unit,
    onPlaylist: (FocusedPlaylistEntry) -> Unit,
    onDownload: (DownloadedMedia) -> Unit,
    onTab: (FocusedHomeTab) -> Unit,
    onFilter: (FocusedLibraryFilter) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    val channels = profile?.focusedChannels().orEmpty().filter { channel -> channel.includes(selectedFilter) }
    val playlists = profile?.focusedPlaylists().orEmpty().filter { playlist -> playlist.includes(selectedFilter) }
    val videos = profile?.items.orEmpty().filter { video -> selectedFilter.includes(video.playbackPolicy) }
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.SEARCH,
                onClick = onSearch,
                contentDescription = "Search library",
            ),
            center = LightTopBarCenter.Text(selectedTab.focusedTitle()),
            rightButton = LightBarButton.LightIcon(
                icon = LightIcons.SETTINGS,
                onClick = onSettings,
                contentDescription = "Settings",
            ),
        )
        if (selectedTab != FocusedHomeTab.DOWNLOADS && profile != null) {
            FocusedFilterRow(selectedFilter = selectedFilter, onFilter = onFilter)
        }
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (profile == null && selectedTab != FocusedHomeTab.DOWNLOADS) {
                FocusedEmptyMessage(
                    if (paired) {
                        "Sync is required before this paired phone can open its library."
                    } else {
                        "Pair this phone in Settings to load your Focused library."
                    },
                )
                if (paired) {
                    LightText(
                        text = "RETRY SYNC",
                        variant = LightTextVariant.Button,
                        underline = true,
                        modifier = Modifier
                            .padding(top = 1f.gridUnitsAsDp())
                            .lightClickable(onClick = onRefresh),
                    )
                }
            } else when (selectedTab) {
                FocusedHomeTab.VIDEOS -> if (videos.isEmpty()) {
                    FocusedEmptyMessage("No videos match this filter. Send a video from the companion website.")
                } else {
                    videos.forEach { video -> VideoRow(video.asVideoSummary()) { onVideo(video) } }
                }
                FocusedHomeTab.CHANNELS -> if (channels.isEmpty()) {
                    FocusedEmptyMessage("No channels match this filter. Add a channel from the companion website.")
                } else {
                    channels.forEach { channel -> ChannelRow(channel) { onChannel(channel) } }
                }
                FocusedHomeTab.PLAYLISTS -> if (playlists.isEmpty()) {
                    FocusedEmptyMessage("No playlists match this filter. Build one from videos in the companion website.")
                } else {
                    playlists.forEach { playlist -> PlaylistRow(playlist) { onPlaylist(playlist) } }
                }
                FocusedHomeTab.DOWNLOADS -> if (downloads.isEmpty()) {
                    FocusedEmptyMessage("No downloads yet. Open a video in your library to save it offline.")
                } else {
                    downloads.forEach { download -> DownloadRow(download) { onDownload(download) } }
                }
            }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.MEDIA,
                    onClick = { onTab(FocusedHomeTab.VIDEOS) },
                    contentDescription = "Videos",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.CONTACTS,
                    onClick = { onTab(FocusedHomeTab.CHANNELS) },
                    contentDescription = "Channels",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.LIST,
                    onClick = { onTab(FocusedHomeTab.PLAYLISTS) },
                    contentDescription = "Playlists",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.DOWNLOAD_ARROW,
                    onClick = { onTab(FocusedHomeTab.DOWNLOADS) },
                    contentDescription = "Downloads",
                ),
            ),
        )
    }
}

@Composable
internal fun FocusedFilterRow(
    selectedFilter: FocusedLibraryFilter,
    onFilter: (FocusedLibraryFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        FocusedLibraryFilter.entries.forEach { filter ->
            LightText(
                text = filter.filterLabel(),
                variant = LightTextVariant.Button,
                underline = filter == selectedFilter,
                align = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .lightClickable(onClick = { onFilter(filter) })
                    .padding(vertical = 0.45f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun FocusedEmptyMessage(message: String) {
    LightText(
        text = message,
        variant = LightTextVariant.Copy,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
    )
}

internal fun FocusedLibraryFilter.filterLabel(): String = when (this) {
    FocusedLibraryFilter.ALL -> "ALL"
    FocusedLibraryFilter.LISTEN -> "AUDIO"
    FocusedLibraryFilter.WATCH -> "VIDEO"
}

private fun FocusedHomeTab.focusedTitle(): String = when (this) {
    FocusedHomeTab.VIDEOS -> "Videos"
    FocusedHomeTab.CHANNELS -> "Channels"
    FocusedHomeTab.PLAYLISTS -> "Playlists"
    FocusedHomeTab.DOWNLOADS -> "Downloads"
}

@Composable
private fun HomeMenuContent(
    settings: ClientSettings,
    signedIn: Boolean,
    onPage: (HomePage) -> Unit,
    onSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text("Lightious"),
            rightButton = LightBarButton.LightIcon(
                icon = LightIcons.SETTINGS,
                onClick = onSettings,
                contentDescription = "Settings",
            ),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (settings.homePages.isEmpty()) {
                LightText(
                    text = "Choose at least one bottom navigation page in Settings.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            } else {
                LightText(
                    text = "Choose a page from the bottom bar.",
                    variant = LightTextVariant.Subheading,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
                LightText(
                    text = settings.homePages.joinToString("  ·  ") { it.homeLabel() },
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                if (HomePage.ACCOUNT_FEED in settings.homePages && !signedIn) {
                    LightText(
                        text = "Sign in from Settings to use subscriptions.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
        if (settings.homePages.isNotEmpty()) {
            LightBottomBar(
                items = settings.homePages.distinct().map { page ->
                    LightBarButton.LightIcon(
                        icon = page.homeIcon(),
                        onClick = { onPage(page) },
                        contentDescription = page.homeLabel(),
                    )
                },
            )
        }
    }
}

@Composable
private fun InitialInstanceEditor(mode: HomeMode.InstanceEditor, viewModel: HomeViewModel) {
    key(mode.session) {
        val text = rememberTextFieldState(mode.initialValue)
        val keyboardOptions = rememberKeyboardOptions()
        LightTextInputEditor(
            title = "Invidious Server",
            state = text,
            keyboardOptionsFlow = keyboardOptions,
            onSubmit = viewModel::submitInstance,
            onBack = {},
            submitLabel = "CHECK",
            showBackButton = false,
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun LoadingContent(message: String, title: String = "Lightious") {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(center = LightTopBarCenter.Text(title))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
    }
}

@Composable
internal fun ActionRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Subheading,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.8f.gridUnitsAsDp()),
    )
}

@Composable
internal fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Superfine, lighten = true)
        LightText(
            text = value,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 0.15f.gridUnitsAsDp()),
        )
    }
}

internal fun HomePage.homeLabel(): String = when (this) {
    HomePage.SEARCH -> "SEARCH"
    HomePage.ACCOUNT_FEED -> "ACCOUNT FEED"
    HomePage.WATCH_HISTORY -> "WATCH HISTORY"
    HomePage.SEARCH_HISTORY -> "SEARCH HISTORY"
    HomePage.POPULAR -> "POPULAR"
}

private fun HomePage.homeIcon() = when (this) {
    HomePage.SEARCH -> LightIcons.SEARCH
    HomePage.ACCOUNT_FEED -> LightIcons.CONTACTS
    HomePage.WATCH_HISTORY -> LightIcons.PLAY
    HomePage.SEARCH_HISTORY -> LightIcons.LIST
    HomePage.POPULAR -> LightIcons.STAR
}

internal fun Throwable.userMessage(fallback: String): String =
    message?.trim()?.takeIf(String::isNotEmpty) ?: fallback
