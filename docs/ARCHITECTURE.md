# ShadeTube Android — architecture

How the ported packages (see `PORTING.md`) run together. `AppGraph` (`com.app.shadetube.AppGraph`) is the composition
root; the adapters it uses to plug one package into another live in `com.app.shadetube.app`.

## Startup order

`ShadeTubeApp.onCreate` (main thread, every process start — also for a media button, Android Auto or a WorkManager
worker):

1. `Paths.init`, crash logger, `SettingsStore.load()`.
2. `AppGraph.init(app)`:
   1. Spotify `Session` (+ `restore()` from spotify.dat), `MatchService`, `LyricsService`, link lookups,
      `Podcasts.init()`, `InternetRadio.init()`, `PodcastRefreshWorker.schedule()`.
   2. Local files: `LocalLibrary`, `OpenedFiles`, `LocalFileResolver`.
   3. Library: `UserLibrary`, `CatalogSource`, `PlaylistActions`, `HomeRepository`, `SearchRepository`, `LibraryBrowser`.
   4. `SmartMixRecommender` and the `DownloadManager` (constructed only; both are inputs of playback).
   5. `PlaybackModule.create(...)` — the `Player`, ExoPlayer engine, blocklist, radio, SponsorBlock, smart shuffle,
      sleep timer; restores session.json. Must stay on the main thread inside `Application.onCreate`.
   6. `ArtistRepository`, `CollectionDeps` (need the player adapter).
   7. `ListenStatsService` (+ `start()`), `Scrobbler`, `StatsArtwork`, `SmartListsService`.
   8. `DownloadSync`, `PlaylistTransfer`, `Updater`.
   9. `applyLabels(app)`, event wiring, background loads, `ProcessLifecycleOwner` hook, `updater.start()`.
3. Background (IO): scrobble.dat, library.json, dropped-files.json, matches.json; downloads.json + sync.json →
   `DownloadsRuntime.install` → `downloads.start()` / `sync.start()`. `NetworkMonitor.start()` and
   `LocalLibrary.start()` (index load + delayed rescan) start their own work.

`MainActivity` calls `AppGraph.applyLabels(this)` again with the activity's (per-app) locale.

## Service graph (who owns what)

```
Session ─┬─ SessionAccount ─┬─ UserLibrary ─┬─ PlaylistActions / LibraryBrowser / HomeRepository
         │                  │               └─ (history ← player events)
         │                  └─ CatalogSource ─ SearchRepository / ArtistRepository / CollectionDeps
         ├─ lyrics (SessionLyricsSource)   ├─ radio (SessionRadioSource)   ├─ stats (SessionStatsSpotify)
         ├─ links (AppLinkLookups)          ├─ sync (SessionSyncSource)     └─ transfer (SessionTransfer)
MatchService ─ Player (MatchServiceResolver) ─ YoutubeTrackDownloader ─ DownloadManager ─ DownloadSync
PlaybackModule ─ Player, Blacklist, Radio, SponsorBlockController, SmartShuffle, SleepTimer, BrowseTree, LikeHandler
Player.events ─▶ ListenStatsService, Scrobbler, UserLibrary.recordPlay (+ Podcasts / InternetRadio inside playback)
Blacklist.onChanged ─▶ Player.skipRulesChanged + Radio (inside PlaybackModule), SmartListsService.onBlocklistChanged
Scrobbler.state (ListenBrainz token) ─▶ MusicBrainz.setListenBrainzToken
UserLibrary.revision / Session liked ids ─▶ PlaybackModule.invalidateMediaButtons (notification heart)
Session.state ─▶ SmartListsService.ensure
```

The player's offline sources are asked in order: downloads (`DownloadsLocalSource`), local files (`LocalFilesSource`,
never falls back to YouTube), downloaded podcast episodes. ReplayGain of files and content Uris: `AppReplayGain`.

## How the UI gets services

Only through `AppGraph` properties (directly in small composables, or in ViewModels that read them). All are created
before any Activity exists.

| Property | Type |
|---|---|
| `session` / `account` | `spotify.Session` / `library.SpotifyAccount` |
| `matchService`, `youtube` | `youtube.MatchService`, `youtube.explode.YoutubeClient` |
| `lyrics`, `linkLookups` | `lyrics.LyricsService`, `links.LinkLookups` (for `LinkOpener.open`) |
| `podcasts`, `internetRadio` | `podcasts.Podcasts`, `internetradio.InternetRadio` (objects) |
| `localLibrary`, `openedFiles`, `localFiles` | `localfiles.LocalLibrary`, `OpenedFiles`, `LocalFileResolver` |
| `library`, `catalog`, `playlistActions` | `library.UserLibrary`, `CatalogSource`, `PlaylistActions` |
| `home`, `search`, `libraryBrowser`, `artists`, `collections` | `HomeRepository`, `SearchRepository`, `LibraryBrowser`, `ArtistRepository`, `CollectionDeps` (pass to `CollectionController`) |
| `playback`, `player` | `playback.PlaybackModule`, `playback.Player` |
| `blacklist`, `radio`, `sponsorBlock`, `smartShuffle`, `sleepTimer` | playback features |
| `playerControls`, `enhancer` | `library.PlayerControls`, `library.CollectionEnhancer` |
| `stats`, `statsArtwork`, `smartLists`, `recommender` | `stats.ListenStatsService`, `StatsArtwork`, `SmartListsService`, `SmartMixRecommender` |
| `scrobbler` | `scrobble.Scrobbler` |
| `network`, `downloads`, `sync` | `downloads.NetworkMonitor`, `DownloadManager`, `DownloadSync` |
| `transfer`, `updater` | `playlistio.PlaylistTransfer`, `update.Updater` |
| `pendingOpen` | `StateFlow<app.PendingOpen?>` — files / playlist opened from another app; `consumePendingOpen()` |

Functions: `applyLabels(context)` (localized backend labels from strings.xml), `openUris(uris, mime)` (VIEW / SEND),
`flush(reason)`.

Spotify login: `registerForActivityResult(SpotifyLoginContract())`, `launch(Unit)`; the session state follows.

## Threading

- Main thread: `Player` and all playback features (confined), `CollectionController`, the event wiring
  (`Dispatchers.Main.immediate` scope in AppGraph), `ListenStatsService` sampling.
- `appScope` (process-wide, Default + supervisor) for services; network / disk inside them on `AppDispatchers.io` /
  `low`. Public suspend APIs are main-safe.
- WorkManager: `DownloadWorker` (foreground, dataSync) while downloads run, `SyncWorker`, `PodcastRefreshWorker`.
- Media3 `PlaybackService` mirrors the Player through `SessionPlayer`; bound by `PlaybackServiceConnector` before
  playback starts.

## Persistence

All in `Paths.appData` (= `filesDir`) unless noted: settings.json, library.json, session.json, blacklist.json,
listening.json (+ listening-imported.json), smart-lists.json, downloads.json, sync.json, podcasts.json, radio.json,
recent-searches.json, local-library.json, dropped-files.json, lyrics-offsets.json, spotify-hashes.json, spotify.dat / scrobble.dat
(Keystore-encrypted), logs/, crashes/. Caches in `appData/cache`: matches.json, images/, lyrics/, mb/, podcasts/,
stats-artwork.json, local-covers/, dropped-covers/. Downloads in the external app Music dir (`Paths.downloadsDir`). Update APKs and shared files (`cache/shared`, served by the `${applicationId}.fileprovider` FileProvider: the
Year-in-review story image) in the OS cache dir.

Flush points: `ProcessLifecycleOwner` ON_STOP and `PlaybackService.onDestroy` (`PlaybackModule.onServiceDestroyed`)
call `AppGraph.flush`: stats and session.json synchronously, then settings, library, downloads, sync, podcasts, radio
and the match cache on IO. Most stores also save themselves (debounced) after changes.

## Known gaps / to verify on a device

- **MP3 downloads** (320/256/192 kbps, default 320) use jump3r, a pure-Java LAME port (no NDK): MediaCodec decode →
  sample-accurate SponsorBlock cuts with 8 ms raised-cosine fades (C++ Splicer port) → LAME CBR → ID3v2.3 (APIC, USLT,
  SYLT, TXXX ReplayGain). ~7.5× real time on the x86_64 emulator; slower on low-end ARM phones. "Original" (0) keeps
  the AAC stream as tagged m4a; a WebM-only source saved as original gets no tags. `settingsVersion` 2 migrates the
  pre-MP3 forced 0 to 320.
- **Crossfade** is an approximation on two ExoPlayer decks (equal-power volume ramps); when a mix cannot start in time
  it degrades to a short gap. Time-stretch uses ExoPlayer's Sonic (`PlaybackParameters`), not the C++ WSOLA.
- **Voice search**: `MEDIA_PLAY_FROM_SEARCH` (`playback/service/PlayFromSearchActivity`) and the media session search
  (Assistant / Android Auto, `VoiceSession`) — the session path is untested (needs a real Assistant / Auto head unit).
- **Widgets** (Glance 4×1 / 4×2, `widget/`): registered and built; rendering on a launcher still to be checked by eye.
- **Spotify session start** retries the token with backoff on transient failures (no network yet after an update /
  boot, DNS, 5xx, 429) instead of logging out like the desktop; only a rejected cookie logs out.
- `LocalLibrary.cancel()` (C++ app exit) has no Android equivalent hook; a running scan just dies with the process.
- History (`UserLibrary.recordPlay`) skips radio stations and podcast episodes (they keep their own recents).
- **Source picker preview** ("Önizle"): a separate short-lived ExoPlayer plays 30 s of a candidate (AAC) and pauses /
  resumes the main player.
- **Large screens**: navigation rail ≥ 840dp, two-column Now Playing, width-capped content; no list-detail panes.
- **UI language below Android 13** (API 28–32) goes through `core/AppLocale` (configuration override in
  `ShadeTubeApp`/`MainActivity.attachBaseContext`, application resources updated + activity recreated on a change);
  33+ uses `LocaleManager`. Only the 33+ path was exercised on the emulator (API 36).
- Emulator QA (API 36, Spotify logged in): playback (YouTube match, seek, next/prev, shuffle / smart shuffle, repeat,
  SponsorBlock), lyrics + karaoke, queue reorder/remove/undo, radio (ICY live titles), podcasts, story share
  (FileProvider), settings, light theme and landscape were checked. Still untested on a real device: gapless /
  crossfade / DSP by ear (the AVD has no audio output), the media notification / lock screen / Bluetooth buttons /
  Android Auto, SAF / MediaStore scanning and content-Uri ReplayGain, VIEW / SEND intents, download worker
  notifications, the self-updater's PackageInstaller flow, WorkManager scheduling.
