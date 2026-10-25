# putio-android

> **Work in progress.** A ground-up rewrite of the put.io Android app, not on
> Google Play yet; expect missing features and breaking changes between
> commits. The put.io TV app on Play stays until this app replaces it;
> [#14](https://github.com/putdotio/putio-android/issues/14) tracks the
> rollout. Bug reports are welcome; open an issue before sending a pull request.

Native Android app for put.io, built with Kotlin and Jetpack Compose for
phones, tablets, Android TV, and Fire TV from one codebase.

## What works today

Mobile, on the emulator harness against the live API:

- OAuth sign-in through an Auth Tab, with the session in Android secure storage
- put.io's type: GT America for text, Berkeley Mono for numbers
- Five tabs (Files, History, Transfers, Downloads, Account) on a navigation bar,
  a rail from 600 dp, or a drawer at large font sizes; each tab keeps its own
  back stack, and Back previews every pop
- Files: browse, sort per folder, rename, move, copy items shared with you,
  create folders, trash with Undo, delete permanently, search inside a folder,
  played marks, and Continue watching for videos started on this device
- One actions sheet per item: Play or Resume, Download, Share, Open in VLC,
  Mark as watched, Rename, Move, Exclusive link and delete
- Tablets from 840 dp: the list beside the selected file's preview and facts
- Images, PDFs and text in an in-app viewer; other types open with another app
- Trash: days left per item, restore one or all, delete permanently, empty
- Transfers in Active, Needs attention and Completed sections, with actions to
  open the save folder, copy the source link, stop, retry, open or remove
- Search with type chips, results grouped into Folders and Files, and recent
  searches
- History grouped by day, with Clear history behind a confirm
- Video playback over HLS or MP4: subtitles with size and timing, audio tracks,
  speed from 0.5× to 3×, 10-second seek, Next episode and autoplay next
- Audio in the background with system media controls, a mini player on every
  tab, and Now Playing with skip, speed, a sleep timer and Up next
- Picture-in-picture from the player or by leaving the app mid-video
- Resume or start over from the saved position, with positions saved back to
  put.io while video or audio plays
- Account: storage, settings saved to the account and shared across devices,
  Privacy controls, App info, and Sign out that can remove this account's
  downloads
- Downloads: video saves the HLS rendition it streams, audio the original.
  Pause, resume, a 1–4 concurrency limit, retry, notifications with actions,
  and offline playback whose positions reach put.io once it answers
- Transfer intake from magnet links, `.torrent` files, shared text and pasted
  links, saved to the default folder or a picked one
- Share file exports the original without exposing a token; product links open
  Files, a folder, Transfers, Search, History, Trash and Downloads
- Exclusive access: create, copy, share and revoke public links; Account lists
  them all
- Launcher shortcuts (Add transfer, Search files, Downloads) and a launcher icon
  per channel
- Proven only on the emulator with faked data so far: a Transfers home-screen
  widget, keyboard shortcuts and drag and drop on tablets, split screen, and
  put.io as a read-only location in the system file picker

TV:

- Device-code sign-in (put.io/link) with a QR code, restored on relaunch, and a
  navigation drawer with Files, Search, History and Account
- Files: browse, sort, refresh, watched progress, Open in VLC, Mark as watched,
  and trash or delete per the account's Trash setting
- Full-screen playback over HLS or MP4 with D-pad scrubbing, a resume prompt,
  Language, Subtitles and Speed pickers, autoplay next, and system media
  controls
- Search through the system keyboard, with recent queries and paged results
- History grouped by day; an event opens its file, and Clear confirms first
- Account: identity, quota, Playback and Storage settings, App and device
  information, Trash, and Sign out
- Videos in progress appear in the launcher's Watch Next row
- Android TV system search lists put.io files

Both apps report crashes and player failures to Sentry only while the
account's Diagnostics control is on, and send only exception types, stack
frames, device facts and failure codes.

## Build and verify

Requires JDK 21, Node 24, `python3`, and, on macOS, Homebrew.

```bash
./scripts/bootstrap.sh   # once per machine: Android SDK, AVDs, brand fonts, local.properties
./gradlew verify
./gradlew :mobile:assembleProductionDebug :tv:assembleProductionDebug
./scripts/prove.sh mobile && ./scripts/prove.sh tv   # emulator launch proof with evidence
```

The [Agent guide](./AGENTS.md#build-and-verify) covers what the gate runs and
[building against an unreleased SDK](./AGENTS.md#kotlin-sdk).

## Docs

- [Design](./DESIGN.md): the Android design contract
- [Design system binding](./design/README.md): tokens, brand fonts and icon
  generation
- [Agent guide](./AGENTS.md): module layout, hazards, proof map and CI

## Security

Report vulnerabilities through the
[put.io security policy](https://github.com/putdotio/.github/blob/main/SECURITY.md).

## License

MIT, see [LICENSE](./LICENSE).
