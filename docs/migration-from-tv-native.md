# Migration From React Native TV

## Source

Behavior reference app: `../putio-web/apps/tv-native`

The phase-one behavior and visual reference is the TV app packet in
`../putio-frontend/docs/specs/tv-app/`, especially the Android TV screenshots.
Boot the React Native app only when a missing state, ambiguous behavior, or
suspected drift needs a fresh capture. It is not the source for tokens, icons,
Compose structure, or Android architecture.

The new Android app should not copy the React component structure; it should preserve the product behavior while using Android-native UI, lifecycle, storage, playback, and input patterns.

## Available Inputs

- `../putio-web/apps/tv-native` for source-level behavior, focus handling, routes, hooks, and API usage
- `../putio-frontend/docs/specs/tv-app/android-tv/` for the exported Android TV parity screenshots
- `../putio-frontend/docs/specs/tv-app/tvos/` for supporting cross-platform comparison when behavior overlaps
- `../putio-frontend/docs/specs/tv-app/current-baseline.md` for behavior notes, screen names, player control flow, and capture method
- `../putio-sdk-kotlin` for typed API access and app-driven SDK tuning
- `../putio-design/dist/tokens.dtcg.json` for visual roles, vendored here as `design/tokens.dtcg.json`
- Phosphor for icons, vendored through `scripts/generate-icons.sh`
- The React Native TV app can still be booted for fresh captures when an exported state is missing, stale, or ambiguous

## Working Decisions

- Ship Android mobile first, then build Android TV parity from the exported reference bundle
- Treat focus polish as expected implementation work, not an architectural blocker
- Generate Compose colors and component roles from the vendored `@putdotio/design` graph; use Phosphor icons
- Treat `tv-native` as the behavior oracle only; Compose for TV and Android platform conventions own the UI structure
- Fine-tune `putio-sdk-kotlin` as real app slices expose gaps; do not bypass it with app-local HTTP unless the SDK gap is documented first
- Move libVLC/original-file playback to a later upgrade phase after the native app matches the current Android TV behavior
- Target one Play identity, `io.put.putio`, for both release form factors; the
  current mobile development flavor keeps its `.mobile` suffix until release
  identity work, and the first TV replacement build must use a versionCode
  above 91
- Use dedicated public mobile OAuth client `9677` with exact callback `putio://auth` through AndroidX Auth Tab

## Current Reference Surface

- Auth: activation code flow through `put.io/link`, token persistence, token validation, logout
- Navigation: authenticated stack with Home, Files, Search, History, Account, Trash, Tunnel, Diagnostics, and Dev screens
- Files: root folder `0`, nested folders, file sorting, refresh, long-press actions, video entry
- Search: keyword search, local search history, result list, clear/delete history
- History: grouped history list, clear history, navigate to file
- Trash: list, restore, restore all, delete, empty
- Settings: proxy route, subtitle preferences, playback type, buffer size, history, trash, app/device info
- Playback: HLS or MP4 source selection, resume prompt, start-from sync, subtitles, audio tracks, playback speed, buffering state, Sentry playback errors
- Input: D-pad focus, preferred focus, long-select and right-press shortcuts, back behavior
- Distribution: current Android TV package uses `io.put.putio`, Android TV launcher metadata, Play beta lane, S3 APK upload lane

## Target Android Shape

- One Kotlin/Compose repo with shared domain, data, design, and app shell primitives
- `mobile` and `tv` flavors so Android mobile can grow without forking the codebase
- TV UI uses Compose for TV components, `FocusRequester`, stable keys, virtualized `LazyColumn` lists, and launcher/channel integrations
- API access goes through `putio-sdk-kotlin`; every missing endpoint or awkward app call should first become an SDK issue or patch
- Token persistence belongs in Android secure storage, with the SDK receiving tokens rather than owning platform storage
- Phase-one playback should match the exported Android TV behavior: HLS/MP4 source selection, custom controls, resume prompt, start-from sync, subtitles, audio tracks, playback speed, seekbar scrubbing, buffering state, and player errors. libVLC original-file playback comes later after parity is real

## SDK Work To Drive From The App

- Prove `putio-sdk-kotlin` works as an Android dependency, not only as a JVM package
- Add app-friendly auth orchestration around OOB code polling, timeout, and validation
- Add paging helpers or sample adapters for files, search, history, and trash
- Add playback-source helpers that expose original, HLS, MP4, subtitles, start-from, and next-file decisions without leaking URL construction into UI code
- Tighten account settings naming around `use_start_from` vs `start_from` before the app bakes in either assumption
- Add Android live proof for file playback metadata, subtitles, start-from, and routes before claiming feature parity

## Rollout Order

1. Shared app shell, SDK composite build, CI, emulator proof, design tokens, and icons
2. Mobile Auth Tab, adaptive navigation, Files, Search/History, Transfers, and Account/Settings
3. Mobile playback, downloads, share/open-in, Chromecast, and release proof
4. TV shell and device-code auth on the shared core
5. TV browse, playback, settings, trash, and history parity against the exported packet
6. TV/Fire TV release proof on the existing listing and package identity

## Recovered Gap Notes

The retired `feature/android-tv-full-parity` branch was never compiled or
device-proven. Its implementation is discarded, but its draft audit identified
useful states to verify against the canonical 34-capture packet:

- vector logo and activation-code QR variants
- conversion-in-progress UI
- diagnostics bandwidth and buffer telemetry
- VLC handoff on Fire TV
- Search settings behavior
- watched/progress treatment in Files
- diagnostics test-stream source parity

These are planning notes, not evidence that the retired branch implemented the
behavior correctly.

## Open Decisions

- Which owner-controlled signing lineage and custody path will replace the React Native app on the existing Play listing
- Which physical Android TV and Fire TV devices are the required remote/back-button/playback proof set
- Later playback phase only: whether libVLC is the only target player or whether Media3/ExoPlayer remains a first-class fallback
