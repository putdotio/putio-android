# Account and sign-out

## Drive

Preconditions:

- Doctor passes.
- Read the account's settings first:
  `PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation account.getSettings --execute --output json`.

- **Open.** `$S/ui.sh tap $SERIAL --text "Account"` and
  `$S/ui.sh wait $SERIAL --text "Storage"`. The header reads `devs-auto` and
  a `<used> of <total>` line. Capture `account-header`.
- **About.** `$S/ui.sh scroll-to $SERIAL --text "App info"` and
  `$S/ui.sh tap $SERIAL --text "App info"`. `Version` and
  `Production, debug build` show.
- **Setting.** Toggle `Show subtitles`, then read `hide_subtitles` back with
  `account.getSettings`: it flipped. Toggle it back and read again. App
  config switches such as `Autoplay next video` have no CLI read-back (the
  CLI refuses `config.*` key reads); prove those by force-stopping,
  relaunching and seeing the switch keep its state.
- **Product analytics prompt.** Debug builds show it only on a launch that
  opts in, and then whatever devs-auto holds. Run
  `adb -s $SERIAL shell am start -W -S -n io.put.putio.mobile.debug/io.putdotio.android.MainActivity --ez putio.harness.analytics_prompt true`
  and `$S/ui.sh wait $SERIAL --text "Turn on product analytics?"`. Capture
  `analytics-prompt`. Close it with Back, never `Allow` or `Don’t allow`:
  either writes the shared account. `$S/ui.sh gone $SERIAL --text "Turn on product analytics?"`.
- **Trash.** Tap `Trash`. The list or its empty state shows. Leave it
  untouched.
- **Sign out.** Only on a `--fresh` run, and last. The row and the dialog's
  confirm button share the label `Sign out`:
  `$S/ui.sh tap $SERIAL --text "Sign out"`, wait for the dialog
  `Sign out of put.io?`, then the same tap again and
  `$S/ui.sh wait $SERIAL --match "^Welcome"`. The dialog offers
  `Remove downloads from this device` only while downloads are on the
  device. Capture `account-sign-out`.
- **Back in.** Only when another recipe follows: run
  `./scripts/sign-in-devs-auto.sh $SERIAL`, and sign out again before
  Cleanup.

## Gotchas

- Settings are the shared account's. Restore every value you change and read
  it back.
- Never tap Restore all, which starts at once, or confirm Empty trash: they act
  on the whole shared account. Trash recipes use owned fixtures only; `TrashRestoreUiProofTest`
  covers the bulk actions synthetically.
- Sign out revokes the grant the session holds;
  [Cleanup](../SKILL.md#cleanup) says what the next run pays. Never sign out
  in a `--snapshot` run: every instance and the snapshot itself share that
  grant.
