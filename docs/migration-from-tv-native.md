# Migration From React Native TV

## Source

Current reference app: `../putio-web/apps/tv-native`

The React Native app remains the behavioral reference for the first Android TV milestones. The new Android app should not copy the React component structure; it should preserve the product behavior while using Android-native UI, lifecycle, storage, playback, and input patterns.

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
- Playback should move toward libVLC original-file playback, with HLS/MP4 retained as fallback/proof paths until the libVLC contract is validated

## SDK Work To Drive From The App

- Prove `putio-sdk-kotlin` works as an Android dependency, not only as a JVM package
- Add app-friendly auth orchestration around OOB code polling, timeout, and validation
- Add paging helpers or sample adapters for files, search, history, and trash
- Add playback-source helpers that expose original, HLS, MP4, subtitles, start-from, and next-file decisions without leaking URL construction into UI code
- Tighten account settings naming around `use_start_from` vs `start_from` before the app bakes in either assumption
- Add Android live proof for file playback metadata, subtitles, start-from, and routes before claiming feature parity

## First Milestones

1. Buildable app shell with `mobile` and `tv` flavors, CI, and emulator/device smoke commands
2. SDK Android compatibility pass and local composite build against `../putio-sdk-kotlin`
3. TV auth slice: code request, polling, token storage, token validation, logout
4. TV file browser slice: root folder, nested folder navigation, D-pad focus, paging, sorting, refresh
5. TV playback proof: original-file libVLC prototype, HLS/MP4 fallback, subtitles, audio tracks, start-from updates
6. TV settings/trash/history parity after auth/files/player behavior is stable
7. Mobile shell pass: reuse data/domain/theme, add touch navigation and mobile-first layouts

## Open Decisions

- Whether `putio-android` should be public immediately or private until the first usable app shell lands
- Whether Android mobile should share the existing `io.put.putio` package identity or receive a separate package/listing
- Whether Android TV release keeps the current signing lineage and Play listing from the React Native app
- Whether libVLC is the only target player or whether Media3/ExoPlayer remains a first-class fallback
- Which CI runner owns Android emulator/device proof

