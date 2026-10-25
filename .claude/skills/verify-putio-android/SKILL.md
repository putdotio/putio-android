---
name: verify-putio-android
description: Drive the put.io Android mobile app (`:mobile`, production debug) on an emulator signed in as devs-auto, and capture proof. Use to prove a mobile UI, navigation, playback or live-API change in the real app, or to check that a feature in features/ still works.
---

# Verify putio-android

The surface is the phone and tablet app, `:mobile` in its production debug
build (`io.put.putio.mobile.debug`). Android TV (`:tv`) is a second surface
with D-pad input and its own `Tv*ProofTest` lanes in `tv/src/androidTest/`;
this skill does not map it yet. [AGENTS.md](../../../AGENTS.md#hazards) owns
the hazards this skill works inside: the shared account, shared emulators,
authenticated installs and evidence.

It needs a checkout set up with `./scripts/bootstrap.sh`, `lsof`, and the
`putio` CLI signed in as `putio auth login --profile devs-auto`. The scripts
find the SDK the way the repo's scripts do: `ANDROID_HOME`, then
`local.properties` `sdk.dir`. One run owns one emulator session and one
evidence directory, named `<run>`. Address everything with `-s <serial>` and
stop only what `session.sh` recorded.

Open the [feature file](#features) that matches the claim before driving.

## Launch

From the repository root:

```bash
S=.claude/skills/verify-putio-android/scripts
RUN="verify-$(date -u +%Y%m%d-%H%M%S)-$(python3 -c 'import secrets; print(secrets.token_hex(3))')"
export PUTIO_CLI_PROFILE=devs-auto PATH="$(bash -c '. scripts/lib.sh && resolve_sdk_root')/platform-tools:$PATH"
unset PUTIO_CLI_TOKEN                         # an ambient token would override the profile
./gradlew :mobile:assembleProductionDebug
SERIAL="$($S/session.sh up "$RUN" --fresh)"   # or --snapshot <name>, or --serial <serial>
$S/session.sh install "$RUN"
$S/session.sh relaunch "$RUN"                 # the app checks its restored session first
./scripts/sign-in-devs-auto.sh "$SERIAL"
$S/session.sh relaunch "$RUN"                 # redraws a window a snapshot left black
```

Recipes use `$S`, `$RUN`, `$SERIAL`, `adb` and `putio` as set above. In a
new shell, repeat the first four lines with the same `RUN` and read the serial
back: `SERIAL="$(sed -n 's/^SERIAL=//p' ".evidence/$RUN/run.env")"`.

Three ways to get an emulator:

- `--fresh`, the default: a new AVD named `<run>` from the `putio-phone`
  image, signed out; `session.sh down` deletes it. The sign-in script shows
  the debug-only device-code screen and approves it with
  `PUTIO_CLI_PROFILE=devs-auto putio auth approve <code>`: no password, no
  browser, one new grant. Sign out before `down` (see Cleanup).
- `--snapshot <name>`: a `-read-only` instance of `putio-phone`, loaded from
  its snapshot `<name>` on a free port. Saved signed in, it starts signed in
  and the sign-in script passes with no approval. Every instance shares that
  one grant: never sign out in a snapshot run.
- `--serial <serial>`: an emulator that already runs. `down` force-stops the
  app and leaves the emulator up. Steps that change device-wide state
  (airplane mode, Home, system settings) run only on an emulator you started.

- Ready means the sign-in script printed `SIGNED IN <account id>` and
  `PROOF PASS sign-in-devs-auto`: the app holds a devs-auto session and shows
  Files. Its `--help` lists failures and fixes.
- To save a snapshot, stop every `putio-phone` instance, boot it writable
  with `./scripts/emulator.sh boot phone --headless`, install, and sign in.
  Then run `adb -s <serial> emu avd snapshot save <name>` and
  `./scripts/emulator.sh stop <serial>`. A snapshot loads only with the
  renderer that saved it; both boots use `-gpu swiftshader_indirect`.
- For a minute or two after a snapshot load the app can fail to check its
  restored session: it shows `Couldn’t check your session` with `Try again`,
  and the sign-in script stops with
  `Device sign-in stopped: app is ValidationUnavailable`. The first
  `relaunch` waits that out, tapping `Try again`. If the script still stops
  there, relaunch and run it again.
- After a snapshot load the app's window can composite black: the status bar
  draws, the app does not, and `capture.sh` rejects the screenshot as
  near-black. `session.sh relaunch` restarts the app, which draws it again.
- After a code change: assemble, then `session.sh install`. `install -r`
  keeps the session, so skip sign-in; Doctor confirms it.

## Doctor

Run it first, and again whenever anything looks off:

```bash
$S/doctor.sh "$RUN"
```

Continue only on `DOCTOR PASS`; each `FAIL` line names its fix. For the
session's account id, rerun the sign-in script: it passes with no approval
when the app is already devs-auto.

## Drive

Android has no CDP. Drive the real app with adb through
[`ui.sh`](scripts/ui.sh), matching what the user sees: visible text and
content descriptions from `uiautomator`. Compose test tags are not in that
tree; they serve instrumentation tests only.

```bash
$S/ui.sh wait $SERIAL --text "Search your files"
$S/ui.sh refresh $SERIAL                      # pull to refresh after creating fixtures
$S/ui.sh scroll-to $SERIAL --text "android-verify-$RUN"
$S/ui.sh tap $SERIAL --text "android-verify-$RUN"
$S/ui.sh long-press $SERIAL --text "<name>"
$S/ui.sh link $SERIAL putio://transfers
```

Handles, most stable first:

1. Deep links: `putio://{files,transfers,search,history,trash,downloads}`,
   `putio://transfers/add` (an empty Add transfer sheet),
   `putio://downloads/<id>` and `putio://downloads/<id>/play`, and
   `https://app.put.io/{files,files/<id>,transfers,search,history,trash}`.
   `ui.sh link` passes the package, so the https links skip the app chooser.
   A link opened while signed out waits for sign-in.
2. Content descriptions: `Actions for <name>` on a row's overflow,
   `More options`, `Sort files`, `Stop playback`, `Back`. Click labels such
   as the mini player's `Open player` are not in the tree; tap the node's
   visible text instead.
3. Visible text: tab labels (`Files`, `History`, `Transfers`, `Downloads`,
   `Account`), button labels, a row's full name. A row exposes the full name
   even when the screen cuts it in the middle.

Start each recipe from the Files root (`putio://files`). Follow every action
with `wait` or `gone` on the state it should produce. When a tap does nothing,
the node was not the clickable one: tap its label text.

Live data comes from [`fixture.sh`](scripts/fixture.sh) on devs-auto: one
uniquely named root container per run, a ledger line before each write, and
cleanup of ledger ids only. Trash stays as it was: deletes use `skip_trash`,
and an item the app trashes is removed from Trash by its id.

Put every fixture inside the run's root, and write local fixture files under
`build/`.

```bash
$S/fixture.sh root $RUN                       # creates android-verify-<run>; prints its id
$S/fixture.sh mkdir $RUN <parent-id> "Inner folder"
$S/fixture.sh upload $RUN <parent-id> <path> ["name"]
$S/fixture.sh intend $RUN "rename <id>"       # before a UI action that changes the account
$S/fixture.sh adopt $RUN <id>                 # after it: each item the app created, renamed or moved
$S/fixture.sh settle $RUN "renamed"           # closes the intent once every item is adopted
$S/fixture.sh owned $RUN                      # id, kind, name per owned item
```

Every UI mutation is an `intend`, the action, an `adopt` per item it created
or changed, then one `settle`. While
an intent is open, the next write and cleanup refuse. Cleanup checks each
id's name and parent and each owned folder's contents against the ledger, and
deletes nothing on a mismatch.

Opt-in `*ProofTest` classes in `mobile/src/androidTest/` cover what adb
cannot drive well (player internals, TalkBack, drag and drop). Each reads its
`putio.<lane>.*` arguments through `InstrumentationRegistry.getArguments()`;
its class header says whether it makes API calls. Build and install the test
APK (`:mobile:assembleProductionDebugAndroidTest`, then `adb install -r`), and
run one class:

```bash
adb -s $SERIAL shell am instrument -w -r -e class io.putdotio.android.<Class> \
  -e putio.<lane>.enabled true -e putio.<lane>.runId "$(python3 -c 'import uuid; print(uuid.uuid4())')" \
  io.put.putio.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Add every other `putio.<lane>.*` key the class reads; media lanes take file
paths (`.fixture`, `.video`, `.audio`). Put media under
`/sdcard/Android/data/io.put.putio.mobile.debug/files/`: that directory exists
only once the app made it, so run the lane once, let it fail on the missing
file, then `adb push`. Creating it with `adb shell mkdir` leaves it unreadable
to the app. Require `OK (n tests)`. Most lanes fake the API: report them as
synthetic proof.

## Evidence

```bash
$S/capture.sh $RUN <label> [--allow-dark]
```

Each capture writes `<stamp>-<label>.png`, `.xml` (the uiautomator tree) and
`.logcat.txt` (logcat for the app's uid only) to `.evidence/<run>/`. A near-black or corrupt screenshot fails; pass `--allow-dark`
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
- Restore every account and device setting a recipe changes.
- Look at every screenshot before you cite it.
- Report a path you could not reach with the failing command and the missing
  precondition, and a path you drove another way as unverified.

## Cleanup

In this order, also after a failed attempt. A `--fresh` run first signs out
under Account ([Account and sign-out](features/account-and-sign-out.md)) while
online. Revocation runs in the background after Welcome shows and retries
after 15 s, then a minute; deleting the AVD drops a pending retry. Before
`session.sh down`, wait until the app holds no pending revocation:

```bash
until ! adb -s $SERIAL shell run-as io.put.putio.mobile.debug cat shared_prefs/putio_auth.xml | grep -q pending_revocation_token; do sleep 5; done
```

If it stays pending for several minutes, keep the run up and report the
grant. A `--snapshot` run never signs out.

```bash
$S/session.sh down "$RUN"     # force-stop the app; stop the emulator up booted; delete AVD <run> if --fresh
$S/fixture.sh cleanup "$RUN"  # owned ids, deepest first, 404 confirmed
```

- Stop the emulator before fixture cleanup, so the app is not mid-request.
- `fixture.sh cleanup` stops at an open intent or an unknown outcome.
  Reconcile that id read-only, `adopt` what exists, then `settle`; never
  repeat the write.
- `.evidence/<run>/` keeps the captures, `fixtures.jsonl`, `run.env` and
  `revision.txt`. Cleanup leaves it alone.

## Features

Each file lists preconditions, drive steps with the expected end state, and
gotchas. A run passes only when the device shows that end state.

- [Welcome and sign-in](features/welcome-and-sign-in.md)
- [Files root and folders](features/files-root-and-folders.md)
- [Search](features/search.md)
- [Item actions](features/item-actions.md)
- [Files selection](features/files-selection.md)
- [Transfers](features/transfers.md)
- [Downloads and offline](features/downloads-and-offline.md)
- [Video player](features/video-player.md)
- [Audio player](features/audio-player.md)
- [Account and sign-out](features/account-and-sign-out.md)
- [Tablet list-detail and viewers](features/tablet-list-detail-and-viewers.md)

Each script in [`scripts/`](scripts/) prints its usage when run without
arguments.
