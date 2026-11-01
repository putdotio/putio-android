---
version: alpha
name: "put.io on Android"
description: "Design contract for the Android phone, tablet and TV apps: Material 3 Expressive on the put.io token graph."
tier: 2
mode: "dark only"
reviewed:
  date: "2026-10-24"
  against: "putio-android 3.0.0 (adaba1b) on a Pixel emulator, API 37"
canvas:
  phone: "412x915dp"
  tablet: "1280x800dp"
  tv: "960x540dp"
typography:
  headline-lg:
    fontFamily: GT America
    fontSize: 28px
    fontWeight: 500
    lineHeight: 36px
  title-lg:
    fontFamily: GT America
    fontSize: 22px
    fontWeight: 500
    lineHeight: 28px
  body-lg:
    fontFamily: GT America
    fontSize: 16px
    fontWeight: 400
    lineHeight: 22px
  body-md:
    fontFamily: GT America
    fontSize: 15px
    fontWeight: 400
    lineHeight: 20px
  body-sm:
    fontFamily: GT America
    fontSize: 14px
    fontWeight: 400
    lineHeight: 20px
  meta:
    fontFamily: GT America
    fontSize: 13px
    fontWeight: 400
    lineHeight: 18px
  label-md:
    fontFamily: GT America
    fontSize: 13px
    fontWeight: 500
    lineHeight: 18px
  label-sm:
    fontFamily: GT America
    fontSize: 12px
    fontWeight: 500
    lineHeight: 16px
  mono:
    fontFamily: Berkeley Mono
    fontSize: 13px
    fontWeight: 400
    lineHeight: 18px
    fontFeature: '"tnum"'
  tv-title:
    fontFamily: GT America
    fontSize: 22px
    fontWeight: 500
    lineHeight: 28px
  tv-detail-title:
    fontFamily: GT America
    fontSize: 18px
    fontWeight: 500
    lineHeight: 24px
  tv-body:
    fontFamily: GT America
    fontSize: 13px
    fontWeight: 400
    lineHeight: 18px
  tv-button:
    fontFamily: GT America
    fontSize: 14px
    fontWeight: 500
    lineHeight: 20px
  tv-label:
    fontFamily: GT America
    fontSize: 12px
    fontWeight: 500
    lineHeight: 16px
  tv-meta:
    fontFamily: GT America
    fontSize: 12px
    fontWeight: 400
    lineHeight: 16px
  tv-mono:
    fontFamily: Berkeley Mono
    fontSize: 12px
    fontWeight: 400
    lineHeight: 16px
    fontFeature: '"tnum"'
rounded:
  progress: 2px
  extra-small: 4px
  small: 8px
  medium: 12px
  large: 16px
  large-increased: 20px
  extra-large: 28px
  full: 9999px
spacing:
  hairline: 2px
  card-gap: 8px
  gutter: 16px
  dialog-padding: 24px
  touch-target: 48px
  icon: 24px
components:
  navigation-bar:
    backgroundColor: "{colors.surface-container-low}"
    height: 80px
  navigation-indicator:
    backgroundColor: "{colors.secondary-container}"
    rounded: "{rounded.full}"
  navigation-rail:
    width: 96px
  search-bar:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface-variant}"
    rounded: "{rounded.full}"
    height: 56px
  top-app-bar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    typography: "{typography.title-lg}"
    height: 64px
  tab-title:
    textColor: "{colors.on-surface}"
    typography: "{typography.headline-lg}"
  file-row:
    textColor: "{colors.on-surface}"
    typography: "{typography.body-lg}"
    height: 64px
  settings-row:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.on-surface}"
    typography: "{typography.body-md}"
    rounded: "{rounded.extra-small}"
    height: 56px
  settings-group:
    rounded: "{rounded.large}"
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.full}"
    height: 40px
  button-tonal:
    backgroundColor: "{colors.surface-container-highest}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.full}"
    height: 40px
  button-destructive:
    backgroundColor: "{colors.error}"
    textColor: "{colors.background}"
    rounded: "{rounded.full}"
    height: 40px
  destructive-text:
    textColor: "{colors.red-text-secondary}"
  fab-extended:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary-container}"
    rounded: "{rounded.large}"
    height: 56px
  action-tile:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.large-increased}"
    height: 72px
  bottom-sheet:
    backgroundColor: "{colors.surface-container-low}"
    rounded: "{rounded.extra-large}"
  dialog:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    typography: "{typography.title-lg}"
    rounded: "{rounded.extra-large}"
    padding: 24px
  menu:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.large}"
    width: 240px
  menu-item-selected:
    backgroundColor: "{colors.surface-container-highest}"
    height: 44px
  notice:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.large}"
  snackbar:
    backgroundColor: "{colors.inverse-surface}"
    textColor: "{colors.inverse-on-surface}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.small}"
    height: 48px
  transfer-card:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.large}"
  transfer-field:
    backgroundColor: "{colors.surface-container-high}"
    rounded: "{rounded.medium}"
  state-dot-downloading:
    backgroundColor: "{colors.primary}"
  state-dot-seeding:
    backgroundColor: "{colors.green-solid}"
  state-dot-failed:
    backgroundColor: "{colors.error}"
  switch-on:
    backgroundColor: "{colors.on-surface}"
    width: 44px
    height: 26px
  switch-off:
    backgroundColor: "{colors.surface}"
    width: 44px
    height: 26px
  switch-off-handle:
    backgroundColor: "{colors.solid}"
    rounded: "{rounded.full}"
    width: 12px
    height: 12px
  switch-on-handle:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.full}"
    width: 18px
    height: 18px
  progress:
    backgroundColor: "{colors.primary}"
    rounded: "{rounded.progress}"
    height: 4px
  widget:
    backgroundColor: "{colors.background}"
    textColor: "{colors.on-background}"
    rounded: "{rounded.extra-large}"
---

# put.io on Android

This is the target design for phones and tablets, adopted on 2026-10-24.
[Shipped drift](#shipped-drift) lists what 3.0.0 still does differently.
Tokens, voice and the binding tiers come from
[putio-design](https://github.com/putdotio/putio-design/blob/main/DESIGN.md);
this file owns how Android applies them. Dimensions are in dp and text sizes
in sp, written as `px` because the format has no Android units.

## Overview

Tier 2: put.io tokens on stock Material 3 Expressive components
(`androidx.compose.material3` 1.5). Build a component from Material and give it
put.io colour, type and icons; draw a custom one only where this file says so.
Dark only, no dynamic colour. Material owns pressed, disabled, selected and
focused states per component.

The app should read as a quiet file manager and player: neutral surfaces, dense
rows, mono numbers, and yellow only where it marks content or the one action
that matters.

## Colors

The app generates one `darkColorScheme(...)` at build time from the vendored
`dist/tokens.dtcg.json` in `design/`. The block below
is generated from the same graph and the same role map;
`./gradlew updateDesignMdColors` rewrites it, and `verify` fails when it is
stale. Colours with no M3 role come from the generated tokens
(`PutioDesignTokens` in Compose, `@color/putio_*` in XML), never from
hand-written values.

```yaml
colors:
  primary: "#FDCE45" # --yellow-solid
  on-primary: "#2A1E09" # --primary-foreground
  primary-container: "#FDCE45" # --yellow-solid
  on-primary-container: "#2A1E09" # --primary-foreground
  secondary: "#EDEDED" # --text
  secondary-container: "#FDCE4542" # --yellow-solid at 26%
  on-secondary-container: "#FDCE45" # --yellow-solid
  background: "#161616" # --app-bg
  on-background: "#EDEDED" # --text
  surface: "#161616" # --app-bg
  on-surface: "#EDEDED" # --text
  on-surface-variant: "#A0A0A0" # --text-secondary
  surface-container-low: "#232323" # --component-bg
  surface-container: "#232323" # --component-bg
  surface-container-high: "#282828" # --component-bg-hover
  surface-container-highest: "#2E2E2E" # --component-bg-active
  inverse-surface: "#2E2E2E" # --component-bg-active
  inverse-on-surface: "#EDEDED" # --text
  inverse-primary: "#FFD147" # --yellow-text-secondary
  outline: "#3E3E3E" # --border
  outline-variant: "#343434" # --line
  error: "#E5484D" # --red-solid
  on-error: "#161616" # --app-bg
  red-text-secondary: "#FF8E8A" # --red-text-secondary
  green-solid: "#30A46C" # --green-solid
  solid: "#707070" # --solid
```

Neutrals do the work. Yellow marks content and the one primary action of a
screen or dialog:

- Content yellow: file-type icons, progress (transfers, downloads, and the
  video and audio scrubbers), the on-device marker, state dots, the selected
  nav item, selection checkboxes and radios. These can share a row.
- Action yellow: the extended FAB, a dialog's confirm, a filled primary button.
  One per screen or dialog.
- Neutral: switches, the player's transport buttons, status icons such as the
  played eye, every other button.
- Red: destructive actions and failures only, plus the storage bar when
  storage is full, and the product analytics prompt's never-send glyphs. Red text and red icons use the light
  `--red-text-secondary`: `red-solid` text reaches only 4.0:1 on the surface
  tiers. The destructive button keeps the `error` fill with dark
  `background` text, which reaches 4.6:1; white text on it reaches 3.9:1. Green: seeding and completed
  transfer dots, the seeding card's progress fill, and the product analytics
  prompt's would-send glyphs.

## Typography

GT America for UI; Berkeley Mono for sizes, times, speeds, counts,
percentages and version numbers, with tabular figures. Dates read as words
("Oct 13") and stay in GT America; words that sit in a value slot ("Always
on", "Phone") stay in GT America too. Numbers never go below 12sp. Filenames stay in GT
America, where mono would fit fewer characters on the line.

| Token         | Use                                        |
| ------------- | ------------------------------------------ |
| `headline-lg` | Tab title                                  |
| `title-lg`    | Top app bar, dialog and sheet titles       |
| `body-lg`     | Row headline                               |
| `body-md`     | Settings row, menu item                    |
| `body-sm`     | Dialog, sheet, notice and snackbar body    |
| `meta`        | Row meta                                   |
| `label-md`    | Section label                              |
| `label-sm`    | Nav label                                  |
| `mono`        | Numbers in meta; takes the size it sits in |

## Layout

Five destinations: **Files, History, Transfers, Downloads, Account**, in that
order. `NavigationSuiteScaffold`'s layout and suite with an explicit layout type:
a navigation bar below 600dp width or 480dp height, and the collapsed wide
navigation rail otherwise, following the window. A phone in landscape is compact
in height, so it keeps the bar, as Material's navigation suite does. When five
destinations don't fit, in short windows or at large font scales, a scrollable
drawer takes over. Changing layout never rebuilds the content under it.
The selected item uses the stock indicator with a filled Phosphor
glyph in `on-secondary-container`; unselected items use the regular glyph.

From 840dp wide and 480dp tall, Files is list-detail, so a phone in landscape
keeps one pane: the folder's bar and list on the left (520dp,
or half the space beside the rail when that is less), the selected file's detail on the right
with its preview, primary actions and facts (size, added, length, subtitles,
folder). Here a tap on a file selects it into
the detail pane; Play in the pane starts playback. Folders open in the list.

Each tab keeps its own back stack. Back pops within the tab, then returns to
Files, then leaves the app. Predictive back is on for every route; never
intercept back at the root.

Content keeps a 16dp gutter. Icon buttons are 48dp targets around 24dp glyphs,
with no gap between neighbours.

## Elevation & Depth

Depth is tonal. Surfaces step up from `surface` through the
`surface-container` tiers: rows and cards sit one step above the page, sheets
and the nav bar on `surface-container-low`, dialogs, menus and notices on
`surface-container-high`. Sheets and dialogs dim the page with Material's
scrim. Menus and snackbars keep Material's own shadow; nothing else draws one.

## Shapes

Corners follow the M3 shape scale: 28dp for sheets (with a 32x4dp handle),
dialogs and the widget;
20dp for action tiles; 16dp for cards, menus, notices, the FAB and settings
groups; 12dp for the Add transfer field; 8dp for snackbars; 4dp for the inner corners of segmented groups.
Search, buttons and the nav indicator are full pills; chips keep M3's 8dp
corners, and a selected filter chip shows its leading check. Progress bars
round to 2dp.

## Components

### App bars

- **Files root:** an M3 search app bar: a pill reading "Search your files",
  with sort and overflow icon buttons inside it. Under it, a **Continue
  watching** row of started videos (name, a yellow progress line, the time left
  in mono) when there are any, then the list. Nothing else sits between the bar
  and the list, except the [inactive-account notice](#notices-and-snackbars),
  the offline banner and the background copy line.
- **Folders:** a small top app bar: back, the folder name, search in this
  folder, sort, overflow (with New folder). Searching here sends the query with
  put.io's `in:<folderId>` scope, so it covers the whole folder, not only loaded
  pages. A section line under it gives the item count and the total size in
  mono.
- **History, Transfers, Downloads, Account:** a tab title with an optional
  one-line summary below it (for example "2 active · 2 failed · 56.2 MB/s"),
  and an overflow at the top right when the tab has actions for it. Below
  480dp of window height, as on a phone in landscape, the title drops to
  `title-lg` and sits on one 64dp top-app-bar row with the summary after it
  and the actions at the end, so the list keeps the height.

### Search

Tapping the search pill opens `ExpandedFullScreenSearchBar`: back arrow, query,
clear. Type chips (All, Folders, Videos, Audio, Subtitles) sit under the field;
they map to the API's type filter, and Subtitles filters by extension on the
device. Results group into Folders and Files, each with a mono count; a file's
meta names its parent folder once its name is known (results carry only its
id). Recent searches show while the query is empty.

### File rows

`ListItem` with two slots, headline and meta, and no dividers. A row is 64dp
(taller only when larger text needs it), with a 40dp leading slot and 16dp
start padding:

- Leading: a 26dp yellow Phosphor fill glyph for the file type.
- Headline: the filename on one line. A name that doesn't fit is cut in the
  middle, keeping its start and the last few characters before and including
  the extension ("Blue.Lights.S04E01…ORGANiC.mkv"). Every list that shows a
  filename or a transfer name cuts it this way, and so do the player's title
  and the mini player.
- Meta: size in mono, `·`, date. A file's date is when it was added; a
  folder's is its last change, "3 days ago", until a week has passed, then
  "Oct 7". The shared folders show no size. Then, only when true:
  a grey outline eye when the video or audio was played (one glyph, started or
  finished alike), a yellow arrow-circle-down when the file is on this device.
- Trailing: an overflow button for any row with actions; a folder without
  them shows a chevron.
- No progress bars or percentages in file rows.

Tap opens the item. Long-press starts selection with that row selected, in
Files and Downloads. Where a list has no selection, as in Search, long-press
opens the same actions sheet as the overflow button.

### Item actions

A `ModalBottomSheet` titled with the file's icon, full name and meta; the name
wraps, uncut, and can be selected. The three
common actions are action tiles (Play or Resume, Download, Share); the rest are
56dp icon rows: Open in VLC (videos), Select, Mark as watched, Rename, Move,
Exclusive link. The
delete action comes last, in red, after a divider: Move to trash, or Delete
permanently when the account turns Trash off. The
sheet shows only what the item allows: Play and Download follow the file type.

Shared items keep the tiles, add **Make a copy** to the rows, and drop the
owner actions without explaining why. Their meta
names the sharer.

### Selection

Long-press or the sheet's **Select** starts selection mode: the top bar turns `surface-container-high`
with close, "_n_ selected" and select all. Checkboxes replace the file icons,
the trailing overflow or chevron leaves, and selected rows take a 9% yellow
tint. A floating toolbar above the nav bar holds Move, Download, Watched and
the delete action. Each action shows only when every selected item allows it,
under the same rules as the sheet; Watched reads Unwatched when every file is
finished, and delete reads Delete, and confirms with the count, when the
account turns Trash off. Back ends selection before it leaves the folder. In
list-detail, the detail pane shows no file while rows are selected. Downloads
uses the same bar, checkboxes, tint and toolbar tiles.

### Menus

**Sort** is a dropdown menu anchored to the sort button, in three labelled
groups with a check on the current choice: Sort by (Name, Size, Date added,
Date modified, Type), Order (A to Z, Z to A; the words follow the field), and
Videos (Unwatched first, Watched first). A Videos choice replaces the field
and order. The button's label names the current sort.

### Dialogs and pickers

- **Rename:** an `AlertDialog` with one outlined field. The name is selected up
  to the extension, so typing keeps `.mkv`.
- **Destructive confirm:** the title names the count and the action ("Delete 2
  items permanently?"); the body names the consequence; the confirm is a red
  filled button. Permanent deletion always confirms. Move to trash never does:
  it happens at once, and a snackbar says "Moved to Trash" with **Undo**.
- **Sign out:** "Sign out of put.io?", then "Downloads stay on this device and
  come back when you sign in again.", then Cancel and a red Sign out. When the
  device holds downloads, a checkbox, off by default, reads "Remove downloads
  from this device" with their size in mono.
- **Choices** (proxy, video playback, default sort, subtitle language): a
  dialog of radio rows, each with a one-line description, then Cancel and a
  filled Save.
- **Move:** a full-screen picker: close, "Move _n_ items", breadcrumb chips,
  folder rows with chevrons, and a bottom bar with New folder (text) and a
  filled confirm that names the target ("Move to Series").

### Exclusive link sheet

A sheet titled **Exclusive access**. One body line names the file and the
limits in prose: a link stops working after 72 hours, or sooner at its IP or
data limit (the API returns no limit numbers). Each live link is a row: the full
link in mono, cut in the middle, "Expires in" with a mono remainder, then copy,
share and revoke icon buttons; revoke confirms. A filled **Create link** ends the sheet.

### Product analytics prompt

A sheet titled **Turn on product analytics?**, with the iOS copy word for word:
one body line, then two outlined boxes, "What we never send" with x-circle
glyphs in `red-text-secondary` and "What we'd send" with check-circle glyphs
in `green-solid`, side by side from 500dp and stacked below. Each glyph scales
with the text and centres on the item's first line. **Don't allow** and **Allow** are equal tonal buttons, so neither
reads as the default, and an underlined **Privacy policy** link ends the
sheet. While an answer saves, its button shows a spinner and the sheet can't
be dismissed; a failure shows in red text above the buttons.

### Notices and snackbars

- **Inactive account:** a notice card under the top bar of every signed-in
  screen, never a dialog, with nothing to tap. A red warning glyph leads; the
  card and its text stay neutral. A medium first line states the
  account's state, either deactivated or a family plan no longer active; a
  deactivated account adds the deletion countdown with the days in mono.
- **Snackbar:** one short line confirming a finished action ("Link copied"),
  above the nav bar, with at most one text action.

### Switch

Neutral and compact. Off: a 2dp `--solid` outline with a 12dp handle. On: an
`on-surface` track with an 18dp `surface` handle. This is a deliberate step off
stock M3. The whole row toggles.

### Empty and error states

The
[put.io empty-state voice](https://github.com/putdotio/putio-design/blob/main/system/preview/12-empty-state.html):
a mono glyph, a one-line headline, one sentence, at most one action. No artwork.

| State        | Glyph        | Headline              | Action                            |
| ------------ | ------------ | --------------------- | --------------------------------- |
| Empty folder | `[ ]`        | Empty folder.         | New folder                        |
| No results   | `¯\_(ツ)_/¯` | Nothing matches.      | none                              |
| No transfers | `( ⌐■_■)`    | Nothing in the queue. | Add transfer, in place of the FAB |
| Offline      | `(x_x)`      | Can't reach put.io.   | Try now                           |

Offline keeps what works: a banner above the list ("Offline. Showing what's on
this device.") and the on-device files stay usable.

Loading draws skeleton rows in the file row's slots, pulsing on
`surface-container-high`; no spinner sits in the list. Pull to refresh uses
Material's indicator in yellow, and paging adds one small spinner row at the
end.

## Do's and Don'ts

- Do keep the space between the Files app bar and its list empty. The
  Continue watching row, expanded search's type chips, the inactive-account
  notice, the offline banner and the background copy line are the exceptions.
- Do use one yellow action per screen or dialog.
- Do show progress in transfer cards and Downloads, never in file rows.
- Do generate every colour from the token graph.
- Do use regular-weight Phosphor icons for chrome; fill marks selected or
  active states and file-kind glyphs.
- Do keep saving to put.io (`cloud-arrow-down`) and downloading to this device
  (`download-simple`) on distinct glyphs.
- Don't use web recipes (`components.css` `.switch`, `.modal`, `.sheet`).

Don't translate iOS:

| iOS has                 | Android uses                                           |
| ----------------------- | ------------------------------------------------------ |
| Inset-grouped list      | Segmented list groups under small labels               |
| Action sheet            | `ModalBottomSheet` for item actions                    |
| Long-press context menu | Long-press selects; the overflow opens the actions     |
| Toolbar plus            | Extended FAB                                           |
| Text back-label         | System back gesture and a top-bar arrow                |
| `UISwitch`              | The compact neutral switch above                       |
| Stepper                 | Slider, presets or a menu; speed steps through presets |

## Motion

Material motion through the standard motion scheme's springs. Material 3 1.4
keeps `MotionScheme` internal, so the app repeats its spring values until the
scheme is public.

- Between tabs: fade through, in Material's two timed phases: the old tab
  fades out in 90ms, then the new one fades and scales in over 210ms.
- Within a tab (folder push and pop, Search over Files, Account's subpages):
  shared axis X.
- File row to player: container transform.
- Sheets, dialogs and menus: Material's own enter and exit.
- Predictive back previews every pop: a route shows the screen beneath it, and
  a folder or Search shrinks toward the gesture's edge. Reduced motion turns
  transitions into fades.

## Transfers

A tab title with a live summary, then sections: Active, Needs attention,
Completed. Each transfer is a transfer card:

- The card background fills left to right with progress, as on the web:
  yellow at 9% while downloading, green at 12% while seeding. At 9% the
  `on-surface-variant` meta keeps 4.5:1.
- A state dot (yellow downloading, grey queued, red failed, green seeding or
  done), then mono meta: "847 MB of 3.42 GB · 34 peers".
- Trailing: ETA and speed in mono while running; a retry icon button on
  failures; a chevron on completed ones, which open on tap.
- Failed cards stay neutral: only the red dot marks them, and the reason reads
  in `on-surface-variant`.

Tapping a running or failed card, or long-pressing any card, opens **Transfer
actions**: a sheet with the full name, the state, put.io's whole reason in
`body-sm` `on-surface-variant` when it gave one, and a progress bar, then facts
(size, speed, time left and peers in mono; the destination folder in GT
America), Open folder, and Copy source link when put.io returns the transfer's
source. Running transfers end with Stop transfer in red; failed ones offer
Retry; completed ones offer Open file and Remove from list, which doesn't
confirm. The tab's overflow holds Clear completed.

**Add transfer** is a yellow extended FAB, 16dp from the edge above the nav
bar, that collapses to its icon on scroll.
It opens a sheet: one field for links and magnets, Paste and .torrent chips,
the destination with Change, then Cancel and Add. Each line is checked on its
own: a line that doesn't parse stays in the field with a one-line reason, and
Add counts the lines that will go ("Add 1"). Below 480dp of window height the
sheet drops its drag handle and its content scrolls; Cancel and Back close it.

**Widget:** the home-screen widget is a `background` card titled Transfers
with a yellow glyph and a refresh button. Up to three running transfers follow,
each with its name on one line, its state and mono percentage, and a progress
bar; the footer gives the mono update time and how many more are running.

## Downloads

A tab title with space used and free. Sections, each shown only when it has
rows:

- Downloading: running, queued and paused downloads, with speed or the waiting
  reason in the meta and a 36dp progress ring in the trailing slot: yellow with
  pause bars while running, grey with a play triangle while paused, dashed
  with a stop square while queued. Tapping the ring pauses or resumes the
  download, or cancels a queued one. A wait for the network or storage is grey
  with pause bars: it resumes by itself, so the ring offers Pause.
- Needs attention: failed and missing downloads, with Retry or Download again
  as a trailing icon button. The meta reads a red "Failed" and a short reason
  in `on-surface-variant`; the sheet keeps the full sentence.
- On this device: the normal file row, without the on-device marker.

The tab's overflow holds Select and Downloads at the same time, with the limit
in mono. Long-press starts selection: its bar replaces the tab title, and the
On this device label gains the space used in mono. The floating toolbar holds
Pause, Resume and Remove; each shows when at least one selected row allows it
and acts on those rows. Remove cancels unfinished downloads and deletes local
copies.

Actions here act on the local copy only: Remove from this device, with a
confirm. They never trash or delete the file on put.io.

## History

A tab title, date headers (Today, Yesterday, then dates), neutral event glyphs,
and one-line rows: the name the event concerns, cut in the middle, over what
happened ("Transfer completed", "Shared with you", "Uploaded") and its time in
mono. Clear history lives in the overflow and confirms with a red filled
button.

## Account

A tab title, then the identity line (40dp avatar, name, email) and a storage
card ("Storage", used of total in mono, a 4dp bar). Settings follow as
segmented groups under `label-md` labels, with `hairline` (2dp) gaps between
rows. Every row is one line, with a leading glyph, the title and a trailing
value or switch.

Groups, in order: Library (Exclusive links, Trash), Files, Playback, Subtitles,
Privacy, About, then Sign out alone, in red, behind its
[confirm](#dialogs-and-pickers). Download concurrency stays on the Downloads
tab. Library rows show the link count and Trash's size in mono once the app
has loaded them; App info opens its own page.

**Privacy controls** opens its own screen, in this order: Strictly necessary
as Always on, with no switch; Diagnostics and Product analytics, each a
two-line row with its explanation; the footer saying neither sends filenames,
media titles, URLs, search text or tokens; then the privacy policy and the
services list.

## Player

Video plays immersive full screen; orientation follows
the video: a landscape video
turns a phone to landscape, a portrait video keeps the window's orientation,
and tablets and multi-window use the window they have. Controls fade in on tap
over a top and bottom scrim:

- Top: back, the filename, cast, picture-in-picture.
- Centre: a 72dp translucent dark circle for play and pause, flanked by 56dp
  circles for 10 seconds back and forward.
- Bottom: elapsed and remaining in mono, a yellow scrubber, then Audio,
  Subtitles and Speed chips, and Next episode on the right when the account's
  next-episode setting is on.

**Cast** shows in the top bar only while a receiver is in reach, in yellow while
casting. On Android 17 it also shows until the user allows nearby devices; its
tap asks, then opens the picker. It opens "Cast to", a bottom sheet at most
560dp wide: 64dp rows with the kind's glyph (TV, screen, speaker), the name over
its model or "Audio only", a yellow check on the one playing, and Stop casting
while connected. Casting video swaps the picture for black with "Playing on" and
the device, over the cast glyph in `on-surface-variant`, above the same
transport, times and scrubber; only Speed stays, when the receiver takes a
playback rate. Now Playing keeps its layout and names the device in its top bar.
When the file fails to reach the receiver, the label says so and Try again sends
it once more.

Audio, Subtitles and Speed open a 340dp side sheet from the right with the
video dimmed behind:

- Audio: tracks by language, then channels and codec.
- Subtitles: Off, then tracks in the file, then subtitles put.io found, then
  **Size and timing**: text size S to XL and a timing offset in 0.1 s steps
  with the value in mono, plus Reset. Font and colours follow Android's caption
  settings.
- Speed: the value large in mono between 56dp − and + buttons that step
  through the presets 0.5×, 0.75×, 1×, 1.25×, 1.5×, 2×, 2.5× and 3×; a
  stepped track over the eight stops with the current one in yellow; then
  chips for 1×, 1.25×, 1.5× and 2×. Every control snaps to a preset.

A video that needs **MP4 conversion** keeps the player's frame with the video
dimmed: "Converting to MP4", one line saying it keeps going if you leave, and a
progress bar with a mono percentage. The one tonal action follows the state: Check again while it
waits for the viewer, Convert again after a failure, and only Back for a video
that can't be converted.

Audio keeps playing while you browse: a mini player card above the nav bar on
every tab, on `surface-container-high` with 16dp corners, 8dp from the sides
and the bar. It holds the type glyph on a 40dp `surface-container-highest`
tile, the filename cut in the middle in `body-sm`, elapsed and total time in
mono, pause and close, and a 2dp yellow progress line along its foot. Below
480dp of window height and from 600dp wide, the time sits after the filename on
the same line.
Tapping it opens Now Playing:

- Top: back as a caret down, Cast, and More when the file has more than one
  audio track.
- A large type glyph on a square `surface-container` tile with 28dp corners,
  32dp from the sides.
- The filename in 20sp medium on a 28sp line, on up to two lines and cut in the
  middle past that, over its folder's name in `on-surface-variant`.
- A yellow scrubber with elapsed and remaining times in mono under it.
- Back 15 seconds, previous, an 88dp neutral play and pause, next, forward 30
  seconds.
- Speed, Sleep timer and Up next chips, each opening a bottom sheet. Speed
  holds the speed panel above, so audio steps through the same eight presets.
  Sleep timer offers Off, 15, 30 and 45 minutes and 1 hour; while it runs, its
  chip fills and counts down in mono. Up next shows the next file.

Previous, next and Up next are the files of the same type before and after
this one in its folder, in name order; the API has no queue.

## Welcome

The signed-out screen is shared with iOS. The retro wordmark sits centred in
a 64dp top bar, 88dp wide. 96dp below the bar, a 220dp stage centres a kaomoji
over a caption, 24dp apart. The kaomoji is set in GT America medium at 48dp on
a 56dp line, as art that ignores the font scale and shrinks only to fit a
narrow window; the caption is 15sp mono in `primary`. When less than 600dp sits
below the bar, as in landscape or split screen, the gap above the stage drops
to 24dp and the screen scrolls.

| Kaomoji        | Caption               |
| -------------- | --------------------- |
| `┌( ಠ‿ಠ)┘`     | "Paste a magnet."     |
| `( ⌐■_■)`      | "put.io fetches it."  |
| `ᕕ( ᐛ )ᕗ`      | "Stream it anywhere." |
| `(づ｡◕‿‿◕｡)づ` | "Or keep it offline." |

Each frame holds for 1.9 s, then the next rolls up into its place over 0.6 s
on `cubic-bezier(0.7, 0, 0.2, 1)`. After the fourth, the first rolls back in:
a 10 s loop. Page dots sit 8dp under the stage: 6dp dots, 8dp apart, in
`surface-container-highest`; the current one grows to 18dp in `on-surface` and
follows the roll. Reduced motion holds the first frame.

The copy sits at the bottom, left-aligned, 24dp from the sides and 16dp above
the navigation bar: **Welcome!** as a 40sp medium title on a 48sp line, then
"Your put.io files, streaming and offline on this phone." in `body-lg` on a
24sp line in `on-surface-variant`, 16dp below. 32dp lower, one full-width
56dp filled **Sign in** opens put.io OAuth in the browser.

- Session expired: **Welcome back!** and "Your session expired. Sign in again to
  continue." in place of the lede.
- While the browser finishes: a still `( ˘ω˘ )` over "Waiting for your
  browser…", no dots, **Almost there**, "Finish signing in in your browser,
  then come back here.", and a full-width text **Cancel** in `on-surface`.
- Failure: a still `(・_・;)` over "That didn’t work.", no dots, the welcome
  copy, then 24dp lower a notice and 24dp below it a filled **Try again**. The
  notice is a 16dp-corner card on `surface-container-high`, padded 14dp by
  16dp, in `body-sm`: a medium first line, "Sign-in didn’t finish.", then
  "Nothing was saved, so you can try again." When the saved session can't be
  read, the notice reads "Secure storage is unavailable." then "Your session
  couldn’t be read or saved safely on this device. Reset clears it so you can
  sign in again.", and the action is **Reset and sign in**.

## Other screens

- **Recent searches:** while the query is empty, a list of past queries with a
  clock glyph and a remove button, and **Clear** on the section line.
- **Background copy:** Make a copy shows one line under the bar, "Copying
  “name” to Folder…", with a small yellow spinner. It survives folder changes
  and can be dismissed; a failure swaps the spinner for a red dot and the
  reason.
- **New folder:** a dialog from the Move picker, a folder's overflow or the
  empty-folder state, with one outlined field, the parent named above it, and a
  filled **Create**.
- **Viewers:** images, PDF and text open in-app on a black frame, with the name,
  Share, Download and the overflow over a top scrim; swiping moves between
  files of the same kind, with the position in mono. Types that neither the
  players nor the viewers handle open with another app.
- **Share progress:** while a file downloads to be shared, a dialog with the
  name, a bar, mono progress and **Cancel**.
- **Resume:** "Resume from 18:42?" over the dimmed player, with **Start over**
  and a filled **Resume**.
- **Player error:** the empty-state voice over the dimmed player, with
  **Open in VLC** as the only action for a video the player can't play, or
  **Try again** when a retry can fix it. It never offers Download, which keeps
  the same HLS rendition, or a browser, whose history would keep the
  tokened link.
- **Drop zone:** dragging a link, magnet or .torrent over Transfers shows one
  dashed yellow area with a 48dp yellow magnet glyph, "Drop to add a transfer"
  at 20sp, and one `on-surface-variant` line naming what it accepts.
- **Trash:** days left in each row's meta, **Restore** on the row (it confirms,
  like every Trash action), **Empty trash** in the overflow.
- **App info:** version, channel, device, API level and player as value rows,
  then the privacy note and a tonal **Copy for support**.
- **Storage full:** the storage bar turns red, and one line says new transfers
  wait and what frees space.
- **Notifications:** transfers post none. Downloads set the small icon on
  yellow: a running count over the first running file's name and the overall
  percentage, a bar, **Pause** and **Cancel**; **Download finished** with
  **Play**; and **Download failed** with the reason, **Try again** and **Open
  in Downloads**. Notification text stays in the system face. Playback keeps its media notification and
  share-out its progress, failure and ready notifications.
- **Launcher icon and shortcuts:** the putio-design app icons (production,
  nightly, and dev for debug builds) as an adaptive icon, so each launcher
  masks the full art to its own shape. Long-press offers Add transfer, Search
  files and Downloads, in that order, each a yellow glyph on a dark disc.

## Accessibility

- Text follows the system font size up to a 1.3 font scale, two steps above
  the default, as the iOS app caps at Extra Extra Large; larger settings
  render at 1.3. Widgets and notifications keep the system scale.
- Every target is at least 48dp, including chips, breadcrumbs, menu rows and
  the widget's refresh.
- Colour never carries a state alone: each state dot has a word next to it,
  including "Downloading".
- Icon-only buttons, rings, chips and switches carry a name that says what
  they change ("Audio: English", "Pause download"). A settings row toggles as
  one node.
- A cut filename keeps its full name as the row's content description.
- When the system turns animations off, the Welcome sequence, the loading
  pulse and spinners hold still.

## Shipped drift

3.0.0 still differs from this contract:

- Expanded search is M3's search input field in the pill's place, not
  `ExpandedFullScreenSearchBar`, and the navigation bar stays under it.
- Names outside Files, Transfers, Downloads, History, Search, Trash and
  Exclusive links wrap instead of being cut in the middle on one line.
- Empty states outside Files, Transfers and Search keep 3.0.0's words and offer
  no action.
- Only the player's timestamps, the tab-title summaries, Transfers, Files,
  Account, Search, Downloads and History rows, Trash sizes, Exclusive link
  expiry and the share dialog's progress set numbers in mono; sizes, speeds and
  counts elsewhere use the UI face.
- Transfer cards and Transfer actions show no peer count: the Kotlin SDK's
  `Transfer` has no peers field.
- The viewers have no Download: Downloads hold video and audio only.

## Android TV

The target design for the `tv` module, adopted on 2026-10-31. It is this file
translated to a 960x540dp screen and a D-pad: the same tokens, type, glyphs,
rows, switch, empty-state voice and copy. Only what differs from the phone is
written here. The shipped Android TV app is still `putio-tv-native-legacy`,
the React Native app shared with Apple TV.

### TV layout

- The shared `tv.overscan` token is the minimum inset. Layouts keep a 48dp side
  margin and 24dp at top and bottom; only the player picture bleeds.
- A top bar holds the wordmark, then pill tabs: **Home, Files, Search, History,
  Account**, left-aligned after the wordmark. The selected tab takes the nav
  indicator: a `secondary-container` pill with the filled glyph in
  `on-secondary-container`. The app opens on Home.
- Each tab keeps its own back stack. Back pops within the tab, then moves focus
  to the tab bar; Back in the tab bar goes to Home, then leaves the app.
- Tab roots have no page title; the selected tab names the place. A folder
  shows its name under a back crumb naming its parent.
- Files, folders, Search and History are list-detail: a 544dp list, and beside
  it the focused item's detail with no card and no preview. The detail shows the
  full name uncut, its meta, the actions, then at most three facts. A video's
  actions are **Resume from 18:42** (or **Play** when unstarted), **Start over**
  and More; a folder's detail shows "n items · size" and **Open**; other files
  follow the phone's Open rules. The actions stay tonal while focus is in the
  list, so the focused row is the only bright thing; Resume fills yellow once
  focus moves into the detail.
- Home shows **Continue watching**, then **Pinned**. Both use the Continue
  watching card: glyph and name, then the progress line or the item's meta.
  Continue watching comes from the same store as the phone's row, recorded by
  the TV player too, and hides when empty. Pins live in the account's config
  (`/v2/config`), so they follow the account. Home is TV-only until mobile takes
  pins.
- Account lists its groups on the left (identity, the storage card, then
  Library, Playback, Subtitles, Privacy, About, and Sign out in red) and shows
  the selected group's rows on the right. Subtitles holds the phone's two
  switches. Privacy uses the phone's Privacy controls copy word for word; the
  privacy policy and the services list open as QR codes. About adds a QR code
  that carries the support details in place of Copy for support.
- Sort is a tonal pill on the list's section line that names the current sort.
  History puts **Clear history** there the same way. Search puts a **Settings**
  pill after its type chips; Menu on the field opens it too.

### Focus

- One focus style: a focused row, tab, chip, card or tonal button turns
  `#EDEDED` with `on-focus` text and glyph. Nothing yellow sits on the focus
  fill: a focused card's progress line turns dark, and a focused file row's
  glyph turns dark. Nothing scales on focus.
- Filled buttons, yellow or red, keep their fill on focus and gain a 2dp light
  ring outside a 2dp `surface-container-high` gap.
- A tab selects when it takes focus. Down from the tab bar lands on that tab's
  last-focused row; Up from a tab's first row reaches the tab bar.
- Returning from the player focuses the last file played. A closed sheet
  returns focus to what opened it.
- The inactive-account notice and the snackbar never take focus on their own.

### TV type

The `tv-*` roles in the front matter: `tv-title` for sheet and dialog titles,
`tv-detail-title` for the detail's name and empty-state headlines, `tv-body` for
rows and tabs, `tv-button` for button labels, `tv-label` for section labels,
`tv-meta` for row meta and dialog bodies, and `tv-mono` for numbers. Numbers
never go below 12sp.

### TV components

- **Side sheet:** every secondary surface is a 360dp sheet from the right on
  `surface-container-high`, with 28dp leading corners, dimming the screen: item
  actions, sort, choices, search settings, Audio, Subtitles, Speed, and Size and
  timing.
- **Item actions:** More in the detail, or Menu on a row, opens the sheet. Its
  header is the glyph, full name and meta. Three tiles follow (Resume, Start
  over, Subtitles), then rows (Open in VLC, Mark as watched or unwatched, Pin to
  Home or Unpin), a divider, and **Move to trash** in red, or **Delete
  permanently** when the account turns Trash off. The Subtitles tile opens the
  player's Subtitles sheet, then plays with that choice.
- **Sort:** the phone's three groups (Sort by, Order, Videos) in a side sheet.
- **Choices** (proxy, video playback, default sort): radio rows with a one-line
  description, then Cancel and a filled Save.
- **Search settings:** a side sheet with **Show search history** as a switch and
  **Clear search history**.
- **Dialogs** carry only confirms and notices: Resume, destructive confirms with
  a red filled button, **Clear history?**, **Sign out of put.io?** ("You can
  sign in again any time with a new code."), and one-line notices with OK ("VLC
  isn't installed").
- **Snackbar:** "Moved to Trash" with **Undo**, bottom-left for 6 s. Down from
  the list reaches Undo while it shows.
- **Switch:** the phone's neutral switch at 28x16dp, so it sits within a 13sp
  row.

### TV player

- The phone's player: back and the filename, the centre circles, a yellow
  scrubber with mono times, then Audio, Subtitles, Speed and Next episode chips.
- Back closes the open sheet, then seek mode, then the controls, and only then
  leaves the player.
- With Autoplay next video on, the end of a video shows an **Up next** card:
  the next file's name, "Playing in 8" in mono, a draining line, **Play now**
  and **Cancel**. It counts 10 s.
- Loading is a spinner over black under the filename. Errors use the
  empty-state voice over the dimmed frame. MP4 conversion follows the phone.
- The screen stays on only while a video plays.

### TV sign-in and startup

- Sign-in: the wordmark, the phone's Welcome stage (the four kaomoji frames
  rolling, with page dots), **Sign in to put.io**, then two columns split by a
  line marked "or". **With your phone** holds the QR code and "Scan with your
  camera and approve." **In a browser** holds "Go to put.io/link and enter", the
  code in mono tiles, and **Get a new code**. There is no countdown.
- When a code expires, the app fetches a new one silently, up to three times in
  a row. After that, or when put.io rejects a code, the tiles and the QR code dim
  under "This code expired." until **Get a new code**.
- Session expired reads **Welcome back!** over "Your session expired. Sign in
  again to continue."
- Startup shows "Checking your session" under a spinner. Offline reads `(x_x)`
  **Can't reach put.io.** with **Try now**, and retries on its own when the
  network returns.

### TV states

| State           | Glyph        | Headline                      | Action       |
| --------------- | ------------ | ----------------------------- | ------------ |
| Empty folder    | `[ ]`        | Empty folder.                 | none         |
| No results      | `¯\_(ツ)_/¯` | Nothing matches.              | none         |
| History off     | `(-_-) zZ`   | History is off.               | Open Account |
| Couldn't load   | `(・_・;)`   | Couldn't load your files.     | Try again    |
| Offline         | `(x_x)`      | Can't reach put.io.           | Try now      |
| Playback failed | `(・_・;)`   | Couldn't play this file.      | Try again    |
| Can't play here | `¯\_(ツ)_/¯` | This TV can't play this file. | Open in VLC  |

No results uses the phone's message: "No files or folders named “query”. Try
fewer words." Loading draws skeleton rows in the list and skeleton lines in the
detail.

### Launcher

- The banner is the wordmark centred on `background`, 320x180dp.
- Play Next and system search cards show put.io's screenshot when the file has
  one, else the wordmark on `background`, with the full name and, in Play Next,
  the progress. A file enters Play Next once started and leaves at 95%.

### Not on TV

Transfers, Downloads, Cast, selection, rename, move, share, exclusive links,
the viewers and audio's Now Playing stay phone-only.
