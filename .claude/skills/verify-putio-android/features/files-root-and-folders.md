# Files root and folders

## Drive

Preconditions:

- Doctor passes and Files is on screen.
- The run owns `android-verify-<run>` (from `fixture.sh root`) holding a
  folder `Inner folder` (`fixture.sh mkdir`) and one small text file
  (`printf 'proof\n' > build/verify-note.txt` then `fixture.sh upload`).

- **Root listing.** Open Files at the root. From a fresh launch run
  `$S/ui.sh link $SERIAL putio://files`; inside a folder that link keeps the
  folder, so press Back until the pill shows. Then run
  `$S/ui.sh wait $SERIAL --text "Search your files"`. The pill and the
  five tabs show. Capture `files-root-before`.
- **Refresh.** Pull to refresh. Run `$S/ui.sh refresh $SERIAL`. The listing
  reloads from put.io and now holds fixtures made after launch.
- **Find the fixture.** Scroll the root. Run
  `$S/ui.sh scroll-to $SERIAL --text "android-verify-$RUN"`, then
  `$S/capture.sh $RUN files-root`. The folder row shows its name, its date
  and a chevron.
- **Open folder.** Tap the row. Run
  `$S/ui.sh tap $SERIAL --text "android-verify-$RUN"` and
  `$S/ui.sh wait $SERIAL --text "Inner folder"`. The bar names the folder
  beside `Back`, the line under it reads `2 items` and `6 B`, and the bar's
  search button is `Search in android-verify-<run>` (a content description).
- **Proof.** Run `$S/capture.sh $RUN files-open-folder`. The XML holds
  `Inner folder` and the text file's full name.
- **Back.** Press Back. Run `$S/ui.sh key $SERIAL BACK` and
  `$S/ui.sh wait $SERIAL --text "Search your files"`. The root returns.
- **Empty folder.** Open `Inner folder`. The state reads `Empty folder.` with
  New folder.
- **New folder.** Run `$S/fixture.sh intend $RUN "new folder"`. In the fixture folder, tap `More options`, then
  `New folder`; type a name; tap `Create`. Run
  `$S/ui.sh tap $SERIAL --desc "More options"`,
  `$S/ui.sh tap $SERIAL --text "New folder"`,
  `$S/ui.sh type $SERIAL "Made in app"` and
  `$S/ui.sh tap $SERIAL --text "Create"`. The listing reloads with
  `Made in app`. Read its id with `putio files list`, adopt it and settle.
- **Sort.** Tap `Sort files` in the bar, pick a field under `Sort by` and a
  direction under `Order`. Run `$S/ui.sh tap $SERIAL --desc "Sort files"`.
  The rows reorder. Sort persists to the account: put the folder's sort back.
- **Offline.** Run `$S/ui.sh link $SERIAL putio://files`, then
  `adb -s $SERIAL shell cmd connectivity airplane-mode enable` and pull to
  refresh. The listing stays and `Couldn’t refresh files.` shows with
  `Try again`. Disable airplane mode and tap `Try again`; the folder reloads.

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
