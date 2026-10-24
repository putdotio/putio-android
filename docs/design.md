---
version: "1.0.0"
name: "put.io on Android"
description: "Design contract for the Android phone and tablet app: Material 3 Expressive on the put.io token graph. Moved here from putio-design platforms/android."
tier: 2
platforms: ["Android", "Android TV"]
source: "Material 3 Expressive (Compose Material3 1.5) + @putdotio/design token graph"
reviewed:
  date: "2026-10-24"
  against: "putio-android 3.0.0 (adaba1b) on a Pixel emulator, API 37"
canvas:
  phone: "412x915dp"
  tablet: "1280x800dp"
  tv: "1920x1080px"
mode: "dark only"
---

# put.io on Android

This is the target design for phones and tablets, adopted on 2026-10-24.
[Shipped drift](#shipped-drift) lists what 3.0.0 still does differently.
Tokens, voice and the binding tiers come from
[putio-design](https://github.com/putdotio/putio-design/blob/main/DESIGN.md);
this file owns how Android applies them.

## Binding

Tier 2: put.io tokens on stock Material 3 Expressive components
(`androidx.compose.material3` 1.5). Build a component from Material and give it
put.io colour, type and icons; draw a custom one only where this file says so.

The app generates one `darkColorScheme(...)` at build time from the vendored
`dist/tokens.dtcg.json` ([design/README.md](../design/README.md)) and applies it
as the scheme. No dynamic colour. Material owns pressed, disabled, selected and
focused states per component. Colours with no M3 role, such as green, come from
the generated `@color/putio_*` tokens, never from hand-written values.

| M3 role                   | put.io token              |
| ------------------------- | ------------------------- |
| `primary`                 | `--yellow-solid`          |
| `onPrimary`               | `--primary-foreground`    |
| `primaryContainer`        | `--yellow-solid`          |
| `onPrimaryContainer`      | `--primary-foreground`    |
| `secondary`               | `--text`                  |
| `secondaryContainer`      | `--yellow-solid` at 26%   |
| `onSecondaryContainer`    | `--yellow-solid`          |
| `background`, `surface`   | `--app-bg`                |
| `onBackground`            | `--text`                  |
| `onSurface`               | `--text`                  |
| `onSurfaceVariant`        | `--text-secondary`        |
| `surfaceContainerLow`     | `--component-bg`          |
| `surfaceContainer`        | `--component-bg`          |
| `surfaceContainerHigh`    | `--component-bg-hover`    |
| `surfaceContainerHighest` | `--component-bg-active`   |
| `inverseSurface`          | `--component-bg-active`   |
| `inverseOnSurface`        | `--text`                  |
| `inversePrimary`          | `--yellow-text-secondary` |
| `outline`                 | `--border`                |
| `outlineVariant`          | `--line`                  |
| `error`                   | `--red-solid`             |

## Colour

Neutrals do the work. Yellow marks content and the one primary action of a
screen or dialog:

- Content yellow: file-type icons, progress, the on-device marker, state dots,
  the selected nav item, selection checkboxes. These can share a row.
- Action yellow: the extended FAB, a dialog's confirm, a filled primary button.
  One per screen or dialog.
- Neutral: switches, the player's transport buttons, status icons such as the
  played eye, every other button.
- Red: destructive actions and failures only. Green: seeding and completed
  transfer dots, and the seeding card's progress fill.

## Type

GT America for UI; Berkeley Mono for sizes, dates, times, speeds, counts and
version numbers, with tabular figures. Filenames stay in GT America: they wrap
to two lines and mono would cost a third. Both fonts need an app-embedding
licence check before they ship; until then the app keeps Roboto.

| Use                     | Size / line | Weight                |
| ----------------------- | ----------- | --------------------- |
| Tab title               | 28 / 36     | Medium                |
| Top app bar title       | 22 / 28     | Medium                |
| Row headline            | 16 / 22     | Regular               |
| Settings row            | 15 / 20     | Regular               |
| Row meta, section label | 13 / 18     | Regular, label Medium |
| Nav label               | 12 / 16     | Medium                |

## Navigation

Five destinations: **Files, History, Transfers, Downloads, Account**, in that
order. `NavigationSuiteScaffold` with an explicit layout type: a navigation bar
below 600dp width and a navigation rail from 600dp, following the window
([Multi-window](behavior.md#multi-window)). When five destinations don't fit,
in short windows or at large font scales, a scrollable drawer takes over, as
today. The selected item uses the stock indicator in `secondaryContainer` with
a filled Phosphor glyph; unselected items use the regular glyph.

From 840dp, Files is list-detail: the folder list on the left (520dp), the
selected file's detail on the right with its preview, primary actions and
facts (size, added, length, subtitles, folder).

Each tab keeps its own back stack. Back pops within the tab, then returns to
Files, then leaves the app. Predictive back is on for every route; never
intercept back at the root.

## App bars

- **Files root:** an M3 search app bar: a 56dp pill reading "Search your
  files", with sort and overflow icon buttons inside it. Nothing permanent sits
  between it and the list.
- **Folders:** a small top app bar: back, the folder name, search in this
  folder, sort, overflow. A section line under it gives the item count and the
  total size in mono.
- **History, Transfers, Downloads, Account:** a 28sp title with an optional
  one-line summary below it (for example "2 active · 2 failed · 56.2 MB/s"),
  and the overflow at the top right.
- Icon buttons are 48dp targets around 24dp glyphs, with no gap between
  neighbours.

## Search

Tapping the search pill opens `ExpandedFullScreenSearchBar`: back arrow, query,
clear. Type chips (All, Folders, Videos, Audio, Subtitles) sit under the field.
Results group into Folders and Files, each with a mono count; a file's meta
names its parent folder. Recent searches show while the query is empty
([Recent searches](behavior.md#recent-searches)).

## File rows

`ListItem` with two slots, headline and meta, and no dividers. A wrapped name
makes three text lines:

- Leading: a 26dp yellow Phosphor fill glyph for the file type.
- Headline: the filename exactly as stored, wrapping to two lines.
- Meta: size in mono, `·`, date. Then, only when true: a grey outline eye when
  the video or audio was played (one glyph, started or finished alike), a
  yellow arrow-circle-down when the file is on this device.
- Trailing: a chevron for folders, an overflow button for files.
- No progress bars or percentages in file rows.

Tap opens the item ([Timestamps and History opens](behavior.md#timestamps-and-history-opens)).
Long-press opens the same actions sheet as the overflow button. Downloads is
the exception: long-press starts selection with that row selected
([Downloads and offline playback](behavior.md#downloads-and-offline-playback)).

## Item actions

A `ModalBottomSheet` titled with the file's icon, name and meta. The three
common actions are 72dp tiles (Play or Resume, Download, Share); the rest are
56dp icon rows: Select, Mark as watched, Rename, Move, Exclusive link. The
delete action comes last, in red, after a divider: Move to trash, or Delete
permanently when the account turns Trash off
([Files delete and paging](behavior.md#files-delete-and-paging)). The sheet
shows only what the item allows: Play and Download follow the file type, and
shared items drop owner actions and Exclusive link for Make a copy
([Shared-with-me items](behavior.md#shared-with-me-items)).

**Select** starts selection mode: the top bar turns `surfaceContainerHigh` with
close, "_n_ selected", select all and overflow. Checkboxes replace the file
icons and selected rows take a 9% yellow tint. A floating toolbar above the nav
bar holds Move, Download, Watched and the delete action. Each action shows only
when every selected item allows it, under the same rules as the sheet; delete
reads Delete permanently, and confirms, when the account turns Trash off.

## Dialogs and pickers

- **Rename:** an `AlertDialog` with one outlined field. The name is selected up
  to the extension, so typing keeps `.mkv`.
- **Destructive confirm:** the title names the count and the action ("Move 3
  items to trash?"); the body names the consequence ("You can restore them from
  Trash for 14 days."); the confirm is a red filled button. Permanent deletion
  always confirms; the target also confirms moves to Trash.
- **Choices** (sort, proxy, video playback): a dialog of radio rows.
- **Move:** a full-screen picker: close, "Move _n_ items", breadcrumb chips,
  folder rows with chevrons, and a bottom bar with New folder (text) and a
  filled confirm that names the target ("Move to Series").

## Transfers

A tab title with a live summary, then sections: Active, Needs attention,
Completed. Each transfer is a 16dp-radius card:

- The card background fills left to right with progress, as on the web:
  yellow at 13% while downloading, green at 12% while seeding.
- A state dot (yellow, grey queued, red failed, green done), then mono meta:
  "847 MB of 3.42 GB · 34 peers".
- Trailing: ETA and speed in mono while running; Retry on failures; Open on
  completed ones.
- Failed cards take a 9% red tint and a 1dp red outline.

**Add transfer** is a yellow extended FAB that collapses to its icon on scroll.
It opens a sheet: one field for links and magnets, Paste and .torrent chips, the
destination with Change, then Cancel and Add.

## Downloads

A tab title with space used and free. Sections, each shown only when it has
rows:

- Downloading: running, queued and paused downloads, with a thin progress bar,
  speed or the waiting reason, and cancel.
- Needs attention: failed and missing downloads, with Retry or Download again.
- On this device: the normal file row.

Actions here act on the local copy only: Remove from this device, with a
confirm. They never trash or delete the file on put.io
([Downloads and offline playback](behavior.md#downloads-and-offline-playback)).

## History

A tab title, date headers (Today, Yesterday, then dates), neutral event glyphs,
and the event with its time in mono. Clear history lives in the overflow.

## Account

A tab title, then the identity line (40dp avatar, name, email) and a storage
card ("Storage", used of total in mono, a 4dp bar). Settings follow as
segmented groups: 2dp gaps, 16dp outer and 4dp inner corners, under 13sp
labels. Every row is one line, 56dp tall, with a leading glyph, the title and a
trailing value or switch. The whole row toggles.

Groups, in order: Library (Exclusive links, Trash), Files, Playback, Subtitles,
Downloads, Privacy, About, then Sign out alone, in red.

**Switch:** neutral and compact, 44x26dp. Off: a 2dp `--solid` outline with a
12dp handle. On: a `--text` track with an 18dp `--app-bg` handle. This is a
deliberate step off stock M3.

## Player

Video plays immersive full screen; orientation follows
[Mobile video player](behavior.md#mobile-video-player): a landscape video turns
a phone to landscape, a portrait video keeps the window's orientation, and
tablets and multi-window use the window they have. Controls fade in on tap over
a top and bottom scrim:

- Top: back, the filename, cast, picture-in-picture.
- Centre: a 72dp translucent dark circle for play and pause, flanked by 56dp
  circles for 10 seconds back and forward.
- Bottom: elapsed and remaining in mono, a yellow scrubber, then Audio,
  Subtitles and Speed chips, and Next episode on the right.

Audio, Subtitles and Speed open a 340dp side sheet from the right with the
video dimmed behind:

- Audio: tracks by language, then channels and codec.
- Subtitles: Off, then tracks in the file, then subtitles put.io found.
- Speed: the value large in mono, presets 0.75 to 2, and a slider from 0.25×
  to 3×.

Audio keeps playing while you browse: a mini player card above the nav bar
(glyph, filename, mono time, pause, close, a 2dp progress line). Tapping it
opens Now Playing: a large type glyph, the filename, a scrubber with mono
times, a 88dp neutral pause between skip and ±15/30-second buttons, then speed,
sleep timer and Up next chips.

## Empty and error states

The [put.io empty-state voice](https://github.com/putdotio/putio-design/blob/main/system/preview/12-empty-state.html):
a mono glyph, a one-line headline, one sentence, at most one action. No artwork.

| State        | Glyph        | Headline              | Action                            |
| ------------ | ------------ | --------------------- | --------------------------------- |
| Empty folder | `[ ]`        | Empty folder.         | none                              |
| No results   | `¯\_(ツ)_/¯` | Nothing matches.      | none                              |
| No transfers | `( ⌐■_■)`    | Nothing in the queue. | Add transfer, in place of the FAB |
| Offline      | `(x_x)`      | Can't reach put.io.   | Try now                           |

Offline keeps what works: a banner above the list ("Offline. Showing what's on
this device.") and the on-device files stay usable.

## Motion

Material motion through `MaterialTheme.motionScheme` springs:

- Between tabs: fade through.
- Folder push and pop: shared axis X.
- File row to player: container transform.
- Sheets and dialogs: Material's own enter and exit.
- Predictive back previews every pop. Reduced motion turns transitions into
  fades.

## Metrics

| Element        | Metric                                                  |
| -------------- | ------------------------------------------------------- |
| Navigation bar | 80dp; rail 96dp from 600dp                              |
| Search app bar | 56dp pill, 16dp side margin                             |
| Small top bar  | 64dp                                                    |
| File row       | 64–88dp; 40dp leading slot; 16dp start padding          |
| Settings row   | 56dp, one line                                          |
| Extended FAB   | 56dp, 16dp corner, 16dp from the edge above the nav bar |
| Bottom sheet   | 28dp top corners, 32x4dp handle                         |
| Dialog         | 28dp corner, 24dp padding, `surfaceContainerHigh`       |
| Transfer card  | 16dp corner, 8dp gap                                    |
| Switch         | 44x26dp                                                 |
| Progress       | 4dp, 2dp radius                                         |

## Do not translate iOS

| iOS has                 | Android uses                                           |
| ----------------------- | ------------------------------------------------------ |
| Inset-grouped list      | Segmented list groups under small labels               |
| Action sheet            | `ModalBottomSheet` for item actions                    |
| Long-press context menu | Long-press opens the actions sheet (Downloads selects) |
| Toolbar plus            | Extended FAB                                           |
| Text back-label         | System back gesture and a top-bar arrow                |
| `UISwitch`              | The compact neutral switch above                       |
| Stepper                 | Slider, presets or a menu                              |

## Don't

- Put permanent controls (chips, buttons) between the Files app bar and its
  list. Expanded search's type chips are the exception.
- Use yellow for a second action on the same screen or dialog.
- Show percentages or progress bars in file rows.
- Use web recipes (`components.css` `.switch`, `.modal`, `.sheet`).
- Hand-write colours; generate them from the token graph.

## Welcome

The signed-out screen is shared with iOS and drawn in
[`mobile-s00-welcome`](https://github.com/putdotio/putio-design/blob/main/system/preview/mobile-s00-welcome.html):
the retro wordmark centred in the top bar, `┌( ಠ‿ಠ)┘` over **Welcome!**, one
lede line, and one full-width 56dp **Sign in** button that opens put.io OAuth in
the browser. Session expired reads **Welcome back!**; failures show a notice
with **Try again** or **Reset and sign in**.

## Shipped drift

3.0.0 still differs from this contract:

- Four tabs: Files, Search (with History inside), Transfers, Account. Downloads
  sits under Account.
- Search is a tab with an outlined text field; Files has no search bar.
- Add transfer is a filled button in a fixed row above the list, not a FAB.
- Transfers is one list with dividers and progress bars, not sectioned cards
  that fill with progress.
- The item actions sheet has rows only, no action tiles; moving to Trash does
  not confirm.
- Green has no generated `@color/putio_*` token yet.
- File lists draw dividers between rows.
- Rename does not preselect the name up to the extension.
- Empty states are text only, with no glyph.
- Folder and player Back use plain `BackHandler`, with no predictive preview.
- Search has no type chips; results are one flat list.
- The actions sheet has no Play or Resume and no Mark as watched.
- The Move picker cannot create a folder.
- Watched files show a progress bar and "N% watched".
- Switches are stock M3 52x32dp in yellow.
- Settings are two-line rows under yellow subheads.
- Type is Roboto. Only the player's timestamps use a monospace face, the system
  one.
- Navigation crossfades; no shared-axis or container transforms.
- The mini player (`MobileNowPlayingBar`) and Downloads selection exist in other
  forms; Files has no selection mode, and there is no offline banner or tablet
  list-detail.
- Player options open as bottom sheets; speed has presets from 0.75× to 2× and
  no slider. There is no cast button, sleep timer or Up next.
- The production launcher icon is a placeholder; nightly uses its generated
  stars icon.
- The player has no picture-in-picture or Next episode button; picture-in-
  picture starts when you leave playback.
- History has no event glyphs and keeps Clear history above the list.

## Android TV

The shipped Android TV app is `putio-tv-native-legacy`: the React Native app
shared with Apple TV, with its own components and `@putdotio/colors`. This
repository's `tv` module replaces it; its design pass comes next and lands in
this file.
