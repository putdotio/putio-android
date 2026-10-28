# Account and sign-out

## Drive

Preconditions:

- Doctor passes.
- Read the account's settings first:
  `PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation account.getSettings --execute --output json`.

- **Open.** Tap `Account`. Run `$S/ui.sh tap $SERIAL --text "Account"` and
  `$S/ui.sh wait $SERIAL --text "Storage"`. The header reads `devs-auto` and
  a `<used> of <total>` line. Capture `account-header`.
- **About.** Scroll to `App info` and open it. Run
  `$S/ui.sh scroll-to $SERIAL --text "App info"` and
  `$S/ui.sh tap $SERIAL --text "App info"`. `Version` and
  `Production, debug build` show.
- **Setting.** Toggle `Show subtitles`, then read `hide_subtitles` back with
  `account.getSettings`: it flipped. Toggle it back and read again. App
  config switches such as `Autoplay next video` have no CLI read-back (the
  CLI refuses `config.*` key reads); prove those only by force-stopping,
  relaunching and seeing the switch keep its state.
- **Product analytics prompt.** Debug builds show it only on a launch that
  opts in, and then whatever devs-auto holds. Run
  `adb -s $SERIAL shell am start -W -S -n io.put.putio.mobile.debug/io.putdotio.android.MainActivity --ez putio.harness.analytics_prompt true`
  and `$S/ui.sh wait $SERIAL --text "Turn on product analytics?"`. Capture
  `analytics-prompt`. Close it with Back, never `Allow` or `Don’t allow`:
  either writes the shared account. `$S/ui.sh gone $SERIAL --text "Turn on product analytics?"`.
- **Trash.** Tap `Trash`. The list or its empty state shows. Leave it
  untouched.
- **Sign out.** Only on a `--fresh` run, and last. Tap `Sign out`; the dialog reads
  `Sign out of put.io?`, with `Remove downloads from this device` only
  while downloads are on the device. Confirm
  `Sign out`. Run `$S/ui.sh tap $SERIAL --text "Sign out"` twice and
  `$S/ui.sh wait $SERIAL --match "^Welcome"`. Capture `account-sign-out`.
- **Back in.** Only when another recipe follows: run
  `./scripts/sign-in-devs-auto.sh $SERIAL`, and sign out again before
  Cleanup.

## Gotchas

- Settings are the shared account's. Restore every value you change and read
  it back.
- Never confirm Restore all or Empty Trash: they act on the whole shared
  account. Trash recipes use owned fixtures only; `TrashRestoreUiProofTest`
  covers the bulk actions synthetically.
- Sign out revokes the session's grant. In a `--snapshot` run that grant is
  shared by every snapshot instance and the snapshot itself: signing out
  there signs out all of them. Sign out only in a `--fresh` run, whose
  device-code sign-in made its own grant, and sign out at its end.
- The `Sign out` label is both the row and the dialog's confirm button; tap
  the row, wait for `Sign out of put.io?`, then tap again.
