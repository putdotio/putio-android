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
- put.io's type: GT America for text, Berkeley Mono for the player's times
- Five tabs (Files, History, Transfers, Downloads, Account) on a navigation bar,
  a rail from 600 dp, or a drawer at large font sizes; each tab keeps its own
  back stack, Back previews every pop, and Search opens over Files
- Files: browse, paginate, sort per folder, rename, move, trash, delete, copy
  items shared with you, create folders, search inside a folder, a played mark
  on media rows, Continue watching for videos started on this device, and
  downloads listed while put.io is unreachable
- Trash: browse, restore one or all, delete permanently, empty
- Transfers in Active, Needs attention and Completed sections, each card
  filling with its progress; Transfer actions open the save folder, copy the
  source link, stop, retry, open the file or remove it from the list
- Search
- History under Today, Yesterday and date headers, one line per event with a
  neutral glyph and its time; Clear history sits in the tab's overflow and
  confirms first
- Video playback with subtitles, 10-second touch seek, autoplay next, and
  configurable HLS or MP4
- A video player with Audio, Subtitles and Speed chips that open side sheets,
  Next episode when the account's next-episode setting is on, and subtitle size
  (S to XL) and timing in 0.1 s steps; audio keeps its own layout
- Audio playback in the background with system media controls and a now-playing bar
- Picture-in-picture from the player's top bar or by leaving the app mid-video:
  the window keeps playing with 10-second back, Pause or Play and 10-second
  forward; Expand returns to the full-screen player, and closing it stops the
  video where it was
- Resume or start over from a saved position, with throttled position updates
  while video or background audio plays
- Video playback speed from 0.5× to 3× in eight presets (audio keeps 0.75× to
  2×) and audio-track selection; choices survive player recreation, and an
  unavailable track falls back to automatic selection
- Account settings: subtitles, history, Trash, resume playback, proxy route,
  and default sort order, each saved to the account and shared across devices
- A Privacy controls page under Account for diagnostics (on by default) and
  product analytics (opt-in), shared across put.io apps, with a
  strictly-necessary storage disclosure and the privacy policy links
- About section with copyable app, Android, device, and player info
- Downloads: video saves the HLS rendition it streams (without subtitles for
  accounts that hide them), audio the original. The Downloads tab shows
  storage used and free, unfinished rows with a progress ring that pauses,
  resumes, or cancels a queued row, each row's place in line, a 1–4
  concurrency limit, failed and missing rows with retry, and multi-select
  pause, resume and removal of local copies. Finished and failed downloads notify with Play or Try again, acting
  only for the account that downloaded them. Downloads play offline and resume
  where this device left off; offline positions reach put.io once it answers
- Transfer intake: tapped magnet links, opened, shared or picked `.torrent`
  files, shared text and several pasted links open the Add transfer sheet,
  which checks each line, adds the good ones, and saves to the default
  download folder or one picked with the move picker
- Share out: Share file exports the original through a scoped content URI; the
  chooser never sees a token. Product deep links open Files, a folder,
  Transfers, Search, History, Trash and Downloads
- Exclusive access: create, copy, share and revoke public links to your own
  files and folders from their action sheet; Account lists every link with
  revoke
- Launcher shortcuts: Search, Add transfer (an empty sheet; nothing is added
  until Add) and Downloads, each waiting for sign-in when signed out
- A Transfers home-screen widget, proven on the emulator with a faked account so
  far: active transfers with progress, refreshed every 30 minutes, on sign-in,
  from the Transfers screen and on demand; sign-out clears it at once
- Tablets and keyboards, proven on a tablet-size emulator with faked data so
  far: keyboard navigation and shortcuts in Files and the player, listed in the
  Keyboard Shortcuts Helper; files drag out to other apps, and links, magnet
  links and `.torrent` files drag in to Add transfer; split screen, freeform
  resizing and a keyboard arriving keep navigation and the playing video
- System file picker, proven on the emulator with a faked account so far: while
  signed in, put.io is a read-only location in other apps' pickers with paged
  folders and search; files stream from put.io, or open from a completed audio
  download, and sign-out revokes every grant

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
- A QR code beside the activation code opens put.io/link with the code filled in
- Videos in progress on this TV appear in the launcher's Watch Next row and
  continue from the saved position; finished videos, sign-out and deleted files
  remove them
- Android TV system search lists put.io files through a suggestions provider; a
  chosen result plays or opens in Files

Both apps report crashes and player failures to Sentry only while the
account's Diagnostics control is on, and send only exception types, stack
frames, device facts and failure codes ([Diagnostics](docs/behavior.md#diagnostics)).

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

- [Behaviour](./docs/behavior.md): product rules and the tests that pin them
- [Harness](./docs/harness.md): emulators, evidence, device lanes and live API
  proof
- [Distribution](./docs/distribution.md): Google Play delivery for both apps
- [Design](./DESIGN.md): the Android design contract
- [Design system binding](./design/README.md): tokens, brand fonts and icon
  generation
- [Agent guide](./AGENTS.md): module layout, hazards, proof map and CI

## Security

Report vulnerabilities through the
[put.io security policy](https://github.com/putdotio/.github/blob/main/SECURITY.md).

## License

MIT, see [LICENSE](./LICENSE).
