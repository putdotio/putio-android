# Item actions

A row's overflow or long-press opens one actions sheet: the item's glyph,
full name and meta, then Play or Resume, Download and Share as tiles, then
rows for Open in VLC, Select, Make a copy, Mark as watched, Rename, Move and
Exclusive link, and Move to trash or Delete permanently in red. Each shows
only when the item allows it.

## Sub-features

- `actions-open` opens the sheet from the overflow and from long-press.
- `actions-rename` renames with the name selected up to its extension.
- `actions-move` moves into a picked folder, with Cancel and collisions.
- `actions-copy` makes a copy in a picked folder.
- `actions-watched` marks a video watched or unwatched.
- `actions-trash` moves to Trash, or deletes permanently when Trash is off.
- `actions-vlc` hands a video to VLC, or says VLC is missing.
- `actions-search-result` opens a reduced sheet from a Search result.

## How to get to it (user POV)

- Tap `Actions for <name>` at the end of a file's row; folder rows have a
  chevron instead.
- Long-press a file or folder row.
- Tap the overflow or long-press a Search result.
- On a tablet, tap the detail pane's overflow.

## Driving it with verify-putio-android

Preconditions:

- Doctor passes. The run's root holds `Inner folder`, a second folder
  `Target folder`, and an uploaded text file `note-<run>.txt`.
- Read the account's Trash setting first:
  `PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation account.getSettings --execute --output json`
  (`trash_enabled`).

- **Open from overflow.** In the run's folder, tap the file's overflow. Run
  `$V/ui.sh tap $SERIAL --desc "Actions for note-$RUN.txt"`. The sheet shows
  the full name, `Rename`, `Move` and `Move to trash` (or
  `Delete permanently` with Trash off). Capture `actions-open`.
- **Open from long-press.** Press Back, then run
  `$V/ui.sh long-press $SERIAL --text "note-$RUN.txt"`. The same sheet opens.
- **Rename.** Run `$V/fixture.sh intend $RUN "rename <file id>"`. Tap
  `Rename`; the dialog's field selects `note-<run>` and keeps `.txt`. Type a
  new stem and tap the dialog's `Rename` button, the last node with that
  label. Run `$V/ui.sh tap $SERIAL --text "Rename"`,
  `$V/ui.sh type $SERIAL "renamed-$RUN"` and
  `$V/ui.sh tap $SERIAL --text "Rename" --last`. The row reads
  `renamed-<run>.txt`. Adopt the file id and settle, then read its name back
  with `putio files list --parent-id <root id>`.
- **Move.** Intend `move <file id>`, open the sheet, tap `Move`, open
  `Target folder`, confirm. The row leaves this folder;
  `putio files list --parent-id <target id>` lists it. Adopt the id and
  settle.
- **Copy.** `Make a copy` shows only on items shared with the account, never
  on its own uploads. Open `Items shared with you`; if it holds a small
  file, intend `copy <file id>`, open its sheet, tap `Make a copy` and pick
  the run's root. The snackbar reads `Copied “<name>” to <folder>.` Adopt
  the copy's id and settle. With nothing shared, report `actions-copy`
  unreachable.
- **Trash.** With `trash_enabled` true: intend `trash <folder id>`. Long-press `Inner folder` to open its
  sheet. Run `$V/ui.sh long-press $SERIAL --text "Inner folder"`, then tap
  `Move to trash`. The snackbar reads `Moved to Trash` and the row leaves.
  Remove that one id from Trash:
  `PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation trash.delete --args '[{"file_ids":[<id>]}]' --execute`,
  check `trash.list` no longer holds it, then
  `$V/fixture.sh settle $RUN "trashed <id>, removed from Trash"`.
- **Delete permanently.** With `trash_enabled` false the sheet offers
  `Delete permanently` instead. Intend `delete <folder id>`, tap it, confirm.
  The row leaves and `files.get` answers 404; settle with
  `"deleted <id> permanently"`. Never change the account's Trash setting to
  reach the other branch.
- **Proof.** Capture before and after each step, plus each CLI read-back.

## Gotchas

- Rename, Move and Copy change the account. Adopt every touched id before
  cleanup, or cleanup leaves it behind as unrecognised.
- A trashed item reads 404 through `files.get`; cleanup cannot see it. Delete
  it from Trash by id; never empty Trash.
- Mark as watched needs the account's resume setting on and a length put.io
  knows: use the video fixture from
  [Downloads and offline](./downloads-and-offline.md), and report a missing
  action with the file's `files.get` answer. A text file has no watched
  action.
- Open in VLC needs VLC on the emulator and the account's download token;
  without them a snackbar says so. That snackbar is the expected end state
  on a fresh AVD.
- A Search result's sheet leaves out Rename, Move, Make a copy and delete.
- Select needs selection mode: see [Files selection](./files-selection.md).
