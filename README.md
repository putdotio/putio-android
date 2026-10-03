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
- Files: browse, paginate, sort per folder, rename, move, trash, delete, copy
  items shared with you, and watched progress on media rows
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
- Transfer intake: tapped magnet links, opened or shared `.torrent` files, shared
  text and several pasted links open the Add transfer sheet, which saves to the
  default download folder or one picked with the move picker
- Share out: Share file exports the original through a scoped content URI; the
  chooser never sees a token. Product deep links open Files, a folder,
  Transfers, Search, History, Trash and Downloads

TV:

- Device-code sign-in (put.io/link) with the token in Keystore-backed storage,
  restored on relaunch, and an M3 navigation drawer with Files, Search,
  History and Account
- Files: browse folders with Refresh, Sort, paging, watched progress and the
  unsupported-type screen. Long-press or Menu on a row offers Open in VLC,
  Mark as watched or unwatched, and Move to trash or Delete permanently per
  the account's Trash setting. Center on a media row plays it full-screen
  through Media3 (HLS or MP4 per the account's playback type) with a
  play/pause overlay, a D-pad seek bar (Left, Right, rewind and
  fast-forward scrub), a resume prompt, Language, Subtitles and Speed
  pickers, and autoplay next; subtitles start from the account's settings.
  Back dismisses a picker, then seek mode, then the controls, then returns to
  the row. The player publishes a media session for system media controls and
  remote media keys
- Search through the system IME, with recent-query chips and paged results;
  a video or audio result plays, anything else opens in Files
- History grouped under relative-date headers; an event opens its file as a
  Search result does, and Clear confirms first
- Account: identity and quota header, Playback and Storage settings saved
  through the shared settings controllers, App and device information with a
  Diagnostics dialog, and Sign out
- Trash from Account: Restore or Delete permanently per row, Restore all and
  Empty trash, each confirmed and checked until put.io confirms it

Not started: Chromecast, Picture-in-Picture, and the Play release lane.

## Direction

- Compose-first app with `mobile` and `tv` flavors over shared `domain/*`
  library modules, one per product domain
- [`putio-sdk-kotlin`](https://github.com/putdotio/putio-sdk-kotlin) is the
  API boundary, consumed from Maven Central as `io.put:putio-sdk-kotlin`
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
./gradlew :mobile:assembleProductionDebug
./gradlew :tv:assembleProductionDebug
./scripts/prove.sh mobile && ./scripts/prove.sh tv   # emulator launch proof with evidence
```

Building against an unreleased SDK checkout:
[Kotlin SDK](./AGENTS.md#kotlin-sdk). What the gate covers:
[Build and verify](./AGENTS.md#build-and-verify).

## Docs

- [Agent guide](./AGENTS.md): toolchain, build and verify, CI, and the
  definition of done
- [Harness](./docs/harness.md): emulator lifecycle, recording, proof lanes,
  live API proof
- [Design system binding](./design/README.md): tokens and icon generation

## License

MIT, see [LICENSE](./LICENSE).
