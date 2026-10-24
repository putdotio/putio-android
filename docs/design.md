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
as the only colour input. No dynamic colour. Material owns pressed, disabled,
selected and focused states: 8% and 12% `onSurface` layers.

| M3 role                   | put.io token              |
| ------------------------- | ------------------------- |
| `primary`                 | `--yellow-solid`          |
| `onPrimary`               | `--primary-foreground`    |
| `primaryContainer`        | `--yellow-solid`          |
| `onPrimaryContainer`      | `--primary-foreground`    |
| `secondary`               | `--text`                  |
| `secondaryContainer`      | `--yellow-solid` at 22%   |
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

Neutrals do the work. Yellow marks one thing per group:

- Yellow: file-type icons, the primary action of a screen (the extended FAB,
  a dialog's confirm), progress, the on-device marker, the selected nav item,
  selection checkboxes.
- Neutral: switches, the player's transport buttons, status icons such as the
  played eye, every other button.
- Red: destructive actions and failures only. Green: seeding and completed
  transfer dots.

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
order. `NavigationSuiteScaffold` shows a navigation bar below 600dp width and a
navigation rail from 600dp. The selected item is a 56x32dp pill in
`secondaryContainer` with a filled Phosphor glyph; unselected items use the
regular glyph.

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

`ListItem`, two lines, no dividers:

- Leading: a 26dp yellow Phosphor fill glyph for the file type.
- Headline: the filename exactly as stored, wrapping to two lines.
- Meta: size in mono, `·`, date. Then, only when true: a grey outline eye when
  the video or audio was played (one glyph, started or finished alike), a
  yellow arrow-circle-down when the file is on this device.
- Trailing: a chevron for folders, an overflow button for files.
- No progress bars or percentages in file rows.

Tap opens the item ([Timestamps and History opens](behavior.md#timestamps-and-history-opens)).
Long-press opens the same actions sheet as the overflow button.

## Item actions

A `ModalBottomSheet` titled with the file's icon, name and meta. The three
common actions are 72dp tiles (Play or Resume, Download, Share); the rest are
56dp icon rows: Select, Mark as watched, Rename, Move, Exclusive link. Move to
trash comes last, in red, after a divider.

**Select** starts selection mode: the top bar turns `surfaceContainerHigh` with
close, "_n_ selected", select all and overflow. Checkboxes replace the file
icons and selected rows take a 9% yellow tint. A floating toolbar above the nav
bar holds Move, Download, Watched and Trash.

## Dialogs and pickers

- **Rename:** an `AlertDialog` with one outlined field. The name is selected up
  to the extension, so typing keeps `.mkv`.
- **Destructive confirm:** the title names the count and the action ("Move 3
  items to trash?"); the body names the consequence ("You can restore them from
  Trash for 14 days."); the confirm is a red filled button.
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

A tab title with space used and free. Sections: Downloading (thin progress bar,
speed, cancel) and On this device (the normal file row).

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

Video plays full screen in landscape. Controls fade in on tap over a top and
bottom scrim:

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

| State        | Glyph        | Headline              | Action       |
| ------------ | ------------ | --------------------- | ------------ |
| Empty folder | `[ ]`        | Empty folder.         | none         |
| No results   | `¯\_(ツ)_/¯` | Nothing matches.      | none         |
| No transfers | `( ⌐■_■)`    | Nothing in the queue. | Add transfer |
| Offline      | `(x_x)`      | Can't reach put.io.   | Try now      |

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

| iOS has                 | Android uses                             |
| ----------------------- | ---------------------------------------- |
| Inset-grouped list      | Segmented list groups under small labels |
| Action sheet            | `ModalBottomSheet` for item actions      |
| Long-press context menu | Long-press opens the actions sheet       |
| Toolbar plus            | Extended FAB                             |
| Text back-label         | System back gesture and a top-bar arrow  |
| `UISwitch`              | The compact neutral switch above         |
| Stepper                 | Slider, presets or a menu                |

## Don't

- Put permanent controls (chips, buttons) between the app bar and a list.
- Use yellow for a second thing in the same group.
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
- Add transfer is a filled button inside the list body, not a FAB.
- Watched files show a progress bar and "N% watched".
- Switches are stock M3 52x32dp in yellow.
- Settings are two-line rows under yellow subheads.
- Type is Roboto; no mono.
- Navigation crossfades; no shared-axis or container transforms.
- No mini player, selection mode, offline banner or tablet list-detail.
- The launcher icon is a placeholder.

## Android TV

The shipped Android TV app is `putio-tv-native-legacy`: the React Native app
shared with Apple TV, with its own components and `@putdotio/colors`. This
repository's `tv` module replaces it; its design pass comes next and lands in
this file.
