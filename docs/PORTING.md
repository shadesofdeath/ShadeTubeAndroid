# ShadeTube Android — porting guide

The Android app is a Kotlin port of the Windows app in `C:\Users\shades\Desktop\ShadeTube` (C++20, ~67k lines).
Its `docs/DEVELOPMENT.md` describes the architecture; this file says how each part maps to Android and which
conventions the Kotlin code follows. The UI (Jetpack Compose) is designed separately and is **not** part of the
backend port: backend packages contain no Compose code.

## Stack

| C++ | Android |
|---|---|
| WinHTTP / YoutubeExplode IHttpClient | OkHttp (`core/Http`) |
| nlohmann::json | kotlinx.serialization `JsonElement` + helpers in `core/Json.kt` (`j.str("k")`, `j.at("a","b",0)`, `jsonOf(...)`) |
| ThreadPool + Dispatcher + `st::async` + `Lifetime` | coroutines: `appScope`, `AppDispatchers.io/low/cpu/main`, Job cancellation |
| listeners + `subscribe(lifetime, fn)` | `StateFlow` (state) / `SharedFlow` (events) |
| `Settings::get()` + `markDirty()` | `SettingsStore.current` / `SettingsStore.update { it.copy(...) }` (auto-saved) |
| DPAPI files (spotify.dat, scrobble.dat) | `core/SecureStore` (Android Keystore AES-GCM), same file names |
| `%LOCALAPPDATA%\ShadeTube` | `Paths.appData` (files dir); `Paths.cacheDir` = appData/cache; `Paths.tempDir` = OS cache |
| write-then-rename JSON stores | `Files.writeJson / writeAtomic / readJson` |
| Media Foundation + WASAPI + own engine | Media3 ExoPlayer + `MediaLibraryService` (notification, lock screen, Bluetooth, Android Auto) |
| EQ / limiter / loudness DSP | Media3 `AudioProcessor`s (ports of audio/Equalizer, Limiter) |
| TimeStretch (WSOLA) | ExoPlayer `PlaybackParameters` (Sonic, pitch kept) |
| LiveStream / LiveParsers (ICY, HLS, AAC/Opus) | ExoPlayer progressive/HLS + ICY metadata (`IcyInfo`) |
| WebView2 login window | `android.webkit.WebView` activity + `CookieManager` |
| SMTC / tray / taskbar / jump list / mini player / Discord / global hotkeys / installer / autostart | dropped (MediaSession covers media controls) |
| Windows property system tags, local folder scan | MediaStore + SAF (`DocumentFile`), own ID3/MP4/FLAC readers where tags are needed |
| `I18n tr(L"...")` | UI layer (`strings.xml`); backend returns typed results / error enums, not user-facing text |
| background housekeeping tick | coroutines in `appScope`; WorkManager for work that must survive the process (sync, podcast refresh) |

## Package map (`com.app.shadetube.*`)

| Package | Ported from |
|---|---|
| `core` | core/* (Http, Json, Files, Paths, Log, Settings, SecureStore, AppScope, Util) |
| `catalog` | catalog/* (Models, ModelJson = the AppContext.cpp toJson/fromJson shapes) |
| `youtube.explode` | external/YoutubeExplode (videos, streams, cipher, search, playlists, music TrackMatcher) |
| `youtube` | youtube/MatchService, youtube/AltSource |
| `spotify` | spotify/* (Totp, Auth, HashRegistry, SpotifyApi, PlaylistTree, Session) + app/LoginWindow (WebView) |
| `musicbrainz` | musicbrainz/* (Net, Parse, MusicBrainz, ListenBrainz, Wikidata, Cover Art Archive) |
| `lyrics` | lyrics/* + app/LyricsService |
| `links` | app/Links (+ the non-UI part of app/LinkOpener) |
| `podcasts` | app/Podcasts (+ non-UI logic of PodcastsPage) |
| `internetradio` | app/InternetRadio (+ non-UI logic of RadioPage) |
| `playback` | player/Player, audio/* DSP, app/SponsorBlock, Blacklist, Radio, SmartShuffle, sleep timer, PlaybackService |
| `library` | AppContext `Library` (library.json), app/Source (Spotify / MusicBrainz seam) |
| `stats` | app/ListenStats, HistoryImport, SmartLists, SmartMix |
| `scrobble` | app/Scrobbler |
| `downloads` | app/Downloads, audio/Downloader, app/DownloadSync, app/SyncRules |
| `localfiles` | app/LocalLibrary (+ non-UI logic of LocalFilesPage) |
| `playlistio` | app/PlaylistIO, app/PlaylistTransfer |
| `update` | app/Updater (GitHub releases → APK) |

Dependency direction (never reverse): `core ← catalog ← {youtube, spotify, musicbrainz, lyrics, links, podcasts,
internetradio} ← {library, playback, stats, scrobble, downloads, localfiles, playlistio, update} ← AppGraph ← UI`.
When a lower package needs something from a sibling or a higher one, it declares a small interface (or function
type) in its own package and `AppGraph` wires the implementation in (e.g. `lyrics` declares
`SpotifyLyricsSource`; `AppGraph` passes the Spotify session's implementation).

## Conventions

- **Port faithfully.** Behavior, constants, limits, timeouts, retry / backoff rules, cache lifetimes, JSON file
  names and shapes, and the reasons behind them (keep the C++ comments that explain *why*, translated into the KDoc).
  Do not simplify away edge cases the C++ handles. Windows-only mechanisms get the closest Android equivalent.
- **Persisted files keep their C++ names and JSON shapes** (settings.json, library.json, matches.json,
  spotify-hashes.json, ...), so the logic and tests port 1:1 and data could be migrated.
- **Public APIs that touch network or disk are `suspend` and main-safe** (they switch to `AppDispatchers.io`
  themselves). Pure logic stays plain functions. Long-lived services expose `StateFlow`s.
- Use `Http.await(...)` (cancellable) or `Http.send(...)` inside `withContext(AppDispatchers.io)`. Never set
  `Accept-Encoding` (OkHttp decompresses transparently only when it adds the header itself).
- Errors: throw exceptions inside a module (IOException / HttpError / module-specific ones); at service boundaries
  return `Result` or sealed result types. Log with `Log.i/w/e("<module>", ...)`.
- No user-facing strings in the backend: return enums / sealed classes the UI maps to `strings.xml`.
- No Compose / UI code outside `ui`. Android components that are not UI (Service, BroadcastReceiver, Worker) live in
  their module; the only Activity the backend owns is the Spotify login WebView.
- Manifest entries, permissions and new dependencies are added centrally (AndroidManifest.xml, build files): list
  them in your report instead of editing those files.
- Unit tests (JUnit4, `app/src/test/java/com/app/shadetube/<package>/`) for pure logic: parsers, matching, planning
  rules, TOTP, link parsing… — port the C++ `tests/<module>` cases where they exist. No network in unit tests.

## Building a package on its own

Several packages are ported at the same time, so do not build the whole project while others are mid-change.
`tools/isolated-build.sh` copies `core`, `catalog`, the root files and the packages you name into a private copy of
the project and compiles that:

```bash
tools/isolated-build.sh spotify spotify                       # compile core + catalog + spotify
tools/isolated-build.sh playback playback youtube spotify     # a package plus the ones it uses
tools/isolated-build.sh lyrics lyrics -- :app:testDebugUnitTest --tests 'com.app.shadetube.lyrics.*'
```
