# Transfers

Transfers lists the account's transfers in Active, Needs attention and
Completed sections under a summary of active and failed counts and speed. Add
transfer takes URLs or magnet links, or a `.torrent`, with a Save to folder;
each card's actions retry, stop or remove a transfer.

## Sub-features

- `transfers-list` shows the sections and the summary.
- `transfers-add-url` adds an HTTP URL into a chosen folder.
- `transfers-add-magnet` opens the sheet from a magnet link, unsent.
- `transfers-save-to` changes the destination with `Change`.
- `transfers-actions` offers Retry, Stop transfer and Remove from list.
- `transfers-refresh` refreshes from the bar.

## How to get to it (user POV)

- Tap the `Transfers` tab.
- Tap the `Add transfer` button in Transfers.
- Open `putio://transfers` or `putio://transfers/add`, or the launcher
  shortcut Add transfer.
- Share a link or open a `magnet:` link from another app.

## Driving it with verify-putio-android

Preconditions:

- Doctor passes. The run owns `android-verify-<run>` as the Save to target.
- A small, stable public HTTP file to fetch. After Add, find the run's
  transfer id in `putio transfers list` by its URL and `save_parent_id`; it
  is the run's to remove.

- **List.** Run `$V/ui.sh link $SERIAL putio://transfers` and
  `$V/ui.sh wait $SERIAL --text "Transfers"`. The title and summary show,
  or `Nothing in the queue.` on an empty account. Capture `transfers-list`.
- **Open Add.** Tap `Add transfer`. Run
  `$V/ui.sh tap $SERIAL --desc "Add transfer"`. A sheet with
  `URLs or magnet links` and `Save to` opens.
- **Save to.** Tap `Change` and pick `android-verify-<run>`; tap
  `Save here`. The sheet names the folder.
- **Add.** Run `$V/fixture.sh intend $RUN "transfer <url>"`. Focus the field,
  type the URL, and tap the button, which now reads `Add 1`. Run
  `$V/ui.sh tap $SERIAL --text "URLs or magnet links"`,
  `$V/ui.sh type $SERIAL "<url>"` and `$V/ui.sh tap $SERIAL --text "Add 1"`.
  The snackbar reads `1 transfer added` and a card shows in Active, then
  Completed. Capture `transfers-add-url`.
- **Side effect.** Read the transfer back with
  `PUTIO_CLI_PROFILE=devs-auto putio transfers list --output json`: its
  `save_parent_id` is the run's root. Adopt the file it saved.
- **Magnet.** Open the link. Run
  `$V/ui.sh link $SERIAL "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Harbor%20film"`.
  The sheet shows that link and adds nothing until `Add 1`. Press Back.
- **Actions.** Tap the run's card's `Transfer actions` and
  `Remove from list`. The card leaves and `putio transfers list` no longer
  holds its id.

## Gotchas

- Transfers is the shared account's queue: other harnesses' cards sit beside
  yours. Act only on the transfer id you created.
- A completed transfer leaves a file in the Save to folder. Adopt it, or
  cleanup refuses the folder's contents as unknown.
- The magnet above is a fake hash; never tap Add on it.
- `ui.sh link` quotes the URI for the device shell. A bare
  `adb shell am start -d` splits a magnet at `&`.
- The add button reads `Add` while the field is empty and `Add <n>` once it
  holds n links.
- Transfer Retry proof over faked SDK calls lives in
  [Harness](../../../../docs/harness.md#transfer-retry-proof); it is
  synthetic.
- Polling updates cards every few seconds; wait for the end state, not a
  fixed time.
