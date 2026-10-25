# Files selection

Files can select several items from the actions sheet's Select, then move,
download, mark watched or trash them together. A bar counts the selection and
a toolbar holds the bulk actions; Back ends selection. Downloads shares the
same bar and toolbar for removing local copies.

## Sub-features

- `select-enter` starts selection from the sheet's `Select`.
- `select-toggle` adds and removes rows with taps while selecting.
- `select-move` moves every selected item through one `Move N items` picker.
- `select-trash` trashes them with one Undo snackbar, or confirms a permanent
  delete when Trash is off.
- `select-watched` marks every selected video watched, one write per file.
- `select-download` downloads every selected media file.
- `select-exit` ends selection with Back.
- `select-downloads` selects rows in Downloads (`Select`, `Select all`,
  `Cancel selection`) and removes their local copies.

## How to get to it (user POV)

- Open a row's actions (`Actions for <name>` or long-press) and tap
  `Select`.
- In Downloads, tap `More options`, then `Select`.

## Driving it with verify-putio-android

Preconditions:

- Files selection needs a build with it. Until
  [#397](https://github.com/putdotio/putio-android/pull/397) merges, `main`
  shows no `Select` row in the Files actions sheet: report the Files
  sub-features unreachable on such a build. `select-downloads` works on
  `main`: with downloads present, Downloads' `More options` offers `Select`.
- Doctor passes. The run's root holds `Target folder` and three small
  uploaded files `a-<run>.txt`, `b-<run>.txt`, `c-<run>.txt`.

- **Enter.** Open the run's folder, then the sheet of `a-<run>.txt`, and tap
  `Select`. Run `$S/ui.sh tap $SERIAL --desc "Actions for a-$RUN.txt"` and
  `$S/ui.sh tap $SERIAL --text "Select"`. The bar shows `1 selected`. Capture
  `select-enter`.
- **Toggle.** Tap `b-<run>.txt` and `c-<run>.txt`. Run
  `$S/ui.sh tap $SERIAL --text "b-$RUN.txt"` and the same for `c`. The bar
  shows `3 selected`; tapping one again drops it to `2 selected`.
- **Move.** Intend `move <ids>`. With two selected, tap `Move`, open `Target folder` and confirm.
  Both rows leave; `putio files list --parent-id <target id>` lists both.
  Adopt both ids, then settle.
- **Trash.** Intend `trash <id>`. Select the remaining file and tap `Trash`. The snackbar reads
  `Moved 1 item to Trash` with Undo. Tap Undo and adopt the id, or delete it
  from Trash by id as in [Item actions](./item-actions.md); then settle.
- **Exit.** Press Back while selecting. Run `$S/ui.sh key $SERIAL BACK`. The
  bar leaves and the folder stays.
- **Proof.** Capture the bar, the picker, the snackbar and the CLI read-back.

## Gotchas

- Selection is a Files and Downloads feature only; Search results offer no
  Select.
- Bulk watched writes four files at a time and waits for an SDK release
  ([putdotio/putio-sdk-kotlin#90](https://github.com/putdotio/putio-sdk-kotlin/pull/90));
  until then the action may be missing. Report it as unreachable, not passed.
- A bulk Trash shows one snackbar for every item; each trashed id still needs
  its own removal from Trash.
- Tapping a row while selecting toggles it; it does not open the folder.
