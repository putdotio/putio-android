# Search

## Drive

Preconditions:

- Doctor passes; Files root on screen.
- The run's root holds `Inner folder` and a text file whose name contains the
  run name, so the term is unique to this run.
- Recent searches is the shared account's list and keeps five terms; a
  submitted term pushes the oldest out for good. Open Search and count the
  `Remove … from recent searches` chips first. With five, skip every
  submitted step (Enter, chip replays, opening a result) and report
  `search-recent` unreachable; search by typing alone, which keeps nothing.

- **Open.** Tap the pill. Run
  `$S/ui.sh tap $SERIAL --text "Search your files"`. The field takes focus
  and the chips `All`, `Folders`, `Videos`, `Audio`, `Subtitles` show.
- **Account match.** Type the run name and submit (Enter only when fewer
  than five recent terms show). Run
  `$S/ui.sh type $SERIAL "$RUN"` and `$S/ui.sh key $SERIAL ENTER`, then
  `$S/ui.sh wait $SERIAL --text "android-verify-$RUN"`. The result groups
  read `Folders` and `Files`, and each result names its parent folder.
- **Proof.** Run `$S/capture.sh $RUN search-account`.
- **Chips.** Tap `Folders`. Run `$S/ui.sh tap $SERIAL --text "Folders"`. Only
  folders remain and the search runs again at once.
- **Open result.** Tap the run's folder. The folder opens in Files; Back
  returns to the results with their scroll.
- **Folder scope.** In `android-verify-<run>`, tap the bar's search button.
  Run `$S/ui.sh tap $SERIAL --desc "Search in android-verify-$RUN"` and
  `$S/ui.sh type $SERIAL "Inner"`. Only `Inner folder` shows. Back closes
  the keyboard, then leaves Search, then the folder.
- **Recent.** Clear the field with `Clear search`. Run
  `$S/ui.sh tap $SERIAL --desc "Clear search"`. `Recent searches` lists the
  submitted run name; tapping it searches again. Remove it with its
  `Remove <term> from recent searches`.
- **No results.** Type `zz-no-such-$RUN` without Enter, so it is not kept.
  Run `$S/ui.sh type $SERIAL "zz-no-such-$RUN"` and
  `$S/ui.sh wait $SERIAL --text "Nothing matches."`. The message names the
  term and the chips hide.
- **Link entry.** From the Files root run
  `$S/ui.sh link $SERIAL putio://search`. Search opens without the keyboard
  and keeps the last term typed in this app session.

## Gotchas

- put.io indexes new items after a short delay; a fixture made seconds ago
  can be missing. Wait and search again instead of weakening the term.
- Recent searches are the account's shared list (`/config` `searchHistory`,
  five at most), also read by TV and web, and the CLI cannot read or restore
  it. Remove the terms you added with the chip's
  `Remove <term> from recent searches`; a term pushed out cannot come back.
- A search that runs because typing paused is not kept in Recent searches;
  submit with Enter.
- `Subtitles` filters names on the device; its first page may load more
  before showing anything.
