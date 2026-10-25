# putio-android verification map

This directory is the maintained source for verifying the user-facing
behaviour of the put.io mobile app. Read this index, then use the matching
feature file as the recipe. Each file states the end state a user sees; a run
passes only when the device shows it.

## Baseline preconditions

- Launch, sign in and pass Doctor as in [SKILL.md](../SKILL.md): the run's
  own emulator on zebroid-devbox (`--snapshot` unless the recipe says
  `--fresh`), production debug build of this copy, signed in as devs-auto,
  five-tab shell on screen.
- Create the run's root container with `fixture.sh root <run>`. Its name is
  `android-verify-<run>`; put every other fixture inside it.
- Drive only the serial `session.sh up` printed for this run.

## Driving conventions

- Every command in a recipe runs in the devbox copy. Wrap it as
  `$S/devbox.sh run "$RUN" '<command>'`, single-quoted, so `$V`, `$SERIAL`
  and `$RUN` expand on the devbox. A command that holds single quotes itself
  (JSON arguments) goes in double quotes instead, with each devbox-side `$`
  escaped as `\$`.
- Start each recipe from the Files root: `$V/ui.sh link $SERIAL putio://files`.
- Select nodes by what the user sees: `--text` for a label, `--desc` for an
  icon's content description, `--match` for a pattern. Compose test tags are
  not in the uiautomator tree.
- Follow every action with `ui.sh wait` or `ui.sh gone` on the state it
  should produce.
- Adopt every item the app creates, renames or moves:
  `$V/fixture.sh adopt $RUN <id>`. Read ids back with
  `PUTIO_CLI_PROFILE=devs-auto putio files list --parent-id <id> --output json`.
- Restore every account setting and device setting a recipe changes.

## Proof and skip reporting

- Capture before and after each action:
  `$V/capture.sh $RUN <feature-id>-<step>`. Each capture is a screenshot, the
  uiautomator XML and the app's logcat.
- A mutation's proof includes a CLI read-back of the account.
- Name the feature id and the entry point used with every artifact.
- Report a path you could not reach with the command that failed and the
  missing precondition. Report a path you drove another way as unverified.
- Label instrumentation lanes over faked repositories as synthetic.

## Feature entry contract

Each feature file starts with an H1 title and one paragraph describing the
user-visible behaviour. It then uses exactly four H2 sections in this order.

1. `Sub-features` lists short ids, one line each.
2. `How to get to it (user POV)` lists every user entry point.
3. `Driving it with verify-putio-android` starts with `Preconditions:` and
   uses labelled bullets that pair each user action with an exact command and
   the observable result.
4. `Gotchas` lists traps that waste or invalidate a run.

Keep implementation details out of the map: user paths, stable handles,
required state, commands and observable proof only.

## Features

- [Welcome and sign-in](./welcome-and-sign-in.md): the signed-out screen, the
  harness's device-code sign-in, and session restore.
- [Files root and folders](./files-root-and-folders.md): the root listing,
  folders, sort, New folder, Continue watching and offline.
- [Search](./search.md): the search pill, folder scope, type chips, recent
  searches and no results.
- [Item actions](./item-actions.md): the actions sheet, Rename, Move, Make a
  copy, watched marks and Trash.
- [Files selection](./files-selection.md): selecting several items and acting
  on them together.
- [Transfers](./transfers.md): the list, Add transfer, Save to, and transfer
  actions.
- [Downloads and offline](./downloads-and-offline.md): downloading, the
  Downloads tab, and playing offline.
- [Video player](./video-player.md): fullscreen playback, resume, tracks,
  subtitles and speed.
- [Audio player](./audio-player.md): the mini player, Now Playing, skip and
  the sleep timer.
- [Account and sign-out](./account-and-sign-out.md): storage, settings,
  Trash, and signing out.
- [Tablet list-detail and viewers](./tablet-list-detail-and-viewers.md): the
  detail pane and the image, PDF and text viewers.
