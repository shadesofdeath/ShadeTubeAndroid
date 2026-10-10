# Wiring notes per module (from the porting reports)

What `AppGraph` must construct, implement and connect. Interfaces live in the consuming package; adapters live in
`com.app.shadetube.app` (or inside AppGraph). Order of startup matters where noted.

## core
- `Paths.init`, `SettingsStore.load` (ShadeTubeApp.onCreate, before everything).
- DONE (integration): the strict JSON parser of lyrics / musicbrainz is `core.parseStrictJson`; `PublicOnlyInterceptor` /
  `executeCancellable` / `readCapped` of podcasts / internetradio live in `core/Net.kt`. `spotify/SpJson.kt` (typed
  accessors, not a parser) stays. All of this file is wired in `AppGraph` — see `ARCHITECTURE.md`.

## spotify
- One `Session()`; `restore()` at startup (switches to Connecting if spotify.dat exists).
- `session.api.labels = SpotifyLabels(likedSongs, playlist, artist)` from strings.xml; `reloadPlaylists()` after a UI
  language change.
- Login: `registerForActivityResult(SpotifyLoginContract()) { ok -> }`, `launch(Unit)`. Session registers itself as
  `Session.current` for the activity. Manifest entry for `.spotify.SpotifyLoginActivity` is in place.
- Errors: reads throw `ApiError(status)` / IOException; writes return Boolean.

## youtube
- `MatchService()` after `SettingsStore.load()` (observes altSource settings). `flush()` on exit.
- `matchFailureOf(t): MatchFailure` for UI errors. `YoutubeClient` via `matchService.client`.

## musicbrainz / lyrics / links
- `LyricsService(spotify = { SpotifyLyricsSource { uri -> if (!session.loggedIn) null else session.api.trackLyrics(uri) } })`
  — must return null while logged out and throw core `HttpError(status)` on HTTP errors (429/403 pause logic).
- Lyrics extra ranges: `playback.sponsorBlock.extra` (StateFlow<List<Pair<Long,Long>>>).
- `LinkLookups`: spotifyLoggedIn, spotifyTrack(uri)/spotifyAlbum(id) (session.api), mbRecording/mbAlbum/
  mbReleaseGroupOfRelease (MusicBrainz), youtubeVideo(id) (YoutubeClient). Callers of `LinkOpener.open` map
  `PinnedVideo` → youtube `Match` and call `MatchService.pin`.
- `MusicBrainz.setListenBrainzToken(token)` from the scrobbler's ListenBrainz token.

## podcasts / internetradio
- `Podcasts.init()`, `InternetRadio.init()`, `PodcastRefreshWorker.schedule(context)` (unique periodic
  "podcast-refresh", 3 h, network, KEEP). `InternetRadio.labels` = strings.xml `RadioLabels`.
- `Podcasts.flush()` / `InternetRadio.flush()` when the app goes to background / service destroyed.
- Podcast downloads ignore `downloadWifiOnly` (gate if wanted).

## playback
- `PlaybackModule.create(context, PlaybackDeps(...))` in `AppGraph.init` on the main thread, AFTER
  `InternetRadio.init()` and podcasts init (Application.onCreate, so a media-button/Auto start has the Player).
  Deps: `matchService`; `localSources = listOf(downloads (doneFile → LocalSource.of), local library (file or content
  Uri + mime))`; `replayGain = ReplayGainReader` (local files: `localfiles` ReplayGain incl. content Uris);
  `radioSource` (Session: radioPlaylist / playlistTracks, map `ApiError` → `RadioSourceError(status,msg)`);
  `recommender` (over `stats.SmartMixRecommender` recommend/hide/isHidden); `collectionWriter` (UserLibrary:
  canAdd / add to "liked" or playlist id); `browse` (`BrowseSource`: recentlyPlayed, playlists, playlistTracks);
  `likes` (`LikeHandler`: isLiked / canLike / setLiked → UserLibrary).
- Already wired inside: podcasts, internet radio, blacklist→shouldSkip, endless playback, SponsorBlock, smart shuffle,
  sleep timer, EQ observer, restoreSession.
- `playback.player.events`: TrackStarted(isLive,isEpisode) / Progress (~1 s) / TrackEnded / QueueChanged /
  StatusChanged → feed stats, scrobbler, library history.
- After an in-app like: `playback.invalidateMediaButtons()`.
- Manifest: FOREGROUND_SERVICE_MEDIA_PLAYBACK, WAKE_LOCK; service `.playback.service.PlaybackService`
  (exported, mediaPlayback, actions MediaLibraryService + MediaBrowserService); `MediaButtonReceiver`;
  `com.google.android.gms.car.application` meta-data → `res/xml/automotive_app_desc.xml`.
- Notification small icon: `R.drawable.ic_notification`. Button labels are hard-coded English → move to strings.

## library
- `SessionAccount(session)`; `UserLibrary(account, isStationId = <internetradio station check>)`;
  `appScope.launch { library.load() }`; `library.flush()` on stop.
- `CatalogSource(account)`, `PlaylistActions(library, account)`, `HomeRepository(account, library, MusicBrainzCatalog)`,
  `SearchRepository(source)`, `LibraryBrowser(account, library)`, `ArtistRepository(source, library, player)`,
  `CollectionDeps(account, library, source, player, enhancer)`.
- Implement `PlayerControls` (over playback.Player) and `CollectionEnhancer` (over playback smartShuffle enhanced
  lists + stats SmartMix interleave/recommender).
- UI sets localized `library.labels`, `collectionDeps.labels`, `home.ownSectionTitles`.

## stats / scrobble
- `ListenStatsService()`: `probe` (player state on main), `start()`; on track change `onTrackChanged(t,pos)`; on state
  change `onPlayerChanged()`; `persist()` on ON_STOP / service destroy.
- `Scrobbler()`: `load()`; `onTrackChanged`, `onProgress(pos, playing, dur)` every ~1–2 s, `onStopped()` when idle.
- `StatsArtwork(spotify = { StatsCatalogSearch? (null logged out) }, artistImageSource = StatsArtistImages)`.
- `SmartListsService(stats.store, SmartListsSources, blocked = blacklist::isBlocked, artwork)`; `ensure()` from Home /
  Library / list page / Stats; `onBlocklistChanged()` when the blocklist changes.
- `SmartMixRecommender(spotify = { SpotifyRadioSource? }, local = LocalTracksSource, blocked)` — throw
  `RateLimitedException` on Spotify 429.

## localfiles / playlistio
- `LocalPlatform.createLibrary(ctx)` → `lib.start()`; `LocalPlatform.createOpenedFiles(ctx)` → `opened.load()`;
  `lib.cancel()` on exit. Player resolver `LocalFileResolver(lib, opened)` (null = error, never fall back to YouTube).
- `PlaylistTransfer(LocalFilesAccess.of(lib, opened), LocalPlaylistTarget(UserLibrary), SpotifyTransfer,
  YouTubeTransfer, MusicBrainzTransfer, AlbumLoader, DownloadedFiles)`.
- Manifest: intent filters VIEW / SEND / SEND_MULTIPLE for audio/* and playlist mimes (audio/x-mpegurl,
  application/vnd.apple.mpegurl, text/csv, application/xspf+xml, application/json) on MainActivity.

## downloads / update
- `NetworkMonitor(ctx).start()`; `YoutubeTrackDownloader(matchService, lyricsService, sponsorCuts: SponsorCutSource
  (playback SponsorBlock lookup, empty on failure))`; `DownloadManager(downloader, network)`;
  `DownloadSync(downloads, SessionSyncSource(session), LocalCollections (UserLibrary), SyncBlocklist (blacklist))`.
- On IO: `downloads.load()`, `sync.load()`, `DownloadsRuntime.install(ctx, downloads, sync)`, `downloads.start()`,
  `sync.start()`. `downloads.flush()` / `sync.flush()` on exit. `DownloadNotifications.labels` from strings.
- `Updater(ctx).start()`.
