# Files root and folders

Files is the first tab: the account's root listing, folders that open in
place with their item count and size, a per-folder sort, New folder, the
Continue watching row for videos played on this device, and an offline state
that retries by itself.

## Sub-features

- `files-root` lists the root's items with the search pill on top.
- `files-open-folder` opens a folder and shows its whole item count and size.
- `files-back` pops folders back to the root.
- `files-sort` changes a folder's order from the bar's overflow.
- `files-new-folder` creates a folder from the overflow or an empty folder.
- `files-empty` shows the empty-folder state.
- `files-continue-watching` lists videos played on this device at the root.
- `files-offline` shows "Can't reach put.io." and retries.

## How to get to it (user POV)

- Launch the app, or tap the `Files` tab.
- Open a link: `putio://files` or `https://app.put.io/files/<id>`.
- Tap a folder row; tap a History, Search or Transfers result that is a
  folder.

## Driving it with verify-putio-android

Preconditions:

- Doctor passes and Files is on screen.
- The run owns `android-verify-<run>` (from `fixture.sh root`) holding a
  folder `Inner folder` (`fixture.sh mkdir`) and one small text file
  (`printf 'proof\n' > build/verify-note.txt` then `fixture.sh upload`).

- **Root listing.** Open Files. Run `$V/ui.sh link $SERIAL putio://files`,
  then `$V/ui.sh wait $SERIAL --text "Search your files"`. The pill and the
  five tabs show. Capture `files-root-before`.
- **Refresh.** Pull to refresh. Run `$V/ui.sh refresh $SERIAL`. The listing
  reloads from put.io and now holds fixtures made after launch.
- **Find the fixture.** Scroll the root. Run
  `$V/ui.sh scroll-to $SERIAL --text "android-verify-$RUN"`, then
  `$V/capture.sh $RUN files-root`. The folder row shows its name, its date
  and a chevron.
- **Open folder.** Tap the row. Run
  `$V/ui.sh tap $SERIAL --text "android-verify-$RUN"` and
  `$V/ui.sh wait $SERIAL --text "Inner folder"`. The bar names the folder
  beside `Back`, the line under it reads `2 items` and `6 B`, and the bar's
  search button is `Search in android-verify-<run>` (a content description).
- **Proof.** Run `$V/capture.sh $RUN files-open-folder`. The XML holds
  `Inner folder` and the text file's full name.
- **Back.** Press Back. Run `$V/ui.sh key $SERIAL BACK` and
  `$V/ui.sh wait $SERIAL --text "Search your files"`. The root returns.
- **Empty folder.** Open `Inner folder`. The state reads `Empty folder.` with
  New folder.
- **New folder.** In the fixture folder, tap `More options`, then
  `New folder`; type a name; tap `Create`. Run
  `$V/ui.sh tap $SERIAL --desc "More options"`,
  `$V/ui.sh tap $SERIAL --text "New folder"`,
  `$V/ui.sh type $SERIAL "Made in app"` and
  `$V/ui.sh tap $SERIAL --text "Create"`. The listing reloads with
  `Made in app`. Read its id with `putio files list` and adopt it.
- **Sort.** Tap `More options`, pick a sort under `Sort by` and `Order`. The
  rows reorder. Sort persists to the account: put the folder's sort back.
- **Offline.** Run `$V/ui.sh link $SERIAL putio://files`, then
  `adb -s $SERIAL shell cmd connectivity airplane-mode enable` and pull to
  refresh. The state reads `Can't reach put.io.` with `Try now`. Disable
  airplane mode; the folder loads again without a tap.

## Gotchas

- The root is the shared account's root: other runs' and other harnesses'
  items sit beside yours. Match your container by its full name.
- Files keeps the listing it loaded. A fixture created after the app opened
  the folder stays missing until `ui.sh refresh`; `scroll-to` alone swipes
  past where it would be.
- A folder row has a chevron and no overflow button; its actions open with
  a long-press. Files rows carry `Actions for <name>`.
- A row's name is cut in the middle on screen; the XML holds the full name,
  so `--text` matches it.
- Count and size come from put.io's total for the folder, not the rows on
  screen. A fixture folder of more than one page still reads its full count.
- The account's sort applies to every client. Read it first and restore it.
- Airplane mode acts on the whole emulator. Turn it off before the next
  recipe, or the app keeps its offline state.
- Continue watching needs a video played past its first report on this
  device; a fresh AVD shows no row. Play one first ([Video player](./video-player.md)).
