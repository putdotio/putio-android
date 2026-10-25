# Harness

Emulators, launch proof, device lanes, evidence, and live API proof. The
[Agent Guide](../AGENTS.md) owns setup, build, verify, and the proof map;
[Behaviour](./behavior.md) owns the product rules these lanes exercise.

## Emulators

Bootstrap creates the reusable `putio-phone` (API 37) and `putio-tv` (API 36)
AVDs, plus the opt-in `putio-google-tv` for the Google TV launcher and Play
surfaces. [scripts/lib.sh](../scripts/lib.sh) (`avd_name_for`, `image_for`,
`device_for`) owns their device profiles and system images.

```bash
./scripts/emulator.sh boot phone --headless   # prints the serial; reuses a running one
./scripts/emulator.sh status
./scripts/emulator.sh stop phone              # or stop emulator-5554
./scripts/emulator.sh create phone --name <name>   # an extra AVD you own; boot/stop/delete take --name too
./scripts/bootstrap.sh --google-tv            # adds the Google TV image and AVD
./scripts/emulator.sh boot google-tv --headless
```

A fresh `putio-google-tv` boots into Google TV setup; finish or cancel it
before launcher proof. `scripts/prove.sh tv` targets `putio-tv`; install on the
Google TV emulator with `adb -s <serial> install` for manual proof.

Lifecycle contract, enforced by the scripts and proven by
`scripts/test-lifecycle.sh`: a flow stops only the exact serial it booted, on
completion, failure, SIGINT, and SIGTERM, and confirms it is gone from
`adb devices` before returning. Preexisting emulators are reused, never
stopped; AVD registrations are deleted only if the flow created them with
`--ephemeral`. No process-name or blanket emulator cleanup, ever. Failed
lifecycle runs keep their case logs under `.evidence/logs/lifecycle.*`;
forced-failure cases require an `INJECTED_FAILURE <stage>` marker, so an
unrelated failure cannot satisfy them.

Every phone boot, reuse included, verifies API 37, selects the bundled Chrome
as the browser role holder, and requires its AndroidX Auth Tab service
category; without that secure OAuth transport the harness fails before
install. The CI managed device (`ciPhone`) is also API 37.

Bootstrap never replaces a mismatched AVD: it provisions the other profiles,
then fails with an explicit command; stop and delete that exact profile
yourself before rerunning. The harness owns only `putio-phone`, `putio-tv`,
`putio-google-tv` and the `--ephemeral` AVDs it creates; other AVDs are left
alone, even when `avdmanager list avd` reports them as unloadable.

Headless and shared hosts:

- Every boot uses `-no-snapshot -no-boot-anim -no-audio`; `--headless` adds
  `-no-window -gpu swiftshader_indirect`, because host-GPU headless rendering
  intermittently composites app windows black and fails the pixel assertion.
  `screencap` and `screenrecord` work without a window.
- The first boot of a fresh AVD takes about a minute on Apple Silicon; later
  boots are faster.
- Cold headless boots often ANR the emulator's own `com.android.systemui`, so
  boots set `hide_error_dialogs 1` and that dialog cannot sit over captures;
  app crashes and ANRs still fail instrumented proof.
- macOS needs Hypervisor.framework (default on Apple Silicon); Linux needs KVM
  (`emulator -accel-check`).
- The scripts scan console ports 5554–5584; check `scripts/emulator.sh status`
  before assuming one is free.

## Launch proof

```bash
./scripts/prove.sh mobile            # build → boot → install → launch → verify → screenshot → teardown
./scripts/prove.sh tv --record       # also captures a screen recording
```

The [prove.sh header](../scripts/prove.sh) owns flags, exit codes and the
`BOOTED`, `EVIDENCE` and `PROOF PASS|FAIL` stdout markers. Verification runs
only `LaunchSmokeTest`, through `:mobile:verifyLaunchProof` or
`:tv:verifyLaunchProof`: RESUMED state, a 3 s stability window, and real
composited pixels (mean luma); a crash or ANR fails it. The task also requires
the smoke test's successful XML result, so an empty, skipped, or missing result
fails even when instrumentation exits 0. One automatic retry covers the
cold-boot flake where the emulator composites the app window black for the
first minute.

`--record` runs after verification: the app is force-stopped and relaunched
under capture, so the clip has frames (`screenrecord` drops static screens) and
shows a real cold launch. A publication gate trims sustained frozen lead and
tail, keeping short context; a fully static recording is quarantined as
`*.mp4.idle` (`scripts/evidence.sh record --keep-idle` keeps one for an
intentional timing demo). Published recordings are lossy re-encodes of the
trimmed range. A follow-up gated screenshot confirms the relaunch rendered.

The connected suite uninstalls the tested app afterwards and `prove.sh`
reinstalls it for capture, so authenticate after launch proof and never run
`connectedAndroidTest` or `prove.sh` between authenticated steps.

`domain/auth` and `domain/transfers` keep their device tests in their own test
APKs (`KeystoreAuthTokenStoreInstrumentedTest`, `TransfersPollingCpuBenchmark`).
`ANDROID_SERIAL=<serial> ./gradlew :domain:auth:connectedDebugAndroidTest`
installs and removes only that test APK, so it is safe beside an authenticated
app; its tests use their own preferences and Keystore aliases.

## Device lanes

Every opt-in lane below runs the same way. It never uses
`connectedAndroidTest` or `prove.sh`, so an installed session survives.

1. Boot the emulator (API 37 phone or `putio-tv`), then build and install the
   app and its instrumentation APK:

   ```bash
   ./gradlew :mobile:assembleProductionDebug :mobile:assembleProductionDebugAndroidTest
   adb -s emulator-5554 install -r mobile/build/outputs/apk/production/debug/mobile-production-debug.apk
   adb -s emulator-5554 install -r mobile/build/outputs/apk/androidTest/production/debug/mobile-production-debug-androidTest.apk
   ```

   For TV, replace `mobile` with `tv` in the task and both paths.

2. Put media fixtures under the app's external files directory
   (`/sdcard/Android/data/<app id>/files/`). On a fresh install that directory
   exists only once the app creates it: run the instrumentation once, let it
   fail on the missing fixture, then push. Never create it with
   `adb shell mkdir`; a shell-owned directory is unreadable to the app. Lanes
   that say so take their fixture from `/data/local/tmp` and copy it in.

3. Run the named class or `Class#method` with the lane's opt-in flag and a
   fresh run id, bounded by your own timeout. Each lane's arguments share a
   prefix: `<prefix>.enabled=true`, `<prefix>.runId=<UUID>`, plus any fixture
   arguments it lists.

   ```bash
   adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.MobileTransferRetryProofTest \
     -e putio.transfers.enabled true -e putio.transfers.runId "$(uuidgen | tr A-Z a-z)" \
     io.put.putio.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner
   ```

   | App    | App id                      | Runner package                   | Test package                 |
   | ------ | --------------------------- | -------------------------------- | ---------------------------- |
   | mobile | `io.put.putio.mobile.debug` | `io.put.putio.mobile.debug.test` | `io.putdotio.android[.*]`    |
   | tv     | `io.put.putio.debug`        | `io.put.putio.debug.test`        | `io.putdotio.android.tv[.*]` |

   Require the lane's `OK (n tests)`.

4. Screenshots land in `<lane>-proof-<UUID>/` under the app's external files
   directory; pull them into `.evidence/` and inspect them. To record, start
   `scripts/evidence.sh record` (with `--allow-dark` for playback) just before
   the instrumentation. Publish as in [Capture and publish](#capture-and-publish).

5. Clean up: remove only the fixtures and run directories you created, restore
   every device setting the lane changed, and stop only an emulator you booted.

Report a lane that makes no API calls as synthetic (or controlled-state)
proof, not live proof. A lane that drives Home, the launcher, or system
settings acts on the whole device; run it on an emulator you booted with no
other proof running.

Fixture recipes the lanes reuse:

```bash
# Landscape MP4 with one audio track; change size or duration as a lane asks.
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=90 -f lavfi -i sine=frequency=440:duration=90 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest fixture.mp4
# SubRip captions, one cue every 3 s.
python3 -c 'for i in range(30): t = lambda v: f"00:{v // 60:02d}:{v % 60:02d},000"; print(f"{i + 1}\n{t(i * 3)} --> {t(i * 3 + 3)}\nProof caption {i + 1}\n")' > captions.srt
```

Captures of a fixture cut from the Blender Foundation's
[Sintel trailer](https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4)
need the attribution “© copyright Blender Foundation |
durian.blender.org” under its [CC BY 3.0 terms](https://durian.blender.org/sharing/).

### Capture and publish

Captures land in the ignored `.evidence/` as `<UTC timestamp>-<label>.png|mp4`;
emulator boot logs land in `.evidence/logs/`.

```bash
./scripts/evidence.sh screenshot --label files-screen
./scripts/evidence.sh record --seconds 15 --label playback --allow-dark
./scripts/evidence.sh validate-recording --input <file>
```

Corrupt output is quarantined as `*.corrupt`, and near-black captures as
`*.black.*` unless `--allow-dark` allows legitimately dark content. Quarantined
files are never printed as evidence paths and never uploaded. Inspect each
validated capture, then attach it with `gh pr create --attach .evidence/<file>.png`
or `gh pr comment <n> --attach .evidence/<file>.mp4` (gh 2.99+). Never commit
evidence.

## Live API proof (putio CLI)

Fixtures and live state checks use the `putio` CLI with the shared test
identity `devs-auto`, the profile the web, iOS, and TV harnesses share. Never a
personal profile, never tokens in the repo; the CLI keeps its tokens in
`~/.config/putio/`.

```bash
putio auth login --profile devs-auto   # device-code flow; approve at the printed URL
putio auth status --profile devs-auto  # verify before relying on it
```

Every live lane follows the same fixture rules:

- Create a uniquely named root container per run and record every ID, name,
  parent, kind and mutation outcome in an ignored ownership ledger before each
  write. Remove only those IDs.
- Wait until guest instrumentation is idle before any cleanup. Delete leaves
  before parents, checking exact identity and contents first, and verify an
  exact `GET /files/<id>` 404 (other errors are not 404). If instrumentation
  does not go idle within your bounded wait, stop only an emulator you booted
  and confirm its shutdown first; on a reused emulator leave the fixtures
  untouched.
- An unknown mutation outcome keeps the ledger for read-only reconciliation;
  never repeat the mutation. Never empty Trash or use ID zero; a trashed item
  needs its own cleanup, since removing its parent leaves the Trash entry.
- Read shared account settings before a proof and restore what you change.

Debug builds honour an ignored `local.properties` key,
`putioMobileOAuthClientIdDebugOverride`, that replaces the mobile OAuth client
id for local proof; release builds ignore it, and ordinary proof needs none.
Existing sessions belong to the previous client, so `adb shell pm clear` the
debug app and sign in again after changing it, and name the client when you
attach evidence.

## Mobile lanes

### Mobile device-code sign-in

Signs the debug app in as `devs-auto` with no browser: a debug-only screen
shows a device code and the CLI approves it. The session is the normal
Keystore one. `--help` covers prerequisites and failures.

```bash
./scripts/sign-in-devs-auto.sh emulator-5554
```

Release builds never declare the screen:
`verifyProductionReleaseDebugSignInManifest` fails if they do. Each approval
adds a grant; sign out in the app to revoke it.

Parallel lanes can share one sign-in through a `putio-phone` snapshot. Every
instance of the AVD must then run `-read-only`, and a snapshot loads only with
the renderer that saved it:

```bash
adb -s <serial> emu avd snapshot save devs-auto-signed-in   # signed in, booted with the flags below
emulator -avd putio-phone -read-only -snapshot devs-auto-signed-in -no-snapshot-save \
  -port <free even port> -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim
```

Each copy restores the same session; check it with the script, which passes
without a new approval. Signing out in one copy revokes the grant for all.

### Mobile accessibility proof

Unless a selector says otherwise, accessibility lanes run on the API 37
emulator with `font_scale` at `2.0` and all three global animation scales
(`animator_duration_scale`, `window_animation_scale`,
`transition_animation_scale`) at `0`. Record their prior values, absent keys
included, and restore them. Compose semantics assertions do not prove
TalkBack speech. Arguments: `putio.accessibility.enabled` and
`putio.accessibility.runId`; screenshots go to `accessibility-proof-<UUID>/`.
Use normal-size captures as primary UI evidence and label large-text ones as
accessibility checks.

- `MobileAccessibilityProofTest` mounts controlled auth, Files, Trash and
  Settings with no API calls. Require `OK (2 tests)`.
- `MobileShellAccessibilityProofTest#fullShellWithPrivateAudioKeepsNavigationAndTransfersReachable`
  adds the real navigation shell, app bars, the Transfers overflow and FAB, and the
  now-playing bar with a private muted player. It needs
  `putio.accessibility.audio` (a 180 s local audio fixture). Run it in
  portrait and landscape with `putio.accessibility.orientation`, one
  `OK (1 test)` each. Inspect the whole shell and keyboard state; visible test
  nodes alone do not prove a usable layout.
- `MobileKeyboardNavigationProofTest#keyboardKeepsNavigationAndTheFocusedSearchEditor`
  runs at `font_scale` `1.0`, in portrait and landscape with the orientation
  argument, and checks navigation, focus and the query survive the keyboard
  inset.

The reduced-motion check reuses the zero animation scales without TalkBack.
Open the navigation drawer, a Files or Trash item sheet, the Files sort menu, a
Settings choice dialog, the Add transfer sheet with a shared-link replacement
prompt, and the player, then pull to refresh Files and Transfers. Each must
appear in its final state on the first frame after the tap: capture a
screenshot within half a second and a short recording. The app defines no
animation of its own, so a non-zero scale here is a regression.

#### TalkBack lanes

These selectors are driven by hand while TalkBack runs. They connect no
UiAutomation service: on API 37 a second accessibility service interfered with
TalkBack's player traversal. Enable TalkBack and its Developer settings →
Display speech output and VERBOSE logging, recording prior settings. Capture
the walkthrough and the filtered `SpeechControllerImpl` log; state assertions
alone do not prove spoken output. Dismiss Android's first-use fullscreen hint
through TalkBack when it appears and restore `immersive_mode_confirmations`.

Send one gesture at a time, waiting for TalkBack to speak and show focus:

```bash
python3 -B scripts/talkback-input.py --adb "$ADB" --serial emulator-5554 \
  --action swipe-right --width 1080 --height 2400 --rotation 0
```

Actions are `swipe-left`, `swipe-right` and `double-tap`. Pass the current
screenshot's physical size and display rotation (0..3; phone landscape is
normally 2400×1080, rotation 1). On API 37, `input swipe` and
`UiAutomation.injectInputEvent` bypass TalkBack's gesture input; the helper's
emulator `event mouse` reaches its touch recognizer. It changes no settings and
releases a held touch on failure.

- `MobileTalkBackProofTest` needs `putio.accessibility.video` and
  `putio.accessibility.audio`: 180 s media with two labelled audio tracks and
  embedded video captions, as for [playback options](#playback-options-device-proof).
  Activate the auth recovery button, video Pause and 1.5×, and audio Pause, and
  inspect the Audio, Captions and audio options labels. Follow
  `talkback-stage.txt` (`auth-action`, `video-ready`, `video-pause`,
  `video-speed`, `video-controls`, `audio-ready`, `audio-pause`,
  `audio-controls`, `finished`); `talkback-state.txt` records state. After
  inspecting the spoken video controls create `talkback-video-reviewed`, and
  after the audio ones `talkback-audio-reviewed`, in the run directory. Stages
  are bounded to 120 s, the flow to 360 s.
- `MobileTalkBackChoicesProofTest#talkBackActivatesSignInResumeAndStartOver`
  mounts the signed-out screen and Resume dialog with controlled callbacks and
  no media arguments. Follow `talkback-choices-stage.txt`: Sign in at
  `sign-in`, Resume at `resume`, Start over at `start-over` (fixtures
  “Rehearsal video.mp4” at 01:23 and “Rehearsal audio.mp3” at 00:42). Each
  callback must fire exactly once; `talkback-choices-state.txt` records the
  sequence. Phases are bounded to 120 s, the walkthrough to 400 s.
- `MobileTalkBackSessionProofTest#talkBackControlsNowPlayingAndSeeksPrivateAudio`
  mounts the shell around one private muted audio player and needs
  `putio.accessibility.audio` of at least 300 s. Follow
  `talkback-session-stage.txt`: Pause at `bar-pause`, Play at `bar-play`, open
  the player and pause at `open-player-paused`, Forward 10 seconds or the
  timeline slider at `seek`, then Back and Stop playback at `return-and-stop`.
  The selector observes the player itself and takes no acknowledgement files;
  the bar has no seek control, so the observed seek proves the player screen
  was open. Preparation is bounded to 15 s, each phase to 120 s, the selector
  to 660 s.

Each requires `finished` and `OK (1 test)`.

### Playback options device proof

`MobilePlaybackOptionsProofTest` uses the real mobile player and audio service
with local media and no API calls. Arguments: `putio.playback.options.enabled`,
`.runId`, `.audio` and `.video`, the last two 180 s AAC audio and H.264 video
fixtures with two differently labelled audio tracks. It captures the layouts
and sheets, selects 1.5× and the second track, recreates the video player and
reconnects to background audio. Lifecycle uses a controlled Compose host, so
this is not full Activity-recreation proof. Use an otherwise idle media
session. Screenshots: `playback-options-proof-<UUID>/`.

### Authenticated rename proof

With the mobile production debug app signed in as `devs-auto`
([Mobile device-code sign-in](#mobile-device-code-sign-in)):

```bash
./gradlew --no-daemon :mobile:proveAuthenticatedRename \
  -PputioRenameEnabled=true \
  -PputioRenameSerial=emulator-5554 \
  -PputioRenameFixture=/absolute/path/to/owned-rename-fixture.json
```

The serial must already run API 37. The task assembles and installs both APKs
with `adb install -r` (never uninstalling or clearing data), validates the CLI
account and fixtures first with `PUTIO_CLI_PROFILE=devs-auto` and no
`PUTIO_CLI_TOKEN`, and the device test checks its session's account ID before
touching a fixture. No token enters an argument or the output.

The fixture JSON (at most 24 KiB) holds exactly:

- `expectedAccountId`: the `devs-auto` account ID
- `containerId`, `renameItemId`, `cancelItemId`: distinct positive IDs
- `containerName`: exact folder name, unique in its search results
- `renameOriginalName`, `renameNewName`, `cancelOriginalName`: distinct exact
  names; both rename names contain a space and a non-ASCII character

The folder and its search must fit one 50-item page, both items must be in it,
and the new name must be unused; anything else fails closed. For a second run,
update the names to the current state. The test renames through overflow, then
edits a second item through long-press and cancels, checking UI and API
readback. Ordinary connected runs skip it as an assumption that AGP may record
as an XML failure; only this task proves it ran.

Preflight, install, instrumentation and capture share a 240 s deadline; a
failed, skipped or incomplete test fails the task. Cleanup (20 s budget) stops
only the run's own host processes and recorder and never force-stops the app.
If instrumentation is still active it fails and leaves it, since API 37 can
hide which invocation owns it; confirm guest idleness before touching fixtures.
`--no-daemon` lets interruption reach the Gradle process; SIGINT can drop
`CLEANUP FAIL` from the client output, so read
`~/.gradle/daemon/<version>/daemon-<pid>.out.log` (under `$GRADLE_USER_HOME`
when set). Run logs and raw captures stay under `.evidence/rename-<run-id>/`;
only a passing task prints `EVIDENCE <validated-path>` and
`PROOF PASS authenticated-rename`.

### Authenticated Trash/Delete device test

`AuthenticatedFilesDeleteTest#authenticatedDeletePreservesSessionAndCancel`
browses an empty fixture folder, sorts its parent, cancels one action and
confirms another, checking the item through the SDK and Trash membership where
it applies. It never changes the account's Trash setting, so a run covers only
the mode its fixture records. With Trash on, cancelling means leaving the sheet
and the confirmed move shows the "Moved to Trash" snackbar.

Arguments: `putio.delete.enabled`, `.runId` and `.fixture` (base64 JSON with
`expectedAccountId`, `containerId`, `containerName`, `actionItemId`,
`actionName`, `expectedTrashEnabled`, `cancelItemId`, `cancelName`). Use two
empty folders in one owned container, named so descending order puts the
cancel item first; IDs are positive and distinct, and the parser rejects
duplicate or unknown keys. Screenshots: `delete-proof-<UUID>/`.

### Authenticated Move device test

`AuthenticatedFilesMoveTest#authenticatedMovePreservesCancelAndConfirmsExactParents`
covers Cancel, a same-name collision, folder and file moves, a move into a
cached ancestor and a folder move to root, checking each item's parent through
the SDK and the source sort and viewport after Cancel. Run
`MoveProofFixtureTest#acceptsSevenOwnedItemsAndRejectsAmbiguousFixtures` first.

Arguments: `putio.move.enabled`, `.runId` and `.fixture` (base64 JSON with
`expectedAccountId`, `containerId`, `containerName`, `sourceId`, `sourceName`,
`destinationId`, `destinationName`, `folderItemId`, `folderName`, `fileItemId`,
`fileName`, `fileSize`, `collisionItemId`, `collisionPeerId`,
`collisionName`). The owned root container holds source and destination
folders; the source holds an empty action folder, a 1–128 byte UTF-8 file and
an empty collision folder, and the destination an empty folder of the same
collision name. Seven distinct positive IDs and six distinct names, with
non-ASCII action-folder and file names. Track the action folder separately
once it moves to root.

With `putio.move.ui.enabled` and the same run ID, the synthetic
`FilesMoveNavigationUiProofTest#rootMoveConsumesBackOnFilesAndAccountUntilRecoveryCompletes`
covers the activity Back dispatcher while a root Move is pending.
Screenshots: `move-proof-<UUID>/`.

The debug-only Compose test host handles asset-path configuration changes:
API 37 can update asset overlays mid-test, and recreating a plain
`ComponentActivity` would drop the test's content. `MainActivity` is
unaffected.

### Trash bulk actions proof

Behaviour: [Trash](./behavior.md#trash); `TrashActionTest` pins the rules on
the JVM. Restore all and Empty Trash act on the whole shared account, so they
are proven synthetically; live proof covers Delete permanently on an owned
fixture. Create `android-trash-actions-proof-<UUID>` with one tiny UTF-8 file,
trash it with `POST /files/delete?skip_trash=false` (put.io reads `skip_trash`
from the query only; a form value is ignored and the file goes permanently when
Trash is off), run the in-app Delete permanently, verify the exact 404 and its
absence from Trash, then delete the empty container.

### Account Trash and single-item Restore proof

Behaviour: [Trash](./behavior.md#trash). Run
`TrashRestoreFixtureTest#acceptsOnlyTheOwnedFourItemFixture`, then
`AuthenticatedTrashRestoreTest#cancelPreservesTrashAndRestoreMakesTheExactItemAvailable`
with `putio.trash.restore.enabled`, `.runId` and `.fixture` (base64 JSON).

Create `android-trash-restore-proof-<UUID>` with a 1–128 byte UTF-8 file, an
empty Cancel folder and an empty sentinel folder, names distinct and holding
Unicode and the run UUID. The fixture has `runId`, `expectedAccountId`,
`fileSize` and, for each of `container`, `file`, `cancel` and `sentinel`,
`<role>Id`, `<role>Name`, `<role>ParentId` and `<role>FileType` (the file's
actual `FILE` or `TEXT`; folders `FOLDER`). Trash only the file and Cancel
folder with separate one-ID `files.delete(skipTrash=false)` requests; each must
appear within four Trash pages of 50.

Cancel dispatches zero Restore calls and the confirmed Restore one. The app
makes at most 14 exact GETs two seconds apart within 60 s; the fifteenth is
yours for independent verification within the same deadline. Afterwards,
restore the Cancel folder with one recorded request, wait for availability,
and clean up as above; never delete a parent that may still receive a queued
restoration.

With `putio.trash.restore.ui.enabled` and the same UUID, the synthetic
`TrashRestoreUiProofTest#ambiguousRestoreAndAuthenticationFailureNeverRepeatMutation`
mounts the shell on controlled repositories. It captures only when Back loses
Trash, into `trash-restore-proof-<UUID>/`; keep that apart from the live
recording.

### Live audio attachment without source resolution

`MobileLiveAudioAttachmentProofTest#shellRequestReopensLiveAudioWithoutResolvingItsSource`
uses the real `MobilePlaybackService` and MediaController with a local two-track
audio fixture, a controlled account and a playback repository that always fails
resolution. Opening the controls and reopening after Back through
`NowPlayingRequests` must make zero resolver calls and keep position, speed,
track and Play/Pause. It does not tap the notification, deliver a MainActivity
intent or simulate process death.

Arguments: `putio.audio.attach.enabled`, `.runId` and `.fixture` (an absolute
device path; the playback-options `audio.m4a` under
`/sdcard/Android/data/io.put.putio.mobile.debug/files/playback-options-fixtures/`
works). It refuses to replace an existing session media item and stops
playback only while its own item is current. Screenshots:
`live-audio-attachment-proof-<UUID>/`.

### Resume and position reporting proof

Behaviour: [Resume and position reporting](./behavior.md#resume-and-position-reporting);
`PlaybackPositionWriterTest`, `PlaybackPositionObserverTest` and
`MobilePlaybackReportingTest` pin the rules on the JVM.

Live: upload a short audio file and a video to an owned folder with
`devs-auto`, record their initial `start_from`, and set a positive position.
Open each in the shell and exercise Resume, Start over, pause, Back, and
background audio with notification controls; read the saved positions back
through the CLI after the reporting interval and pause. Restore the initial
positions before cleanup. Local test-player proof does not establish live
write-back.

### Immersive landscape video proof

Behaviour: [Mobile video player](./behavior.md#mobile-video-player).
`MobileFullscreenVideoProofTest#landscapePlaybackUsesDirectControlsAndRestoresThePreviousWindow`
plays local Media3 video in a debug-only Activity that handles orientation
changes, so the production window policy rotates the real window: portrait with
bars becomes landscape without them, and Back restores both. It opens the Audio,
Subtitles and Speed side sheets, picks the second track, 1.5× and the caption
track, checks tracks and cue text survive saved-state recreation
(`StateRestorationTester`, not Activity or process recreation), and that Off and
picking the track again clear and restore cues. The source declares `PlaybackSubtitles.None`, so captions must
come from player-discovered tracks. Its player factory builds ExoPlayer with the
production renderers, because the production player streams through the download
cache's HTTP source, which cannot read a local file.

Arguments: `putio.video.fullscreen.enabled`, `.runId` and `.fixture`: a 180 s
landscape MP4 with AAC `eng`/`deu` audio tracks and an `eng` mov_text caption
track showing `Rehearsal caption proof` throughout.
`portraitVideoKeepsAnUnlockedPortraitWindow` also needs
`putio.video.fullscreen.portraitFixture`, any portrait MP4 (the fixture recipe
at `size=720x1280`): it plays full-screen in portrait with bars hidden and no
orientation request. Screenshots: `fullscreen-video-proof-<UUID>/`.

### Picture-in-picture proof

Behaviour: [Picture-in-picture](./behavior.md#picture-in-picture).
`MobilePictureInPictureProofTest` mounts the player in MainActivity, so the
manifest declarations are on trial, plays a local MP4 with automatic captions,
and drives the system window with Home and its menu; synthetic.

- `homeEntersTheWindowWhichKeepsPlayingObeysItsControlsAndExpandsWherePlaybackIs`:
  Home enters the window and playback and reporting continue; Pause holds,
  Play resumes, Expand returns to the same player straight to landscape.
- `closingTheWindowStopsTheVideoAndTheNextVisitFindsItPaused`: Close writes the
  position; a launcher relaunch waits, paused, within a second of it.

```bash
python3 -c 'for i in range(40): t = lambda v: f"00:{v // 60:02d}:{v % 60:02d},000"; print(f"{i + 1}\n{t(i * 3)} --> {t(i * 3 + 3)}\nPicture-in-picture proof caption {i + 1}\n")' > captions.srt
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=120 -f lavfi -i sine=frequency=440:duration=120 \
  -i captions.srt -map 0:v -map 1:a -map 2:s -c:v libx264 -pix_fmt yuv420p -g 60 -c:a aac -c:s mov_text \
  -metadata:s:s:0 language=eng -shortest pip-proof.mp4
adb -s emulator-5554 push pip-proof.mp4 /sdcard/Android/data/io.put.putio.mobile.debug/files/pip-proof.mp4
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.MobilePictureInPictureProofTest \
  -e putio.pip.enabled true -e putio.pip.runId "$(uuidgen | tr A-Z a-z)" \
  -e putio.pip.fixture /sdcard/Android/data/io.put.putio.mobile.debug/files/pip-proof.mp4 \
  io.put.putio.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The menu buttons are found once by their descriptions (Pause, Expand, Close);
the accessibility window list drops the menu after its first use, so later
presses reuse those places. `am instrument` force-stops the app first, and the
lane acts on the whole device. Screenshots: `pip-proof-<UUID>/`.

### Tablet proof

Behaviour: [Keyboard and shortcuts](./behavior.md#keyboard-and-shortcuts),
[Drag and drop](./behavior.md#drag-and-drop) and
[Multi-window](./behavior.md#multi-window). `MobileTabletPowerProofTest`
mounts the shell in the debug-only `MobileTabletProofActivity`, which takes
MainActivity's configuration changes, over faked Files, a local video and a
controlled session whose drag-out reads stream a generated JPEG in-process;
synthetic. The other app is the androidTest `DragProbeActivity`, written in
Java against the framework only because it runs in the test package's process;
its torrent provider sits outside the app's authority prefix, since put.io
refuses torrents from providers named after its own package.

The resized phone below proves keyboard, drag and window behaviour only. Captures
of the tablet layout need true scale, status bar included: use an AVD created
with `-d pixel_tablet` (2560x1600 at 320 dpi, 1280x800 dp).

For this proof, use a phone AVD you created, with a hardware keyboard and a
tablet-size display; restore with `wm size reset` and `wm density reset`:

```bash
sed -i '' 's/^hw.keyboard=no$/hw.keyboard=yes/' ~/.android/avd/<name>.avd/config.ini   # before booting
adb -s <serial> shell wm size 2560x1600 && adb -s <serial> shell wm density 320          # 1280x800 dp
```

Arguments: `putio.tablet.enabled`, `.runId` and `.video` (a landscape MP4 from
the fixture recipe). One `OK (1 test)` per selector:

- `keyboardDrivesFilesSearchTrashAndThePlayer`: Tab, Enter, Backspace, Ctrl+F,
  Escape, F5, Delete with focus kept, the Keyboard Shortcuts Helper, Enter
  selecting the video into the detail pane and its Play, then Space, Right and
  Escape in the player.
- `dragAndDropWithAnotherAppInSplitScreenKeepsTheVideoAndShell`: the video
  keeps playing in a split beside the probe; `poster.jpg` drags out, readable
  only while the drop's permissions are held and never writable; the probe's
  `.torrent` and magnet link drag in.

Android's one-time "Viewing full screen" hint takes key focus, so the proof
answers it; the emulator may still show the on-screen keyboard. Screenshots:
`tablet-proof-<UUID>/`.

### Mobile share-in proof

Behaviour: [Share-in](./behavior.md#share-in). Never use real credentials in
share proof artifacts. Live: share from the browser to the editable sheet and
Add with an owned transfer fixture, plus cancellation, a second share while
editing, and rotation before confirmation; capture the "1 transfer added"
snackbar. `MobileShellAccessibilityProofTest` covers both replacement choices
at 200% font.

Magnet intake needs no account: the sheet must show this link unsubmitted
(after sign-in when signed out):

```bash
adb shell am start -a android.intent.action.VIEW \
  -d 'magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Harbor%20film' \
  -p io.put.putio.mobile.debug
```

`MainActivityShareTest` covers that intent and the `.torrent` content-URI
read on the JVM; `MobileTransfersScreenTest` and `SdkTransfersRepositoryTest`
cover the Save to picker, `save_parent_id`, refused links from add-multi and the
torrent upload.

### Mobile share-out and deep links proof

Behaviour: [Share-out](./behavior.md#share-out) and
[Deep links](./behavior.md#deep-links); `MobileFileShareServiceTest`,
`MobileDeepLinksTest` and `MainActivityDeepLinkTest` pin them on the JVM. On
the signed-in emulator:

```bash
adb shell am start -a android.intent.action.VIEW -d putio://transfers
adb shell am start -a android.intent.action.VIEW -d https://app.put.io/files/<id> -p io.put.putio.mobile.debug
```

Then Share file on a small image and confirm the chooser preview; in
`dumpsys activity activities` the chooser's `clip=` must hold only a
`content://` URI and no `oauth_token`. Repeat for the rail layout after
`adb shell wm size 1600x2560` and `adb shell wm density 320`, then reset both.

`MobileShareSessionProofTest` (`io.putdotio.android.share`) runs the real
service and `MainActivity` with a controlled session and an in-process JPEG
download; synthetic. A control export opens the chooser; a second, held
mid-download while the session signs out, removes its folder and opens none.
Arguments: `putio.share.session.enabled`, `.runId`. Screenshots:
`share-session-proof-<UUID>/`.

### Documents provider proof

Behaviour: [Documents provider](./behavior.md#documents-provider).
`MobileDocumentsPickerProofTest` (`io.putdotio.android.documents`) sends
`ACTION_OPEN_DOCUMENT` from a test activity and drives the real system picker
over a faked session and Files repository; the remote file streams through the
in-process share-out request, the local one from a stand-in download;
synthetic. Arguments: `putio.documents.enabled`, `.runId`; record with
`scripts/evidence.sh record --seconds 60`. Screenshots in
`documents-proof-<UUID>/` cover the root, a three-page folder paging in, the
remote and local picks, and, after sign-out, refused documents and no root.

The picker reopens at its last root. The test activity shares the app's uid,
which always reads its own provider, so revoking another app's grant is pinned
by `MobileDocumentsProviderTest`, not this lane.

### Downloads and offline playback proof

Behaviour:
[Downloads and offline playback](./behavior.md#downloads-and-offline-playback);
`DownloadsControllerTest`, `MobileDownloadStoreTest`,
`MobileDownloadEngineTest`, `MobileDownloadNotificationsTest`,
`OfflinePlaybackPositionsTest`, `UserScopedCacheKeysTest` and
`OfflinePlaybackRepositoryTest` pin the rules on the JVM.

Live, with the app signed in as `devs-auto`
([Mobile device-code sign-in](#mobile-device-code-sign-in)):

- Download a small root video; its Downloads row advances about once a second
  until `On this device` shows there and in Files. Enable airplane mode
  (`adb shell cmd connectivity airplane-mode enable`) and play it.
- Cut the network mid-download: the row reads `Waiting for network`, then
  completes on its own once restored.
- `databases/exoplayer_internal.db` must hold no `oauth_token` and a
  `u<userId>|` prefix on every `ExoPlayerCacheIndex*` key; cached playlist
  bodies are server text and contain the token.
- Deleting the local copy shrinks the cache directory; the CLI shows the
  originals untouched after a multi-select delete.
- With the limit at 2, four downloads show two running and the rest
  `Queued · #n in line`; raising the limit starts more.
- `adb shell cmd devicestoragemonitor force-low -f` makes rows read
  `Waiting for free storage`; `adb shell cmd devicestoragemonitor reset`
  resumes them.
- After `adb shell pm revoke <package> android.permission.POST_NOTIFICATIONS`
  (the app is killed; relaunch it), a finished download posts nothing and
  Downloads shows the notice; after Turn on or `pm grant`, the next
  notification opens its row.
- Removing `files/downloads/` with `run-as` after a force-stop makes the row
  read missing with Download again.
- Offline resume: leave a downloaded video partway online, go offline, reopen,
  Resume, play on, Back; back online, the CLI reads the new position.

Between runs clear only `databases/exoplayer_internal.db*`,
`shared_prefs/io.putdotio.android.downloads.xml` and `files/downloads/`; never
app data or the session.

Two synthetic lanes run the Downloads screen, controller and engine over a
real Media3 manager reading generated local files, each with its own index and
cache:

- `MobileDownloadsProgressProofTest` (`putio.downloads.progress.enabled`,
  `.runId`) reads 12 MB at about 1 MB/s and fails unless the row shows at least
  five byte counts before `On this device`. Record it. Screenshots:
  `downloads-progress-proof-<UUID>/`.
- `MobileDownloadsQueueProofTest` (`putio.downloads.queue.enabled`, `.runId`)
  queues five files at a limit of 2, one failing for storage until retried.
  Revoke POST_NOTIFICATIONS first and use an emulator you booted: it forces low
  storage and resets it, also on failure (the platform rechecks about once a
  minute), then grants the permission. It restores the concurrency setting and
  removes only its own index rows. Screenshots:
  `downloads-queue-proof-<UUID>/`.

### Shortcuts, widget and notification actions proof

Behaviour: [App shortcuts](./behavior.md#app-shortcuts),
[Transfers widget](./behavior.md#transfers-widget) and the notifications under
[Downloads and offline playback](./behavior.md#downloads-and-offline-playback);
`MobileShortcutsTest`, `MobileShellLinkActionsTest`, `MobileTransfersWidgetTest`,
`MobileDownloadNotificationsTest` and `MobileDownloadActionReceiverTest` pin
them on the JVM. Use an emulator you created and booted: the lane leaves a
widget on the home screen and grants POST_NOTIFICATIONS.

Shortcuts need no session. `adb shell dumpsys shortcut` must list `search`,
`add-transfer` and `downloads` with
`cmp=<package>/io.putdotio.android.MainActivity`; `cmp=@<id>/...` means a
resource reference and opens nothing. Open the launcher menu with
`adb shell "input motionevent DOWN <x> <y>; sleep 1.5; input motionevent UP <x> <y>"`
(`input swipe` does not open it on API 37); signed out, Add transfer opens
sign-in.

`MobileHomeScreenProofTest` drives the real launcher and notification shade
over a controlled session; synthetic. Arguments: `putio.homescreen.enabled`,
`.runId`; run one selector at a time and record the notification one with
`scripts/evidence.sh record --seconds 75`. Screenshots:
`homescreen-proof-<UUID>/`.

- `theTransfersWidgetFollowsTheSessionAndForgetsItOnSignOut` pins the widget
  through the launcher's dialog and checks it signed out, signed in (three rows
  and `1 more`), cleared at sign-out with no row name on screen, and for the
  next account.
- `downloadNotificationActionsActOnlyForTheirOwnAccount`: the owner's Try again
  replaces the outcome; another account's retries nothing and is removed; Play
  opens MainActivity with `putio://downloads/<id>/play?user=<id>`, waiting at
  sign-in when signed out. The receiver's Media3 start is a stand-in; routing
  after sign-in is `MobileShellLinkActionsTest`.

`am force-stop` cancels the app's pending intents, the widget's included, so
its taps do nothing until its next update.

### Move and copy target folder proof

Behaviour: [Move and copy target folder](./behavior.md#move-and-copy-target-folder).
`MobileMoveTargetProofTest` mounts the Files route and controller on a faked
repository with the real `MobileMoveTargetStore`, cleared before and after;
synthetic. Arguments: `putio.movetarget.enabled`, `.runId`. Screenshots in
`move-target-proof-<UUID>/` cover the toggle, reopening at the remembered
folder for Move and Make a copy, Back reading the parent, root after sign-out
or for an unreadable folder, a remembered folder now inside the moved one, and
put.io's 403 for a move into itself.

### Public links proof

Behaviour: [Public links](./behavior.md#public-links).
`MobilePublicLinksProofTest` mounts the Files route, the public links sheet,
the system chooser and Account's list on faked repositories; synthetic.
Arguments: `putio.publiclinks.enabled`, `.runId`. Each step holds about a
second for a recording. Screenshots in `public-links-proof-<UUID>/` cover a
friend's file without Exclusive access, create, copy, share, revoke, the daily
limit copy and Account's list. A live run needs the app signed in as
`devs-auto` ([Mobile device-code sign-in](#mobile-device-code-sign-in)).

### Transfer retry proof

Behaviour: [Transfer failures and retry](./behavior.md#transfer-failures-and-retry).
`MobileTransferRetryProofTest` runs the Transfers screen and controller over
`SdkTransfersRepository` with faked SDK calls: a failed transfer with an
`error_message`, one without, one downloading; Retry succeeds for the first and
gets a 403 for the second. Synthetic. Arguments: `putio.transfers.enabled`,
`.runId` (the example in [Device lanes](#device-lanes)). Screenshots in
`transfers-proof-<UUID>/`: failure reasons, the "Retrying transfer" snackbar,
and the rejected retry's snackbar with no dialog.

## TV lanes

TV instrumentation classes live in `io.putdotio.android.tv` (the player's in
`.tv.player`); arguments use the `putio.tv.<lane>` prefix shown per lane. The
`TvSessionShell` lanes mount the production TV session and signed-in shell on
fake repositories and make no API calls: controlled-state proof.

### TV device-code sign-in proof

Approve the TV code with the shared identity instead of a browser:

```bash
./scripts/emulator.sh boot tv --headless
adb -s emulator-5554 install -r tv/build/outputs/apk/production/debug/tv-production-debug.apk
adb -s emulator-5554 shell am start -n io.put.putio.debug/io.putdotio.android.MainActivity
code=$(adb -s emulator-5554 exec-out uiautomator dump /dev/tty | grep -oE 'Activation code [A-Z0-9]+' | awk '{print $3}')
PUTIO_CLI_PROFILE=devs-auto putio auth approve "$code"
```

The shell appears within one 3 s poll. Get new code requests another; Sign out
under Account returns to a fresh code at once and revokes in the background. A
force-stop and relaunch must land in the shell (the Keystore restore);
`pm clear io.put.putio.debug` drops the token. The emulator has no Fire TV
feature flag, so it links as the Android TV client (6221); Fire TV (6233) needs
the physical device from
[#51](https://github.com/putdotio/putio-android/issues/51).

### TV platform integration proof

Behaviour: [TV sign-in QR code](./behavior.md#tv-sign-in-qr-code),
[TV Watch Next](./behavior.md#tv-watch-next) and
[TV system search](./behavior.md#tv-system-search). Use a TV emulator you
booted: the lane signs in and changes the launcher's rows.

QR: decode a capture of the sign-in screen with ZXing's reader (the `core` jar
Gradle already resolved), compare it with put.io's QR for the same code, then
approve the code it carries:

```bash
./scripts/evidence.sh screenshot --serial emulator-5558 --label tv-link-qr
jar=$(find ~/.gradle/caches/modules-2/files-2.1/com.google.zxing/core -name 'core-*.jar' | head -1)
cat > /tmp/Decode.java <<'JAVA'
import com.google.zxing.*; import com.google.zxing.common.HybridBinarizer; import com.google.zxing.qrcode.QRCodeReader;
public class Decode { public static void main(String[] a) throws Exception {
  var i = javax.imageio.ImageIO.read(new java.io.File(a[0])); int w = i.getWidth(), h = i.getHeight();
  System.out.println(new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h,
    i.getRGB(0, 0, w, h, null, 0, w)))), java.util.Map.of(DecodeHintType.TRY_HARDER, true)).getText()); } }
JAVA
ours=$(java -cp "$jar" /tmp/Decode.java .evidence/<capture>.png); code=${ours##*code=}
curl -s -o /tmp/server-qr.png "https://api.put.io/v2/oauth2/oob/qr/$code"
[ "$ours" = "$(java -cp "$jar" /tmp/Decode.java /tmp/server-qr.png)" ] && echo MATCH
PUTIO_CLI_PROFILE=devs-auto putio auth approve "$code"
```

Watch Next: upload a uniquely named video of a few minutes with `devs-auto`,
open it with
`adb shell am start -a android.intent.action.VIEW -d putio://files/<id> io.put.putio.debug`,
play past one 15 s sample and press Home: Play Next shows the card with the
screenshot and progress. `putio files start-from set <id> <s>` places a position
for a repeat. Center on the card (or `-d putio://continue/<id>`) continues
without the prompt; playing to the end, Sign out, and trashing the file then
force-stopping and relaunching (the check runs once per process) each remove the
card. The shell user cannot read another package's Watch Next rows, so check the
launcher (`uiautomator dump`, Resume watching), not `content query`.

System search: the provider refuses the shell (`content query --uri
content://io.put.putio.debug.search/search_suggest_query/<q>` reports the
`GLOBAL_SEARCH` denial). `TvGlobalSearchProofTest` queries it from the app's
own process against the signed-in session, covering the quiet restore in a
fresh process, with one search request:

```bash
./gradlew :tv:assembleProductionDebugAndroidTest
adb -s emulator-5558 install -r tv/build/outputs/apk/androidTest/production/debug/tv-production-debug-androidTest.apk
adb -s emulator-5558 shell am instrument -w -r -e class io.putdotio.android.tv.TvGlobalSearchProofTest \
  -e putio.tv.globalSearch.query <unique name part> -e putio.tv.globalSearch.fileId <id> \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The `putio-tv` system search UI is Google Assistant: without a Google account
it starts the app for the provider (logcat `Start proc ... for content provider
... TvSearchSuggestionsProvider`) but answers "Sorry, I didn't understand.", so
a result row in the system UI stays unproven. Remove the fixture folder and the
trashed video afterwards.

### TV safe-area proof

`TvSafeAreaProofTest` (synthetic account, no sign-in, so the `putio-tv`
session survives) mounts the signed-in shell at the current display, outlines
the safe edge in red, captures the collapsed and expanded drawer and the
inactive-account notice, and fails if any label or focus target leaves the safe
area:

```bash
run=$(uuidgen | tr 'A-Z' 'a-z')
adb -s emulator-5554 shell wm size 1280x720 && adb -s emulator-5554 shell wm density 213   # optional 720p
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.tv.TvSafeAreaProofTest \
  -e putio.tv.safearea.enabled true -e putio.tv.safearea.runId "$run" \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 pull "/sdcard/Android/data/io.put.putio.debug/files/tv-safearea-proof-$run" .evidence/
adb -s emulator-5554 shell wm size reset && adb -s emulator-5554 shell wm density reset
```

A device can clamp `wm size` overrides below Android's three-times limit, and
`putio-tv` does not apply 3840x2160, so the JVM test covers 4K at xxxhdpi.
Elsewhere, check that `wm size` reports the requested `Override size` before
calling a run 4K.

### TV copy proof

Behaviour: [History events](./behavior.md#history-events),
[Storage quota](./behavior.md#storage-quota), [Trash](./behavior.md#trash).
`TvCopyProofTest` mounts the TV session and shell on fake repositories whose
History mixes every event kind; controlled-state. Arguments:
`putio.tv.copy.enabled`, `.runId`. Screenshots: `tv-copy-proof-<UUID>/`.

### TV manual checks

These lanes run on the signed-in `putio-tv` after device-code sign-in. The
shared identity's files, settings, history and Trash are the fixture: create
throwaway items, read state before a proof, and restore what you change.
"Oracle" is the 34-capture TV behaviour oracle in
[#33](https://github.com/putdotio/putio-android/issues/33).

```bash
adb -s emulator-5554 exec-out uiautomator dump /dev/tty | grep -oE 'content-desc="(Open|Play|Search again for) [^"]+"' | head
PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation files.getStartFrom --args '[<id>]' --execute --output json
PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation account.getSettings --execute --output json
PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation trash.list --execute --output json
```

- **Files**: focus starts on the first row. Center opens a folder or explains
  an unsupported type; Back pops folders to the root, then the shell owns Back.
  Up from the first row reaches Refresh and Sort, whose centred dialog focuses
  the current choice; sort persists to the account, so restore it. Center on a
  media row plays it and Back returns to the row.
- **Files actions** (oracle files-actions state): long-press Center or Menu on
  a row opens a centred dialog titled with the file name, one button per action
  with Cancel last and the first focused; a row with no actions opens nothing.
  Open in VLC hands `/files/{id}/stream` with the account's download token
  (never the access token) to `org.videolan.vlc`; without a download token the
  account gets a notice, and without VLC a dialog. Mark as watched or
  unwatched needs a confirmed `use_start_from` (watched also a known duration).
  Move to trash runs at once; Delete permanently confirms with Cancel focused;
  both show the shared delete phases, Check status or Retry, and the listing's
  verdict. Every dialog returns focus to its row.
- **Account** (oracle captures 09–12 and 14): the header holds the avatar,
  username, quota and Sign out; Playback settings, Storage settings and App and
  device information follow as rows, with Sign out last. Focus starts on Choose
  your proxy once settings load, else the header's Sign out. Switches save
  through `AccountSettingsController` and `AndroidAppConfigController`; a failed
  save keeps the server value and offers Try again. Choose your proxy loads
  `/tunnel/routes` with the direct route first; Video playback type lists MP4
  above HLS (default); turning Trash off confirms with Cancel focused. Every
  dialog returns focus to its row.
- **Trash** (Account → Manage your trash): focus starts on the first row, or
  Refresh when empty; Up reaches Refresh, Restore all and Empty trash. Center
  on a row offers Restore and Delete permanently; every mutation confirms with
  Cancel focused and reports with Check status or Check trash until the listing
  confirms. Never confirm a mutation you have not fixtured.
- **Search**: focus enters on the pill field; Center summons the IME, its
  Search submits, and the field also searches 300 ms after typing stops. Right
  from the field's end reaches Settings; Down reaches the recent-query chips,
  then results and Load more; Left returns to the drawer. Long-press on a chip
  removes it. Recent terms are the shared `searchHistory` config, so remove
  proof terms. `adb shell input text 'tears'` then `input keyevent
  KEYCODE_ENTER` types without the IME for headless proof.
- **History**: lists only shared files and completed transfers, as tv-native
  does, grouped under Today, Yesterday, Last week, Last month and Earlier, each
  with its kind and relative time, or its date past a week. Focus enters on the
  first event; Up reaches Clear, Left the drawer. Center on an event naming a
  file opens it as a Search result does; any other event is a resting row. Clear
  confirms with Cancel focused and removes the account's whole history, so clear
  only on a proof account. With `history_enabled` off the pane names Account's
  Keep account history switch; TV's own toggle flips it at once, a change
  elsewhere on the next session validation.

### TV player proof

Behaviour: [TV playback](./behavior.md#tv-playback). `TvPlayerProofTest`
(`io.putdotio.android.tv.player`) mounts a fixed Files listing, the TV shell
and the real player with the production ExoPlayer factory, and drives it with
D-pad keys. The listing, resolved source and position server are fakes, so it
is controlled-state proof. The Compose test clock follows real time so the
auto-hide and position timers run as in the app.

- `selectPlaysTheFixtureAndBackReturnsToItsRow`: play, pause and resume, Back
  hides the controls, Back returns to the row.
- `dpadScrubbingAndBackWalkTheOverlayStack`: scrub, commit, rewind, then Back
  through seek mode, playing controls and paused controls to the row.
- `resumeDialogBackLeavesThenContinueAndStartOverWithWriteBack`: against a
  fake position server at 45 s, Back on the dialog leaves without a player or a
  write; Continue plays from 45 s and writes after 16 s and on leaving; Start
  from the beginning plays from zero and Continue resumes from what it saved.
- `aVideoFinishedWithinTenSecondsOfItsEndOpensAgainWithoutAsking`: from 70 s
  the last write is the real end (at least 89 s), and the next Center starts
  over without the dialog.
- `savedAudioContinuesWithoutAsking` (needs `audioFixture`): audio with a 45 s
  position plays from it without the dialog.
- `languageSubtitlesAndSpeedPickersJoinTheBackStack`: switches to the second
  audio track, turns subtitles off (still off after a seek), picks 1.5×, and
  Back closes only the open picker.
- `conversionThenSessionControlsAndARecoverableError`: the conversion
  interstitial starts one conversion unprompted and reads queue, 35 % and 80 %;
  `input keyevent KEYCODE_MEDIA_PLAY_PAUSE` and the system media session's
  controls pause and play it, leaving removes the session, and a network error
  on the second video recovers through Try again.
- `hideSubtitlesLeavesNoSubtitlesButton`: a `hide_subtitles` account has no
  Subtitles button and no subtitle on.
- `automaticSubtitlesStartOnTheAccountsDefaultOverTheCaptionLanguage` (needs
  `defaultSubtitleFixture`): with system captions on in English, the master's
  `DEFAULT=YES` German rendition wins; caption settings are restored.

The fixture is two audio renditions and a WebVTT subtitle rendition over HLS,
plus a German-default variant and a 90 s AAC file:

```bash
python3 -c 'for i in range(30): t = lambda v: f"00:{v // 60:02d}:{v % 60:02d},000"; print(f"{i + 1}\n{t(i * 3)} --> {t(i * 3 + 3)}\nTV proof subtitle {i + 1}\n")' > captions.srt
rm -rf hls && ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=90 -f lavfi -i sine=frequency=440:duration=90 \
  -f lavfi -i sine=frequency=880:duration=90 -i captions.srt -map 0:v -map 1:a -map 2:a -map 3:s \
  -c:v libx264 -pix_fmt yuv420p -g 60 -c:a aac -c:s webvtt -f hls -hls_time 4 -hls_playlist_type vod \
  -master_pl_name index.m3u8 -var_stream_map "v:0,s:0,agroup:aud,sgroup:subs,name:video \
  a:0,agroup:aud,language:en,name:English,default:yes a:1,agroup:aud,language:de,name:Deutsch" \
  -hls_segment_filename 'hls/%v/seg%03d.ts' hls/%v/media.m3u8
sed -i.bak -e 's/NAME="audio_1"/NAME="English"/' -e 's/NAME="audio_2"/NAME="Deutsch"/' \
  -e 's/NAME="subtitle_0",DEFAULT=NO/NAME="English",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="en"/' hls/index.m3u8
rm hls/index.m3u8.bak
mkdir hls/subs-de && cp hls/video/media_vtt.m3u8 hls/subs-de/
for f in hls/video/media*.vtt; do sed 's/TV proof subtitle/Account default subtitle/' "$f" > "hls/subs-de/${f##*/}"; done
sed -e 's|^#EXT-X-MEDIA:TYPE=SUBTITLES,.*|#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Deutsch",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="de",URI="subs-de/media_vtt.m3u8"\
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=NO,AUTOSELECT=NO,LANGUAGE="en",URI="video/media_vtt.m3u8"|' \
  hls/index.m3u8 > hls/default-subtitle.m3u8
ffmpeg -f lavfi -i sine=frequency=440:duration=90 -c:a aac hls/audio.m4a
adb -s emulator-5554 push hls/. /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.tv.player.TvPlayerProofTest \
  -e putio.tv.player.enabled true -e putio.tv.player.runId "$(uuidgen)" \
  -e putio.tv.player.fixture /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/index.m3u8 \
  -e putio.tv.player.audioFixture /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/audio.m4a \
  -e putio.tv.player.defaultSubtitleFixture /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/default-subtitle.m3u8 \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
```

An `.m3u8` fixture plays as HLS, anything else as the original file. Append
`#<method>` to the class to record one flow. Screenshots:
`tv-player-proof-<UUID>/`.

### TV autoplay proof

Behaviour: [TV playback](./behavior.md#tv-playback). `TvAutoplayProofTest`
runs `TvSessionShell` with Autoplay next video and resume on and a 30 s local
video per Files row (long enough that 00:05 is not within 10 s of the end).
The first video plays to its end and writes it, the next asks to continue from
00:05, Back twice returns to Files on the autoplayed row, and the folder's last
video leaves playback on its row. A second case turns the setting off: the
first video ends and playback leaves without looking up the next.

```bash
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=30 -f lavfi -i sine=frequency=440:duration=30 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest tv-autoplay-proof.mp4
adb -s emulator-5554 push tv-autoplay-proof.mp4 /data/local/tmp/tv-autoplay-proof.mp4
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.tv.TvAutoplayProofTest \
  -e putio.tv.autoplay.enabled true -e putio.tv.autoplay.runId "$(uuidgen)" \
  -e putio.tv.autoplay.fixture /data/local/tmp/tv-autoplay-proof.mp4 \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Screenshots: `tv-autoplay-proof-<UUID>/`. Remove the pushed fixture, the copied
`tv-autoplay-fixture.mp4` and the screenshots afterwards.

### TV Search history proof

Behaviour: [Recent searches](./behavior.md#recent-searches).
`TvSearchHistoryProofTest` runs `TvSessionShell` with the production
recent-search store over an in-memory `/config` that logs every write. Slow
typing keeps nothing, an opened result and a submit are kept, Settings clears
and disables history, and the test asserts the exact `searchHistory` and
`searchHistoryEnabled` writes. Arguments: `putio.tv.searchHistory.enabled`,
`.runId`. Screenshots: `tv-search-history-proof-<UUID>/`.

### TV Search and History opens proof

Behaviour: [History opens](./behavior.md#timestamps-and-history-opens).
`TvExternalOpenProofTest` runs `TvSessionShell` with a local video: Files on
Movies, a Search video result plays after the resume prompt and Back returns to
it focused, `notes.pdf` opens its Documents folder focused on it, a folder
result opens, and Files is still on Movies. The app cannot read a directory
adb creates under `Android/data`, so the fixture goes to `/data/local/tmp`:

```bash
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=90 -f lavfi -i sine=frequency=440:duration=90 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest tv-open-proof.mp4
adb -s emulator-5554 push tv-open-proof.mp4 /data/local/tmp/tv-open-proof.mp4
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.tv.TvExternalOpenProofTest \
  -e putio.tv.open.enabled true -e putio.tv.open.runId "$(uuidgen)" \
  -e putio.tv.open.fixture /data/local/tmp/tv-open-proof.mp4 \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Two more methods need no fixture; select them with `#` and drop
`-e putio.tv.open.fixture`:

- `backOnTheDrawerReturnsToThePaneThenThePaneRulesApply` proves
  [TV Back](./behavior.md#tv-back) on Search, an empty History, Account and a
  Files folder; a handler registered before the shell stands in for the system.
- `aPickOnALaterPageOfItsFolderOpensFocusedOnIt`: a three-page folder served
  1.5 s per page, with the document on the third; Back while it loads returns
  to the result, and Center again opens it focused with the row above loaded.

Screenshots: `tv-open-proof-<UUID>/`. Remove the pushed fixture, the copied
`tv-open-fixture.mp4` and the screenshots afterwards.

## Transfers polling CPU benchmark

`TransfersPollingCpuBenchmark` replays the deterministic Transfers histories
from `domain/transfers/src/testFixtures` with no network or account, and logs
the median thread CPU and wall time per poll for the old list walk and the
current refresh under the `TransfersPollingCpu` tag. It lives in the
`domain/transfers` test APK, so no app is installed or removed:

```bash
./gradlew :domain:transfers:assembleDebugAndroidTest
adb -s <serial> install -r -t domain/transfers/build/outputs/apk/androidTest/debug/transfers-debug-androidTest.apk
adb -s <serial> logcat -c
adb -s <serial> shell am instrument -w \
  -e class io.putdotio.android.transfers.TransfersPollingCpuBenchmark \
  -e putio.transfers.benchmark.enabled true \
  io.putdotio.android.transfers.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> logcat -d -s TransfersPollingCpu:I
adb -s <serial> uninstall io.putdotio.android.transfers.test
```

Label results with the device and build; emulator numbers depend on host load.
