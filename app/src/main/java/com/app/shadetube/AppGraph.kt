package com.app.shadetube

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.app.shadetube.app.AppBrowseSource
import com.app.shadetube.app.AppCollectionEnhancer
import com.app.shadetube.app.AppCollectionWriter
import com.app.shadetube.app.AppLikeHandler
import com.app.shadetube.app.AppLinkLookups
import com.app.shadetube.app.AppPlayerControls
import com.app.shadetube.app.AppRecommender
import com.app.shadetube.app.AppReplayGain
import com.app.shadetube.app.AppSmartListsSources
import com.app.shadetube.app.AppSponsorCuts
import com.app.shadetube.app.AppStatsArtistImages
import com.app.shadetube.app.AppYouTubeTransfer
import com.app.shadetube.app.BlacklistSyncBlocklist
import com.app.shadetube.app.DownloadsLocalSource
import com.app.shadetube.app.LibraryCollections
import com.app.shadetube.app.LibraryPlaylistTarget
import com.app.shadetube.app.LocalFilesSource
import com.app.shadetube.app.OpenRequests
import com.app.shadetube.app.PendingOpen
import com.app.shadetube.app.ResourceRadioLabels
import com.app.shadetube.app.SessionLyricsSource
import com.app.shadetube.app.SessionRadioSource
import com.app.shadetube.app.SessionStatsSpotify
import com.app.shadetube.app.SessionTransfer
import com.app.shadetube.app.albumLoader
import com.app.shadetube.app.downloadedFiles
import com.app.shadetube.app.localTracksSource
import com.app.shadetube.app.musicBrainzTransfer
import com.app.shadetube.app.samplePlayer
import com.app.shadetube.app.statsCatalogSearch
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Log
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.appScope
import com.app.shadetube.downloads.DownloadManager
import com.app.shadetube.downloads.DownloadNotificationLabels
import com.app.shadetube.downloads.DownloadNotifications
import com.app.shadetube.downloads.DownloadSync
import com.app.shadetube.downloads.DownloadsRuntime
import com.app.shadetube.downloads.NetworkMonitor
import com.app.shadetube.downloads.SessionSyncSource
import com.app.shadetube.downloads.YoutubeTrackDownloader
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.library.ArtistRepository
import com.app.shadetube.library.CatalogSource
import com.app.shadetube.library.CollectionDeps
import com.app.shadetube.library.CollectionEnhancer
import com.app.shadetube.library.CollectionLabels
import com.app.shadetube.library.HomeRepository
import com.app.shadetube.library.LibraryBrowser
import com.app.shadetube.library.LibraryLabels
import com.app.shadetube.library.MusicBrainzCatalog
import com.app.shadetube.library.PlayerControls
import com.app.shadetube.library.PlaylistActions
import com.app.shadetube.library.SearchRepository
import com.app.shadetube.library.SessionAccount
import com.app.shadetube.library.SpotifyAccount
import com.app.shadetube.library.UserLibrary
import com.app.shadetube.links.LinkLookups
import com.app.shadetube.localfiles.LocalFileResolver
import com.app.shadetube.localfiles.LocalFormats
import com.app.shadetube.localfiles.LocalLibrary
import com.app.shadetube.localfiles.LocalPlatform
import com.app.shadetube.localfiles.OpenedFiles
import com.app.shadetube.lyrics.LyricsService
import com.app.shadetube.musicbrainz.MusicBrainz
import com.app.shadetube.playback.EndReason
import com.app.shadetube.playback.PlaybackDeps
import com.app.shadetube.playback.PlaybackModule
import com.app.shadetube.playback.Player
import com.app.shadetube.playback.PlayerEvent
import com.app.shadetube.playback.Status
import com.app.shadetube.playback.features.Blacklist
import com.app.shadetube.playback.features.Radio
import com.app.shadetube.playback.features.SleepTimer
import com.app.shadetube.playback.features.SmartShuffle
import com.app.shadetube.playback.features.SponsorBlockController
import com.app.shadetube.playback.service.BrowseLabels
import com.app.shadetube.playback.service.VoiceCatalog
import com.app.shadetube.playback.service.VoiceSearch
import com.app.shadetube.playlistio.LocalFilesAccess
import com.app.shadetube.playlistio.PlaylistTransfer
import com.app.shadetube.podcasts.PodcastConnection
import com.app.shadetube.podcasts.PodcastRefreshWorker
import com.app.shadetube.podcasts.Podcasts
import com.app.shadetube.scrobble.Scrobbler
import com.app.shadetube.spotify.Session
import com.app.shadetube.spotify.SpotifyLabels
import com.app.shadetube.stats.ListenStatsService
import com.app.shadetube.stats.SmartListsService
import com.app.shadetube.stats.SmartMixRecommender
import com.app.shadetube.stats.StatsArtwork
import com.app.shadetube.update.Updater
import com.app.shadetube.widget.WidgetUpdater
import com.app.shadetube.youtube.MatchService
import com.app.shadetube.youtube.explode.YoutubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Composition root (port of app/App + AppContext): owns the long-lived services, constructs each once in dependency
 * order and wires them together. The UI reaches services only through this object (directly or via ViewModels that
 * read these properties). Built by [ShadeTubeApp.onCreate] on the main thread, so a process started for a media button,
 * Android Auto or a worker finds everything ready. See docs/ARCHITECTURE.md.
 */
@SuppressLint("StaticFieldLeak") // only the application context (and services holding it) are kept
object AppGraph {
    private const val TAG = "app"

    lateinit var context: Context
        private set

    @Volatile
    var initialized = false
        private set

    /** Main-thread scope for the event wiring (lives as long as the process). */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- Spotify / catalog ------------------------------------------------------------------------------------------
    lateinit var session: Session
        private set
    lateinit var account: SpotifyAccount
        private set
    lateinit var matchService: MatchService
        private set
    val youtube: YoutubeClient get() = matchService.client
    lateinit var lyrics: LyricsService
        private set
    lateinit var linkLookups: LinkLookups
        private set

    /** Podcasts and internet radio are process-wide objects (initialized here). */
    val podcasts: Podcasts get() = Podcasts
    val internetRadio: InternetRadio get() = InternetRadio

    // ---- Local files ------------------------------------------------------------------------------------------------
    lateinit var localLibrary: LocalLibrary
        private set
    lateinit var openedFiles: OpenedFiles
        private set
    lateinit var localFiles: LocalFileResolver
        private set

    // ---- Library ----------------------------------------------------------------------------------------------------
    lateinit var library: UserLibrary
        private set
    lateinit var catalog: CatalogSource
        private set
    lateinit var playlistActions: PlaylistActions
        private set
    lateinit var home: HomeRepository
        private set
    lateinit var search: SearchRepository
        private set
    lateinit var libraryBrowser: LibraryBrowser
        private set
    lateinit var artists: ArtistRepository
        private set
    lateinit var collections: CollectionDeps
        private set

    // ---- Playback ---------------------------------------------------------------------------------------------------
    lateinit var playback: PlaybackModule
        private set
    val player: Player get() = playback.player
    val blacklist: Blacklist get() = playback.blacklist
    val radio: Radio get() = playback.radio
    val sponsorBlock: SponsorBlockController get() = playback.sponsorBlock
    val smartShuffle: SmartShuffle get() = playback.smartShuffle
    val sleepTimer: SleepTimer get() = playback.sleepTimer
    lateinit var playerControls: PlayerControls
        private set
    lateinit var enhancer: CollectionEnhancer
        private set

    // ---- Stats / scrobbling -------------------------------------------------------------------------------------------
    lateinit var stats: ListenStatsService
        private set
    lateinit var statsArtwork: StatsArtwork
        private set
    lateinit var smartLists: SmartListsService
        private set
    lateinit var recommender: SmartMixRecommender
        private set
    lateinit var scrobbler: Scrobbler
        private set

    // ---- Downloads / transfer / update --------------------------------------------------------------------------------
    lateinit var network: NetworkMonitor
        private set
    lateinit var downloads: DownloadManager
        private set
    lateinit var sync: DownloadSync
        private set
    lateinit var transfer: PlaylistTransfer
        private set
    lateinit var updater: Updater
        private set

    // ---- Things handed to the app from outside --------------------------------------------------------------------------
    private val _pendingOpen = MutableStateFlow<PendingOpen?>(null)

    /** Files / a playlist opened from another app (VIEW / SEND), prepared for the UI; null = nothing pending. */
    val pendingOpen: StateFlow<PendingOpen?> = _pendingOpen.asStateFlow()

    fun consumePendingOpen() {
        _pendingOpen.value = null
    }

    // ---- Construction -------------------------------------------------------------------------------------------------

    /**
     * Builds the graph (main thread, once). Order: settings (ShadeTubeApp) -> session / matching / lyrics / podcasts /
     * radio -> local files -> library -> playback (needs radio + podcasts initialized) -> stats / scrobbling / smart
     * lists -> downloads / sync / transfer -> updater; then the event wiring, the background loads and the lifecycle
     * hooks.
     */
    fun init(app: Context) {
        if (initialized) return
        val ctx = app.applicationContext
        context = ctx

        // Spotify, YouTube matching, lyrics, podcasts, internet radio.
        session = Session()
        account = SessionAccount(session)
        session.restore()
        matchService = MatchService()
        val lyricsSource = SessionLyricsSource(session)
        lyrics = LyricsService(spotify = { if (session.loggedIn) lyricsSource else null })
        linkLookups = AppLinkLookups(session) { matchService.client }
        Podcasts.init()
        InternetRadio.init()
        PodcastRefreshWorker.schedule(ctx)

        // Local files.
        localLibrary = LocalPlatform.createLibrary(ctx)
        openedFiles = LocalPlatform.createOpenedFiles(ctx)
        localFiles = LocalFileResolver(localLibrary, openedFiles)

        // Library (library.json is read in the background below).
        library = UserLibrary(account, isStationId = InternetRadio::isStation)
        catalog = CatalogSource(account)
        playlistActions = PlaylistActions(library, account)
        home = HomeRepository(account, library, MusicBrainzCatalog)
        search = SearchRepository(catalog)
        VoiceSearch.catalog = VoiceCatalog.of({ catalog.search(it, 10) }, { catalog.album(it) }, { catalog.artistPage(it).topTracks })
        libraryBrowser = LibraryBrowser(account, library)

        // Recommendations (used by playback's smart shuffle; the blocklist is playback's, looked up lazily).
        val statsSpotify = SessionStatsSpotify(session)
        recommender = SmartMixRecommender(
            spotify = { if (session.loggedIn) statsSpotify else null },
            local = localTracksSource { localLibrary.current.tracks },
            blocked = { t -> PlaybackModule.instance?.blacklist?.isBlocked(t) == true },
        )

        // The download queue exists before the player (it is one of its offline sources); loaded in the background.
        network = NetworkMonitor(ctx)
        Podcasts.attachNetwork(network.state.map { PodcastConnection(it.connected, it.metered) })
        val sponsorCuts = AppSponsorCuts { PlaybackModule.instance?.sponsorBlock?.sponsor?.categories ?: emptyList() }
        downloads = DownloadManager(YoutubeTrackDownloader(matchService, lyrics, sponsorCuts), network = network)

        // Playback (main thread; restores session.json).
        playback = PlaybackModule.create(
            ctx,
            PlaybackDeps(
                matchService = matchService,
                localSources = listOf(DownloadsLocalSource { downloads }, LocalFilesSource(localFiles)),
                replayGain = AppReplayGain(localLibrary.opener),
                radioSource = SessionRadioSource(session),
                recommender = AppRecommender(recommender),
                collectionWriter = AppCollectionWriter(session, library, playlistActions),
                browse = AppBrowseSource(session, library) { collections.labels.likedSongs },
                likes = AppLikeHandler(library),
            ),
        )
        playerControls = AppPlayerControls { playback.player }
        enhancer = AppCollectionEnhancer({ playback.smartShuffle }, recommender)
        artists = ArtistRepository(catalog, library, playerControls)
        collections = CollectionDeps(account, library, catalog, playerControls, enhancer)

        // Stats, scrobbling, smart lists.
        stats = ListenStatsService()
        stats.probe = { samplePlayer(playback.player) }
        stats.start()
        scrobbler = Scrobbler()
        statsArtwork = StatsArtwork(statsCatalogSearch(session), AppStatsArtistImages(session, library))
        smartLists = SmartListsService(
            stats.store,
            AppSmartListsSources(session, library),
            blocked = playback.blacklist::isBlocked,
            artwork = statsArtwork,
        )

        // Download sync, playlist import / export.
        sync = DownloadSync(
            downloads,
            SessionSyncSource(session),
            LibraryCollections(library),
            BlacklistSyncBlocklist { playback.blacklist },
            network = network,
        )
        transfer = PlaylistTransfer(
            LocalFilesAccess.of(localLibrary, openedFiles),
            LibraryPlaylistTarget(library),
            SessionTransfer(session),
            AppYouTubeTransfer(matchService),
            musicBrainzTransfer,
            albumLoader { catalog.album(it) },
            downloadedFiles { downloads },
        )

        // Updates.
        updater = Updater(ctx)

        initialized = true
        applyLabels(ctx)
        wireEvents()
        WidgetUpdater.start(ctx)
        startBackground(ctx)
        installLifecycleHooks()
        updater.start()
        Log.i(TAG, "graph ready")
    }

    /** Player events -> stats / scrobbling / history; cross-service notifications. */
    private fun wireEvents() {
        val p = playback.player
        mainScope.launch {
            p.events.collect { e ->
                when (e) {
                    is PlayerEvent.TrackStarted -> {
                        stats.onTrackChanged(e.track, p.positionMs())
                        scrobbler.onTrackChanged(e.track)
                        // Stations and episodes keep their own recents (internetradio / podcasts), not the song history.
                        if (!e.isLive && !e.isEpisode) library.recordPlay(e.track)
                    }
                    is PlayerEvent.Progress -> {
                        stats.onPlayerChanged()
                        scrobbler.onProgress(e.positionMs, e.status == Status.Playing, e.durationMs)
                    }
                    is PlayerEvent.StatusChanged -> {
                        stats.onPlayerChanged()
                        if (e.track == null || e.status == Status.Idle) scrobbler.onStopped()
                    }
                    is PlayerEvent.TrackEnded -> if (e.reason == EndReason.QueueEnded || e.reason == EndReason.Stopped) {
                        stats.onPlayerChanged()
                        scrobbler.onStopped()
                    }
                    is PlayerEvent.QueueChanged -> Unit
                }
            }
        }
        // Blocklist edits: smart lists re-filter (the player / radio follow it inside PlaybackModule).
        playback.blacklist.onChanged { smartLists.onBlocklistChanged() }
        // Hearts changed in the app: refresh the notification's like button.
        mainScope.launch { library.revision.drop(1).collect { playback.invalidateMediaButtons() } }
        mainScope.launch { session.library.map { it.likedIds }.distinctUntilChanged().drop(1).collect { playback.invalidateMediaButtons() } }
        // Login / logout: smart lists rebuild for the new user.
        mainScope.launch { session.state.drop(1).collect { smartLists.ensure() } }
        // ListenBrainz token of the scrobbler -> authenticated ListenBrainz reads.
        appScope.launch {
            scrobbler.state.map { scrobbler.listenbrainzToken() }.distinctUntilChanged().collect { MusicBrainz.setListenBrainzToken(it) }
        }
        playback.onServiceDestroyed = { flush("service destroyed") }
    }

    /** Disk loads and the services that start working on their own. */
    private fun startBackground(ctx: Context) {
        network.start()
        localLibrary.start()
        appScope.launch(AppDispatchers.io) {
            step("scrobbler") { scrobbler.load() }
            step("library") { library.load() }
            step("opened files") { openedFiles.load() }
            step("match cache") { matchService.preload() }
        }
        appScope.launch(AppDispatchers.io) {
            step("downloads") {
                downloads.load()
                sync.load()
                DownloadsRuntime.install(ctx, downloads, sync)
                downloads.start()
                sync.start()
            }
        }
    }

    private inline fun step(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "startup: $what failed", e)
        }
    }

    private fun installLifecycleHooks() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) flush("background") },
        )
    }

    /**
     * Persists everything that may be dirty (the C++ persistHooks): the app went to the background or the playback
     * service is being destroyed — the process may be killed without further notice. The current play is written
     * synchronously (main thread); the stores on a worker.
     */
    fun flush(reason: String) {
        if (!initialized) return
        Log.i(TAG, "flush ($reason)")
        runCatching { stats.persist() }.onFailure { Log.w(TAG, "stats persist failed: ${it.message}") }
        runCatching { playback.player.saveSession() }
        appScope.launch(AppDispatchers.io) {
            step("settings") { SettingsStore.flush() }
            step("library") { library.flush() }
            step("downloads") { downloads.flush() }
            step("sync") { sync.flush() }
            step("podcasts") { Podcasts.flush() }
            step("radio") { InternetRadio.flush() }
            step("matches") { matchService.flush() }
        }
    }

    // ---- Localized labels -----------------------------------------------------------------------------------------------

    /**
     * Installs the localized texts the backend needs (it has no user-facing strings of its own). Called at startup with
     * the application context and again by the UI with its (per-app locale) context, e.g. after a language change.
     */
    fun applyLabels(context: Context) {
        if (!initialized) return
        val liked = context.getString(R.string.library_liked_songs)
        val playlist = context.getString(R.string.label_playlist)
        val spotifyLabels = SpotifyLabels(likedSongs = liked, playlist = playlist, artist = context.getString(R.string.label_artist))
        if (session.api.labels != spotifyLabels) {
            val changed = session.api.labels != SpotifyLabels() || session.library.value.loaded
            session.api.labels = spotifyLabels
            if (changed) session.reloadPlaylists() // the Liked Songs pseudo-playlist is named by the labels
        }
        library.labels = LibraryLabels(newPlaylist = context.getString(R.string.library_new_playlist))
        collections.labels = CollectionLabels(likedSongs = liked, playlist = playlist)
        home.ownSectionTitles = listOf(
            context.getString(R.string.home_your_playlists),
            context.getString(R.string.home_saved_albums),
            context.getString(R.string.home_followed_artists),
            context.getString(R.string.home_recently_played),
        )
        InternetRadio.labels = ResourceRadioLabels(context.resources)
        playback.setBrowseLabels(
            BrowseLabels(recent = context.getString(R.string.auto_browse_recent), playlists = context.getString(R.string.auto_browse_playlists)),
        )
        DownloadNotifications.labels = DownloadNotificationLabels(
            channelName = context.getString(R.string.downloads_notification_channel),
            title = context.getString(R.string.downloads_notification_title),
            queuedFormat = context.getString(R.string.downloads_notification_queued),
        )
    }

    // ---- Opened files / playlists ---------------------------------------------------------------------------------------

    /**
     * Uris handed to the app (VIEW / SEND / SEND_MULTIPLE): a playlist file becomes an import preview, audio files are
     * read like opened files and remembered. The result lands in [pendingOpen].
     */
    fun openUris(uris: List<String>, mimeType: String?) {
        if (!initialized || uris.isEmpty()) return
        val resolver = context.contentResolver
        _pendingOpen.value = PendingOpen.Loading
        appScope.launch {
            val first = uris.first()
            val name = withContext(AppDispatchers.io) { OpenRequests.displayName(resolver, first) }
            val result = if (uris.size == 1 && OpenRequests.isPlaylist(mimeType, name)) {
                val display = OpenRequests.playlistName(name, mimeType)
                transfer.previewFile({ resolver.openInputStream(android.net.Uri.parse(first)) ?: throw java.io.IOException("cannot open $first") }, display)
                    .fold(
                        onSuccess = { PendingOpen.PlaylistImport(it) },
                        onFailure = { PendingOpen.Failed(PendingOpen.Failed.Reason.PlaylistUnreadable) },
                    )
            } else {
                val collected = runCatching { openedFiles.collect(uris) }.getOrNull()
                if (collected == null || collected.entries.isEmpty()) {
                    PendingOpen.Failed(PendingOpen.Failed.Reason.NothingPlayable)
                } else {
                    openedFiles.remember(collected.entries)
                    PendingOpen.AudioFiles(collected.entries.map { LocalFormats.toTrack(it) }, collected.skipped)
                }
            }
            _pendingOpen.value = result
        }
    }
}
