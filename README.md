# putio-android

> **Work in progress.** This is a ground-up rewrite of the put.io Android app
> and is not on Google Play yet. Expect missing features and breaking changes
> between commits. The Android TV app on Play today is the existing put.io
> TV app; it stays there until this app replaces it.
> [Epic #14](https://github.com/putdotio/putio-android/issues/14) tracks the
> rollout. Bug reports are welcome; open an issue before sending a pull request.

Native Android app for put.io, built with Kotlin and Jetpack Compose for
phones, tablets, Android TV, and Fire TV from one codebase. Mobile ships first;
the TV surface reuses the shared data, domain, theme, and component layers.

## What works today

Mobile, on the emulator harness against the live API:

- OAuth sign-in through an Auth Tab, with the session in Android secure storage
- Files: browse, paginate, sort per folder, rename, move, trash, delete, and
  watched progress on media rows
- Trash: browse, restore one or all, delete permanently, empty
- Transfers and search
- Video playback with subtitles, 10-second touch seek, autoplay next, and
  configurable HLS or MP4
- Separate audio/video player layouts with a compact timeline and native settings sheets
- Audio playback in the background with system media controls and a now-playing bar
- Resume or start over from a saved position, with throttled position updates
  while video or background audio plays
- Playback options for audio and video: 0.75×, 1×, 1.25×, 1.5×, and 2× speed,
  plus audio-track selection when multiple supported tracks are available.
  Choices survive player recreation; unavailable audio tracks fall back to automatic selection
- Account settings: subtitles, history, Trash, resume playback, proxy route,
  and default sort order, each saved to the account and shared across devices
- Privacy controls for the support chat widget, shared across put.io apps,
  with a strictly-necessary storage disclosure
- About section with copyable app, Android, device, and player info
- Downloads: video saves the same HLS rendition it streams, audio saves the
  original; a Downloads screen under Account lists local copies, storage used,
  retry and delete; completed downloads play without a connection
- Share out: Share file exports the original through a scoped content URI; the
  chooser never sees a token. Product deep links open Files, a folder,
  Transfers, Search, History, Trash and Downloads

The TV flavor signs in with a device code (put.io/link), keeps the token in
Keystore-backed storage, restores it on relaunch, and shows the M3 navigation
drawer with Files, Search, History and Account. Files browses folders with
Refresh, Sort, paging, watched progress and the unsupported-type screen;
media rows do not play yet. Search types through the system IME, replays
recent queries from chips, pages results in the standard rows, and opens a
result in Files. History lists the account's events under relative-date
headers, opens an event's file in Files, and clears with a confirmation.
Not started:
Chromecast, Picture-in-Picture, and the Play release lane.

## Direction

- Compose-first app with `mobile` and `tv` flavors
- [`putio-sdk-kotlin`](https://github.com/putdotio/putio-sdk-kotlin) is the
  API boundary, consumed as a Gradle composite build until it ships on Maven
  Central
- Mobile-first rollout with touch and tablet-adaptive navigation
- TV shell built around D-pad focus, Android TV launcher metadata, media
  sessions, and system search
- TV behavior matches the current put.io Android TV app; libVLC or
  original-file playback is a later, separately proven upgrade
- Colors and component roles come from
  [`@putdotio/design`](https://github.com/putdotio/putio-design); Phosphor is
  the icon source

## Build and verify

Requires JDK 21, `python3`, and, on macOS, Homebrew. Everything else is scripted.

```bash
./scripts/bootstrap.sh   # once per machine; installs the Android SDK, AVDs, local.properties
./gradlew verify
./gradlew :app:assembleMobileProductionDebug
./gradlew :app:assembleTvProductionDebug
./scripts/prove.sh mobile && ./scripts/prove.sh tv   # emulator launch proof with evidence
```

`verify` runs lint, detekt, the unit tests, the icon lock check, and the
harness script contract tests (needs `python3` and `ffprobe`), then assembles
an unsigned minified mobile release APK to prove the composite SDK survives
minSdk 26 and R8. The phone AVD runs the API 37 Google Play image and checks Chrome Auth Tab
readiness on every boot; Android TV and the scheduled CI device stay on API 36.

The `bootstrap` script expects `putio-sdk-kotlin` cloned as a sibling
directory, or a `putioSdkKotlinPath` in `local.properties`.

## Docs

- [Agent guide](./AGENTS.md): setup, emulator flows, evidence conventions, and
  the definition of done
- [Harness](./docs/harness.md): emulator lifecycle, recording, live API proof
- [Design system binding](./design/README.md): tokens and icon generation

## License

MIT, see [LICENSE](./LICENSE).
