# ShadeTube UI — rules for screen authors

The UI is Jetpack Compose (Material 3 behaviours, ShadeTube look). Several people/agents build screens in parallel, so
these rules keep them from colliding.

## Sources of truth
- Design handoff: `design/faz1/handoff/` — `README.md` (identity, navigation, Compose notes), `COMPONENTS.md` (every
  component with dp and states), `REDLINES.md` (hi-fi measurements), `SCREENS.md` (all 26 screens), `strings/strings.xml`.
  Rendered hi-fi screens: `design/faz1/shots/part0..2.png` (Home, Now Playing, Playlist detail, Mini player). Screens
  without hi-fi follow SCREENS.md using the same tokens and components. Phase 2 hi-fi designs will come later.
- Architecture / services: `docs/ARCHITECTURE.md`, `AppGraph.kt` (every service is a property there).
- Navigation contract: `ui/nav/Navigation.kt` (`Screen`, `Navigator`, `LocalNavigator`).

## Ownership (create/modify only what you own)
| Owner | UI packages | Backend packages it may extend (small, additive changes only) |
|---|---|---|
| Shell & player | `ui/shell`, `ui/nowplaying`, `ui/sheets`, `MainActivity.kt` | playback, lyrics, youtube |
| Browse | `ui/home`, `ui/search`, `ui/library`, `ui/collection`, `ui/artist` | library |
| Broadcast & flows | `ui/broadcast`, `ui/onboarding`, `ui/transfer` | podcasts, internetradio, playlistio |
| Profile & settings | `ui/profile`, `ui/stats`, `ui/settings`, `ui/downloads`, `ui/localfiles` | stats, scrobble, downloads, update, localfiles, core/Settings (add fields only) |

- `ui/theme`, `ui/components`, `ui/nav`, `AppGraph.kt`, `app/`, build files and `AndroidManifest.xml` are frozen for
  screen authors. Need a new shared component? Write it privately in your package (`ui/<pkg>/…`) and mention it in your
  report so it can be promoted later. Need a manifest entry / dependency / AppGraph property? Ask in your report.
- **Strings**: never edit `res/values/strings.xml` (shared). Put new texts in your own files
  `res/values/strings_<pkg>.xml` (Turkish) **and** `res/values-en/strings_<pkg>.xml` (English), keys prefixed with your
  package (`home_…`, `np_…`). Existing keys in `strings.xml` may be used. Plurals the same way (`plurals_<pkg>.xml`).
- **Drawables**: use the existing `ic_*` icons; new resources go in files prefixed with your package.

## Screen contract
- Every screen composable takes `contentPadding: PaddingValues` first (bottom: mini player + nav bar; top: nothing —
  each screen handles the status bar itself, edge-to-edge) — see the stub signatures in your package; keep them.
- Navigate only through `LocalNavigator.current` (`navigate(Screen)`, `open(Destination)`, `showTrackMenu`, `message`…).
- State: one `ViewModel` per screen (lifecycle-viewmodel-compose) reading `AppGraph` services; collect flows with
  `collectAsStateWithLifecycle`. No business logic in composables — if the backend lacks something, add it (additively)
  in a backend package you own, or ask.
- Use `ui/theme` (`ShadeTheme.accent / extras`, `ShadeTypography`, `ShadeMono`, `ShadeMotion`, `ShadeDimens`) and
  `ui/components` (TrackRow, CoverTile, Artwork, PrimaryButton, ShadeBottomSheet, EmptyState, skeletons, ShadeTextField,
  StepDots, ProfileAvatar…). Match the
  dp values in COMPONENTS.md / REDLINES.md.
- Lists: `LazyColumn` / `LazyRow` with stable keys; skeletons while loading; `EmptyState` / `ErrorState` with retry.
- Respect `ShadeTheme.reduceMotion`, 48dp touch targets, `contentDescription`s, 200 % font scale (maxLines + ellipsis).
- Text formatting: durations / counts / bitrates in `ShadeMono`; relative dates, counts and greetings via
  `library/Formatting.kt` data + plurals.
- Add `@Preview`s with fake data where cheap.
- Large windows (SCREENS #26): the shell provides `LocalWindowLayout` (`ui/components/Adaptive.kt`: width class
  Compact < 600 ≤ Medium < 840 ≤ Expanded, `useRail`, `twoPaneNowPlaying`, `compactHeight`). Expanded windows get the
  navigation rail (avatar on top, vertical mini player at the bottom) and two-column Now Playing; `ScreenContent`
  centers pages at `AdaptiveWidths` (Home / Library 1200, Search / Broadcast 960, detail pages 840). Grids size their
  columns with `gridColumns(maxWidth)` instead of a fixed 2. Never read `LocalConfiguration` for layout.
- Tab roots: hook the main list up with `ScrollToTopEffect(Tab.X, listState)` (`ui/nav/ScrollToTop.kt`) — re-tapping
  the selected tab at its root scrolls it to the top (deeper in the stack the tap pops to the root).

## Building your part
Other screens are being written at the same time; compile only your packages (screens you don't list are replaced by
the stubs in `tools/ui-stubs/`):
```bash
tools/isolated-build.sh <name> backend uibase ui/home ui/search          # your packages
tools/isolated-build.sh <name> backend uibase ui/home -- :app:assembleDebug   # also AAPT-check resources
```
Never run gradle in the main project while others are working.
