# Files selection

## Drive

Preconditions:

- Doctor passes. The run's root holds `Target folder` and three small
  uploaded files `a-<run>.txt`, `b-<run>.txt`, `c-<run>.txt`.

- **Enter.** Open the run's folder, then
  `$S/ui.sh long-press $SERIAL --text "a-$RUN.txt"`. The bar shows
  `1 selected`. Capture `select-enter`. The sheet's `Select` does the same.
- **Toggle.** `$S/ui.sh tap $SERIAL --text "b-$RUN.txt"` and the same for
  `c`. The bar shows `3 selected`; tapping one again drops it to
  `2 selected`.
- **Move.** Intend `move <ids>`. With two selected, tap `Move`. The picker
  opens at the Files root: open `android-verify-<run>`, then `Target folder`,
  and tap `Move to Target folder`. Both rows leave and selection ends; `putio files list --parent-id <target id>` lists both.
  Adopt both ids, then settle.
- **Trash.** Intend `trash <id>`. Select the remaining file and tap `Trash`. The snackbar reads
  `Moved to trash` with Undo. Tap Undo and adopt the id, or delete it
  from Trash by id as in [Item actions](./item-actions.md); then settle.
- **Exit.** Long-press a file again, then `$S/ui.sh key $SERIAL BACK`. The
  bar leaves and the folder stays.
- **Proof.** Capture the bar, the picker, the snackbar and the CLI read-back.

## Gotchas

- Selection exists in Files and in Downloads, where `More options` offers
  `Select` once downloads are present. Search results offer no Select.
- Bulk watched writes four files at a time and waits for an SDK release
  ([putdotio/putio-sdk-kotlin#90](https://github.com/putdotio/putio-sdk-kotlin/issues/90));
  until then the action may be missing. Report it as unreachable.
- A bulk Trash shows one snackbar for every item; each trashed id still needs
  its own removal from Trash.
- While selecting, a tap on a folder row toggles it too.
