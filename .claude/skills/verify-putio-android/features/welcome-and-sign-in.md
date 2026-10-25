# Welcome and sign-in

## Drive

Preconditions:

- A `--fresh` run (`$S/session.sh up "$RUN" --fresh`) whose app has no session:
  right after `session.sh install`. Never clear or sign out a `--snapshot`
  run; its session is shared.

- **Welcome.** Launch. Run
  `adb -s $SERIAL shell am start -n io.put.putio.mobile.debug/io.putdotio.android.MainActivity`
  and `$S/ui.sh wait $SERIAL --text "Welcome!"`. `Sign in` shows. Capture
  `welcome-screen`.
- **Browser.** Tap `Sign in`. Run `$S/ui.sh tap $SERIAL --text "Sign in"`
  and `$S/ui.sh gone $SERIAL --text "Welcome!"`. Chrome takes the screen.
  Press Back; the app returns to Welcome. Stop here: the browser path needs
  a password, which this skill never types.
- **Device code.** Run `./scripts/sign-in-devs-auto.sh $SERIAL`. It prints
  `SIGNED IN <account id>` and `PROOF PASS sign-in-devs-auto`, and Files
  shows. Capture `sign-in-device-code`.
- **Restore.** Force-stop and relaunch. Run
  `adb -s $SERIAL shell am force-stop io.put.putio.mobile.debug`, the
  `am start` above, and `$S/ui.sh wait $SERIAL --text "Search your files"`.
  Files shows with no Welcome in between.
- **Link waits.** Unreachable with this harness: the sign-in script starts
  its screen with `--activity-clear-task`, which drops a link MainActivity
  holds while signed out. Report `welcome-link-waits` as unverified, with
  that reason.

## Gotchas

- The device-code screen exists only in debug builds; a release build fails
  the script with a named error.
- `pm clear` drops the session but leaves its grant on the account. Sign out
  in the app instead, so the grant is revoked.
- The browser path cannot be completed without the account's password. Prove
  only that Chrome opens and that Back returns to Welcome.
- On a fresh AVD, Chrome shows its own first-run screen
  (`com.android.chrome/...FirstRunActivity` in
  `dumpsys activity activities`) instead of put.io's page. That still proves
  the hand-off; leave Chrome's setup alone.
- A `--fresh` boot checks that Chrome holds the browser role with the Auth
  Tab service; without it the boot fails before install.
