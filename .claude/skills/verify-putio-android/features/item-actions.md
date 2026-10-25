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

- Tap `Actions for <name>` at the end of a Files row.
- Long-press a Files row.
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
  the full name, `Rename`, `Move`, `Make a copy` and `Move to trash` (or
  `Delete` with Trash off). Capture `actions-open`.
- **Open from long-press.** Press Back, then run
  `$V/ui.sh long-press $SERIAL --text "note-$RUN.txt"`. The same sheet opens.
- **Rename.** Tap `Rename`; the field selects `note-<run>` and keeps `.txt`.
  Type a new stem and tap `Save`. Run `$V/ui.sh tap $SERIAL --text "Rename"`,
  `$V/ui.sh type $SERIAL "renamed-$RUN"`, `$V/ui.sh tap $SERIAL --text "Save"`.
  The row reads `renamed-<run>.txt`. Adopt the file id, then read its name
  back with `putio files list --parent-id <root id>`.
- **Move.** Open the sheet, tap `Move`, open `Target folder`, confirm. The
  row leaves this folder; `putio files list --parent-id <target id>` lists
  it. Adopt the id.
- **Copy.** Open the sheet, tap `Make a copy`, pick the run's root. The
  snackbar reads `Copied "<name>" to <folder>.` Adopt the copy's id.
- **Trash.** Open the sheet on `Inner folder`, tap `Move to trash`. The
  snackbar reads `Moved to Trash` and the row leaves. Remove that one id
  from Trash:
  `PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation trash.delete --args '[{"file_ids":[<id>]}]' --execute`,
  then check `trash.list` no longer holds it.
- **Proof.** Capture before and after each step, plus each CLI read-back.

## Gotchas

- Rename, Move and Copy change the account. Adopt every touched id before
  cleanup, or cleanup leaves it behind as unrecognised.
- A trashed item reads 404 through `files.get`; cleanup cannot see it. Delete
  it from Trash by id; never empty Trash.
- Mark as watched needs the account's resume setting on and a known length:
  use an uploaded video that put.io has finished processing. A text file has
  no watched action.
- Open in VLC needs VLC on the emulator and the account's download token;
  without them a snackbar says so. That snackbar is the expected end state
  on a fresh AVD.
- A Search result's sheet leaves out Rename, Move, Make a copy and delete.
- Select needs selection mode: see [Files selection](./files-selection.md).
