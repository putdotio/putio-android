# Account and sign-out

Account shows the user, storage used and free, and the account's settings:
playback, files, subtitles, privacy controls, Trash and public links, and App
info. Sign out asks first, can remove downloads from the device, and returns
to Welcome.

## Sub-features

- `account-header` shows the username and `Storage` with used and free.
- `account-settings` toggles a setting and keeps the server's value on a
  failed save.
- `account-trash` opens Trash from `Trash`.
- `account-public-links` lists exclusive links.
- `account-about` shows App info and `Copy for support`.
- `account-sign-out` confirms with `Sign out of put.io?`, optionally removes
  downloads, revokes the session and shows Welcome.

## How to get to it (user POV)

- Tap the `Account` tab.
- Open `putio://trash` for Trash.

## Driving it with verify-putio-android

Preconditions:

- Doctor passes.
- Read the account's settings first:
  `PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation account.getSettings --execute --output json`.

- **Open.** Tap `Account`. Run `$V/ui.sh tap $SERIAL --text "Account"` and
  `$V/ui.sh wait $SERIAL --text "Storage"`. The header reads `devs-auto` and
  a `<used> of <total>` line. Capture `account-header`.
- **About.** Scroll to `App info` and open it. Run
  `$V/ui.sh scroll-to $SERIAL --text "App info"` and
  `$V/ui.sh tap $SERIAL --text "App info"`. `Version` and
  `Production, debug build` show.
- **Setting.** Toggle `Autoplay next video`, then read the app config key
  back with
  `PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation config.getKey --args '["autoplay_next_video"]' --execute --output json`;
  toggle it back and read again.
- **Trash.** Tap `Trash`. The list or its empty state shows. Leave it
  untouched.
- **Sign out.** Only on a `--fresh` run, and last. Tap `Sign out`; the dialog reads
  `Sign out of put.io?` with `Remove downloads from this device`. Confirm
  `Sign out`. Run `$V/ui.sh tap $SERIAL --text "Sign out"` twice and
  `$V/ui.sh wait $SERIAL --match "^Welcome"`. Capture `account-sign-out`.
- **Back in.** Run `./scripts/sign-in-devs-auto.sh $SERIAL` to sign in for
  the next recipe.

## Gotchas

- Settings are the shared account's. Restore every value you change and read
  it back.
- Never confirm Restore all or Empty Trash: they act on the whole shared
  account. Trash recipes use owned fixtures only
  ([Harness](../../../../docs/harness.md#trash-bulk-actions-proof)).
- Sign out revokes the session's grant. In a `--snapshot` run that grant is
  shared by every snapshot instance and the snapshot itself: signing out
  there signs out all of them. Sign out only in a `--fresh` run, whose
  device-code sign-in made its own grant, and sign out at its end.
- The `Sign out` label is both the row and the dialog's confirm button; tap
  the row, wait for `Sign out of put.io?`, then tap again.
