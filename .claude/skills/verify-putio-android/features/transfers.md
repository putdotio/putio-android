# Transfers

## Drive

Preconditions:

- Doctor passes. The run owns `android-verify-<run>` as the Save to target.
- A small, stable public HTTP file that is not `text/html`, which put.io
  refuses: `https://raw.githubusercontent.com/putdotio/putio-android/main/LICENSE`
  works. After Add, find the run's
  transfer id in `putio transfers list` by its URL and `save_parent_id`; it
  is the run's to remove.

- **List.** Run `$S/ui.sh link $SERIAL putio://transfers` and
  `$S/ui.sh wait $SERIAL --text "Transfers"`. The title and summary show,
  or `Nothing in the queue.` on an empty account. Capture `transfers-list`.
- **Open Add.** Tap `Add transfer`. Run
  `$S/ui.sh tap $SERIAL --desc "Add transfer"`. A sheet with
  `URLs or magnet links` and `Save to` opens.
- **Save to.** Tap `Change` and pick `android-verify-<run>`; tap
  `Save here`. The sheet names the folder.
- **Add.** Run `$S/fixture.sh intend $RUN "transfer <url>"`. Focus the field,
  type the URL, and tap the button, which now reads `Add 1`. Run
  `$S/ui.sh tap $SERIAL --desc "URLs or magnet links"`,
  `$S/ui.sh type $SERIAL "<url>"` and `$S/ui.sh tap $SERIAL --text "Add 1"`.
  The snackbar reads `1 transfer added` and a card shows in Active, then
  Completed. `Completed` sits below `Needs attention`, which on the shared
  queue can be long: run `$S/ui.sh scroll-to $SERIAL --text "<file name>" 20`.
  Capture `transfers-add-url`.
- **Side effect.** Read the transfer back with
  `PUTIO_CLI_PROFILE=devs-auto putio transfers list --output json`: its
  `save_parent_id` is the run's root. Adopt the file it saved, then settle.
- **Magnet.** Open the link. Run
  `$S/ui.sh link $SERIAL "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Harbor%20film"`.
  The sheet shows that link and adds nothing until `Add 1`. Press Back.
- **Actions.** Long-press the run's card by its file name (`Transfer actions`
  is only an action label, and a tap on a completed card opens its file).
  The queue is shared, so first count the transfers with that name in
  `putio transfers list --output json`; the screen shows only some cards. Go
  on only at exactly 1; otherwise skip this step and report it, since the
  long-press takes the first match. Run `$S/ui.sh long-press $SERIAL --text "<file name>"`, then tap
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
- Retry on a failed transfer is covered by `MobileTransferRetryProofTest`
  over faked SDK calls; it is synthetic.
- Polling updates cards every few seconds; wait for the end state, not a
  fixed time.
