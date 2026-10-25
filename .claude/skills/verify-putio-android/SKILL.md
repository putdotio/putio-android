---
name: verify-putio-android
description: Drive the put.io Android mobile app (`:mobile`, production debug) on a zebroid-devbox emulator signed in as devs-auto, and capture proof. Use to prove a mobile UI, navigation, playback or live-API change in the real app, or to check that a feature in features/ still works.
---

# Verify putio-android

The surface is the phone and tablet app, `:mobile` in its production debug
build (`io.put.putio.mobile.debug`). Android TV (`:tv`) is a second surface
with its own D-pad lanes in [Harness](../../../docs/harness.md#tv-lanes); this
skill does not map it yet.

Every Gradle build and every emulator runs on `zebroid-devbox`, never on the
laptop. The laptop edits and calls [`devbox.sh`](scripts/devbox.sh); each
devbox command is one `devbox.sh run`. One run owns one devbox copy, one
emulator instance and one evidence directory, named `<run>`. Other emulators
and the attached phones on the devbox belong to someone else: address
everything with `-s <serial>` and stop only the instance `session.sh`
recorded.

Read [features/README.md](features/README.md) before driving, then the
feature file that matches the claim.

## Launch

On the laptop, in this worktree:

```bash
S=.claude/skills/verify-putio-android/scripts
RUN="verify-$(date -u +%Y%m%d-%H%M%S)"
$S/devbox.sh sync "$RUN"         # copy, sdk.dir, git init, brand fonts; prints the revision
$S/devbox.sh run "$RUN" './gradlew --max-workers=6 :mobile:assembleProductionDebug'
$S/devbox.sh run "$RUN" '$V/session.sh up $RUN --snapshot'  # prints the serial, e.g. emulator-5556
$S/devbox.sh run "$RUN" '$V/session.sh install $RUN'
$S/devbox.sh run "$RUN" './scripts/sign-in-devs-auto.sh $SERIAL'
$S/devbox.sh run "$RUN" '$V/session.sh relaunch $RUN'
```

Single quotes matter: `devbox.sh run` sets `$RUN`, `$V` (this skill's
`scripts/` in the copy) and `$SERIAL` (once `session.sh up` ran) on the
devbox side.

Two ways to boot:

- `--snapshot`, the default: a `-read-only` instance of the shared
  `putio-phone` AVD, loaded from its `devs-auto-signed-in` snapshot on a free
  port. It starts signed in, so the sign-in script passes with no approval.
  Every instance shares that one grant: never sign out in a snapshot run.
- `--fresh`: a new AVD named `<run>`, signed out. The sign-in script shows
  the debug-only device-code screen and approves it with
  `PUTIO_CLI_PROFILE=devs-auto putio auth approve <code>`: no password, no
  browser, one new grant. Use it for Welcome and sign-out recipes, or when
  the snapshot is missing.

- Ready means the sign-in script printed `SIGNED IN <account id>` and
  `PROOF PASS sign-in-devs-auto`: the app holds a devs-auto session and shows
  Files. Its `--help` lists failures and fixes.
- `Device sign-in stopped: app is ValidationUnavailable` means the restored
  app could not reach put.io to check its session yet. `session.sh up` waits
  for a validated network to make this rare; run the script once more.
- Every `putio-phone` instance runs `-read-only`, ours included; boot that
  AVD only through `session.sh up --snapshot`. A snapshot loads only with the
  renderer that saved it (`-gpu swiftshader_indirect`).
- After a snapshot load the app's window can composite black: the status bar
  draws, the app does not, and `capture.sh` rejects the screenshot as
  near-black. `session.sh relaunch` restarts the app, which draws it again;
  it is the last Launch step for that reason.
- Run one Gradle invocation at a time per run. Other agents share the devbox.
- After a code change: `devbox.sh sync`, assemble, then `session.sh install`.
  `install -r` keeps the session, so skip sign-in; Doctor confirms it.
- Keep `connectedAndroidTest` and `scripts/prove.sh` out of an authenticated
  run: both uninstall the app and its session.

## Doctor

Run it first, and again whenever anything looks off:

```bash
$S/devbox.sh run "$RUN" '$V/doctor.sh $RUN'
```

`DOCTOR PASS` means the serial is up and is still the instance `run.env`
records (AVD, and the emulator pid for snapshot runs), API 37, the installed
APK is byte-identical to this copy's build, the CLI is devs-auto, and the app
shows the signed-in five-tab shell. Each `FAIL` line names its fix. It taps
nothing. For the session's account id, rerun the sign-in script: it passes
with no approval when the app is already devs-auto.

## Drive

Android has no CDP. Drive the real app with adb through
[`ui.sh`](scripts/ui.sh), matching what the user sees: visible text and
content descriptions from `uiautomator`. Compose test tags are not in that
tree; they serve instrumentation tests only.

```bash
$S/devbox.sh run "$RUN" '$V/ui.sh wait $SERIAL --text "Search your files"'
$S/devbox.sh run "$RUN" '$V/ui.sh refresh $SERIAL'      # pull to refresh after creating fixtures
$S/devbox.sh run "$RUN" '$V/ui.sh scroll-to $SERIAL --text "android-verify-$RUN"'
$S/devbox.sh run "$RUN" '$V/ui.sh tap $SERIAL --text "android-verify-$RUN"'
$S/devbox.sh run "$RUN" '$V/ui.sh long-press $SERIAL --text "<name>"'
$S/devbox.sh run "$RUN" '$V/ui.sh link $SERIAL putio://transfers'
```

Handles, most stable first:

1. Deep links: `putio://{files,transfers,search,history,trash,downloads}`,
   `putio://transfers/add` (an empty Add transfer sheet),
   `putio://downloads/<id>` and `putio://downloads/<id>/play`, and
   `https://app.put.io/{files,files/<id>,transfers,search,history,trash}`.
   `ui.sh link` passes the package, so the https links skip the app chooser.
   A link opened while signed out waits for sign-in.
2. Content descriptions: `Actions for <name>` on a row's overflow,
   `More options`, `Open player`, `Stop playback`, `Back`.
3. Visible text: tab labels (`Files`, `History`, `Transfers`, `Downloads`,
   `Account`), button labels, a row's full name. A row exposes the full name
   even when the screen cuts it in the middle.

Use `wait` and `gone` after each action instead of a fixed sleep. When a tap
does nothing, the node was not the clickable one: tap its label text.

Live data comes from [`fixture.sh`](scripts/fixture.sh) on devs-auto, under
[Live API proof](../../../docs/harness.md#live-api-proof-putio-cli): one
uniquely named root container per run, a ledger line before each write, and
cleanup of ledger ids only. Trash stays as it was.

```bash
$S/devbox.sh run "$RUN" '$V/fixture.sh root $RUN'      # creates android-verify-<run>; prints its id
$S/devbox.sh run "$RUN" '$V/fixture.sh mkdir $RUN <parent-id> "Inner folder"'
$S/devbox.sh run "$RUN" '$V/fixture.sh upload $RUN <parent-id> <devbox path> ["name"]'
$S/devbox.sh run "$RUN" '$V/fixture.sh adopt $RUN <id>' # after the app creates, renames or moves an item
$S/devbox.sh run "$RUN" '$V/fixture.sh owned $RUN'     # id, kind, name per owned item
```

Adopt every item the app creates or changes before cleanup. Cleanup checks
each id's name and parent against the ledger and leaves a mismatch alone.

Instrumentation lanes in [Harness](../../../docs/harness.md#device-lanes)
cover what adb cannot drive well (player internals, TalkBack, drag and drop).
Most make no API calls: report them as synthetic proof.

## Evidence

```bash
$S/devbox.sh run "$RUN" '$V/capture.sh $RUN <label> [--allow-dark]'
```

Each capture writes `<stamp>-<label>.png`, `.xml` (the uiautomator tree) and
`.logcat.txt` (logcat for the app's uid only) to `.evidence/<run>/` in the
devbox copy. A near-black or corrupt screenshot fails; pass `--allow-dark`
for video. A file holding a token-shaped value is deleted and the capture
fails.

Proof standards:

- Drive the user path: taps, keys and links. Instrumentation setters and
  test-only hosts are synthetic proof; label them so.
- Capture the action and the state it produces: a `before` capture, the
  action, then an `after` capture.
- Check side effects outside the UI. After a rename, move, delete or
  transfer, read the account back with
  `PUTIO_CLI_PROFILE=devs-auto putio files list --parent-id <id> --output json`.
- A run on faked repositories proves layout, not the API.
- Look at every screenshot before you cite it. `.evidence/` is gitignored:
  attach captures with `gh pr comment <n> --attach <file>`.

## Cleanup

In this order, also after a failed attempt:

```bash
$S/devbox.sh run "$RUN" '$V/session.sh down $RUN'     # force-stop the app, stop this instance (and delete AVD <run> if --fresh)
$S/devbox.sh run "$RUN" '$V/fixture.sh cleanup $RUN'  # owned ids, leaves first, 404 confirmed
$S/devbox.sh pull "$RUN"                              # devbox .evidence/<run>/ -> laptop .evidence/<run>/
$S/devbox.sh remove "$RUN"                            # delete the devbox copy
ls .evidence/"$RUN"/                                  # the proof survives here
```

- Stop the emulator before fixture cleanup, so the app is not mid-request.
- `fixture.sh cleanup` stops on an unknown outcome. Reconcile that id
  read-only; never repeat the write.
- `remove` refuses while `run.env` says the emulator is up.
- The laptop's `.evidence/<run>/` keeps the captures, `fixtures.jsonl`,
  `run.env` and `revision.txt`. Cleanup leaves it alone.

## Helpers

All in [`scripts/`](scripts/); each prints its usage when run without
arguments.

| Script       | Host   | Use                                                                                                |
| ------------ | ------ | -------------------------------------------------------------------------------------------------- |
| `devbox.sh`  | laptop | `sync`, `run '<cmd>'`, `pull`, `remove` for `<run>`; returns the remote exit status                |
| `session.sh` | devbox | `up --snapshot` or `up --fresh`, `install`, `relaunch`, `down`                                     |
| `doctor.sh`  | devbox | read-only health check                                                                             |
| `ui.sh`      | devbox | `dump`, `find`, `tap`, `long-press`, `wait`, `gone`, `scroll-to`, `refresh`, `type`, `link`, `key` |
| `capture.sh` | devbox | screenshot, uiautomator XML and app logcat into `.evidence/<run>/`; secret scan                    |
| `fixture.sh` | devbox | `root`, `mkdir`, `upload`, `adopt`, `owned`, `cleanup` on devs-auto, with the ledger               |

`devbox.sh` carries the status in-band because Tailscale SSH to a Mac always
exits 0: a bare `ssh zebroid-devbox` reports success for a failed command.
Interrupting the laptop side can leave the remote command running; run
Doctor before the next step.
