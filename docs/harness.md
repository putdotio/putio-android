# Harness

Emulator, launch-proof, evidence, live-API, and headless operating notes for
this repo. The root [Agent Guide](../AGENTS.md) owns setup, build, verify,
and the proof map; [Behaviour](./behavior.md) owns the product rules
the proof lanes exercise.

## Emulators

Bootstrap creates two reusable AVDs, `putio-phone` (API 37) and `putio-tv`
(API 36). The opt-in `putio-google-tv` covers the Google TV launcher and Play
surfaces. [scripts/lib.sh](../scripts/lib.sh) (`avd_name_for`, `image_for`, `device_for`)
owns their device profiles and system images.

```bash
./scripts/bootstrap.sh --google-tv                # adds the Google TV image and AVD
./scripts/emulator.sh boot google-tv --headless
./scripts/emulator.sh stop google-tv
```

A fresh `putio-google-tv` boots into Google TV setup; finish or cancel it
before launcher proof. `scripts/prove.sh tv` still targets `putio-tv`; install
on the Google TV emulator with `adb -s <serial> install` for manual proof.

```bash
./scripts/emulator.sh boot phone --headless   # prints serial; reuses if running
./scripts/emulator.sh status
./scripts/emulator.sh stop phone              # or stop emulator-5554
```

Lifecycle contract (enforced by the scripts, proven by
`scripts/test-lifecycle.sh`): a flow stops only the exact serial it booted, on
completion, failure, SIGINT, and SIGTERM; it confirms the owned serial is gone
from `adb devices` before returning; preexisting emulators are reused, never
stopped; AVD registrations are deleted only if the flow created them via
`--ephemeral`. No process-name or blanket emulator cleanup, ever.

Failed lifecycle runs keep their case logs under `.evidence/logs/lifecycle.*`.
Forced-failure cases require an `INJECTED_FAILURE <stage>` marker, so an
unrelated failure cannot satisfy them.

Every phone boot, including reuse, also verifies API 37, selects the bundled
Chrome as the browser role holder, and requires its AndroidX Auth Tab service
category. The harness fails closed before install when that secure OAuth
transport is unavailable. The CI managed device (`ciPhone`) is also API 37;
TV remains API 36.

Bootstrap never replaces a mismatched AVD. It still provisions the other
profiles, then fails with an explicit command; stop and delete that exact
profile yourself before rerunning bootstrap. The harness owns only
`putio-phone`, `putio-tv`, the opt-in `putio-google-tv`, and the
`--ephemeral` AVDs it creates; other AVDs on the machine are left alone, even
when `avdmanager list avd` reports them as unloadable.

## Launch Proof

One command from source to a verified, evidenced launch:

```bash
./scripts/prove.sh mobile            # build → boot → install → launch → verify → screenshot → teardown
./scripts/prove.sh tv --record       # also captures a screen recording
```

Verification explicitly selects the instrumented smoke test (`LaunchSmokeTest`)
through `verifyMobileLaunchProof` or `verifyTvLaunchProof` on the booted emulator.
These tasks run the matching production-debug connected instrumentation task.
Opt-in feature suites and the separately scheduled OAuth regression are not
part of this launch proof.
It asserts RESUMED state, a 3 s stability window, and real composited pixels (mean luma) via platform
test APIs; a crash or ANR fails the instrumentation. The exit code is the
proof. The `verifyMobileLaunchProof` and `verifyTvLaunchProof` tasks also
require the named smoke test's successful XML result; an empty, skipped, or
missing result fails even when instrumentation exits successfully.
One automatic retry covers the cold-boot render flake, where the
emulator composites the app window black for the first minute. Exit 0 pass ·
1 fail or bad arguments · 64 missing flavor · 70 cleanup failure · 130/143 interrupted. Machine-readable stdout
markers: `BOOTED <serial>`, `EVIDENCE <path>`, `PROOF PASS|FAIL <flavor>`.

The connected suite uninstalls the tested apps afterward; `prove.sh` reinstalls
the app for capture. Authenticate after launch proof for interactive feature
checks, and do not run `connectedAndroidTest` or `prove.sh` between authenticated
steps. The device auth tests use separate preferences and Keystore aliases so
their own setup and cleanup do not touch an existing session.

`domain/auth` and `domain/transfers` keep their device tests in their own test
APKs (`KeystoreAuthTokenStoreInstrumentedTest`, `TransfersPollingCpuBenchmark`).
`ANDROID_SERIAL=<serial> ./gradlew :domain:auth:connectedDebugAndroidTest`
installs and removes only that test APK, so it is safe beside an authenticated
app.

Flags: `--keep` (leave emulator running), `--ephemeral` (throwaway AVD,
deleted on exit; conflicts with `--keep`), `--window` (headed), `--skip-build`,
`--record`, `--seconds N` (recording length, 3 to 180).

`--record` runs after verification: the app is force-stopped and relaunched
under active capture, so the clip always has frames (`screenrecord` drops
static screens) and shows a real cold process launch on a settled system.
A publication gate trims sustained frozen lead and tail while retaining short
context around the action. A fully static recording is quarantined as
`*.mp4.idle`; pass `--keep-idle` to `scripts/evidence.sh record` only for an
intentional timing demonstration. Published recordings are lossy re-encodes
of the trimmed range, not the raw device capture.
A follow-up gated screenshot confirms the recorded relaunch rendered.
Still eyeball captures before publishing them as evidence.

## Evidence

The opt-in device lanes below share one contract. Build the mobile production
debug app and instrumentation APKs, install both with `adb install -r` on the
existing API 37 emulator, and invoke only the named class or selector through
bounded `am instrument` with the lane's opt-in flag and a fresh `runId` UUID.
Never run `connectedAndroidTest` or `prove.sh` on an authenticated
installation. Screenshots land in the lane's `<lane>-proof-<UUID>/` directory
under the target app's external files directory; pull and inspect them,
validate recordings with `scripts/evidence.sh validate-recording`, and publish
as in [Capture and publish](#capture-and-publish). The caller owns bounded
instrumentation supervision, recording, emulator lifetime, fixture cleanup, and
restoring every device setting a lane changes; report lanes that make no API
calls as synthetic proof.

### Mobile accessibility proof

`MobileAccessibilityProofTest` mounts controlled production auth, Files,
Search, Transfers, Trash and Settings surfaces without API calls or session
changes. Its three selectors exercise named actions, sheets, dialogs and the
real keyboard. Unless a selector says otherwise, every accessibility lane
below runs with the API 37 emulator's system `font_scale` at `2.0` and all
three global animation scales (`animator_duration_scale`,
`window_animation_scale`, `transition_animation_scale`) at `0`. Record their
exact prior values, including absent keys, and restore them after proof. This
lane proves large-font reachability with animations disabled;
Compose semantics assertions do not establish actual TalkBack speech.

Opt in with `putio.accessibility.enabled=true` and
`putio.accessibility.runId=<UUID>` as instrumentation arguments. Require
`OK (3 tests)`. Screenshots land in the `accessibility-proof-<UUID>/` run
directory.

`MobileShellAccessibilityProofTest` adds the actual navigation shell, app bars
and Transfers toolbar to the controlled proof. Run each named selector once in
portrait and once in landscape, passing `putio.accessibility.orientation` with
the expected orientation as well as the existing opt-in and run ID:

- `fullShellNavigationAndTransfersKeepActionsReachable`
- `fullShellWithPrivateAudioKeepsNavigationAndTransfersReachable`

Both check complete destination labels, drawer Back, usable list space, all four
destinations, Transfers actions and Add with the real keyboard. The audio selector
also checks the now-playing bar using a private muted ExoPlayer. Pass
`putio.accessibility.audio` pointing to the existing caller-owned 180-second local
audio fixture beneath the target app's external files directory. It never connects
to the media-session service, initializes authentication, reports positions or
makes API calls. It releases only its own player after disposing the shell.
Require `OK (1 test)` per named invocation. Screenshots use `shell-portrait-` and
`shell-landscape-` prefixes, with `-audio` for the audio variant, in the same run
directory. Inspect the whole shell and keyboard state; visible test nodes alone
do not prove a usable layout. Use normal-size captures as primary UI evidence and
label large-text captures as accessibility checks.

`MobileKeyboardNavigationProofTest#keyboardKeepsNavigationAndTheFocusedSearchEditor`
checks normal-font navigation while the real keyboard opens. Set `font_scale` to
`1.0`, run once in portrait and once in landscape, and pass the same opt-in, run
ID and expected orientation. It requires the normal bar/rail before typing and
checks that navigation, focus and the editable query survive the keyboard inset
change. Screenshots use `normal-portrait-` and `normal-landscape-` prefixes. This
controlled shell does not initialize authentication, connect audio or call the API.
Require `OK (1 test)` per invocation.

`MobileTalkBackProofTest` is a separate, manually driven TalkBack lane. It
mounts controlled auth and private local players, then checks the real auth
callback and player state while the caller operates TalkBack. It connects no
UiAutomation service: on this API 37 emulator, querying accessibility nodes
from a second service interfered with TalkBack's player traversal. Enable
TalkBack and its Developer settings → Display speech output and VERBOSE
logging, recording prior settings for restoration. Capture the walkthrough
plus the filtered `SpeechControllerImpl` log; player state alone does not
prove spoken output. Screenshots and recordings require inspection and the
existing publication gates.

Pass the same opt-in and run ID, plus `putio.accessibility.video` and
`putio.accessibility.audio` pointing to caller-owned files beneath the app's
external files directory. Use 180-second media with two labeled audio tracks
and embedded video captions, as in the playback-options proof. The selector
uses private players and does not connect to the background audio service;
preserve the separate service lifecycle proofs. Navigate to each action with
TalkBack, then double-tap: the auth recovery button, video Pause and 1.5× speed,
and audio Pause. Inspect the Audio, Captions and audio playback-options labels
as part of the walkthrough. Dismiss Android's first-use fullscreen hint through
TalkBack when it appears, preserving its prior `immersive_mode_confirmations`
setting for restoration.

The owned proof directory contains `talkback-stage.txt` and
`talkback-state.txt`. Follow the stages `auth-action`, `video-ready`,
`video-pause`, `video-speed`, `video-controls`, `audio-ready`, `audio-pause`,
`audio-controls`, then `finished`. Only after inspecting the spoken video
Audio/Captions controls, create `talkback-video-reviewed` in that directory;
after inspecting the audio timeline/options, create `talkback-audio-reviewed`.
These acknowledgements record caller inspection, not automated speech assertions.
The selector limits each stage to 120 seconds and the whole flow to 360 seconds.
Require `OK (1 test)` and inspect the corresponding recording and utterance log.

`MobileTalkBackChoicesProofTest#talkBackActivatesSignInResumeAndStartOver`
completes the ordinary Sign in and resume-choice TalkBack lane. It mounts the
production signed-out screen and Resume dialog with controlled callbacks;
it opens no browser, starts no player and initializes no account runtime.
Use the TalkBack setup above with the accessibility opt-in arguments; no media
fixture arguments are needed.

Follow `talkback-choices-stage.txt` under `accessibility-proof-<UUID>/`: activate
Sign in at `sign-in`, Resume at `resume`, and Start over at `start-over`.
The fixed examples are “Rehearsal video.mp4” at 01:23 and “Rehearsal audio.mp3”
at 00:42. Each stage advances only after its real callback fires exactly once;
a wrong choice or dismissal fails. `talkback-choices-state.txt` records the
observed action sequence. Require `finished` plus `OK (1 test)`. Each phase is
bounded to 120 seconds and the whole walkthrough to 400 seconds. Capture and
inspect the actual TalkBack utterances and host recording; callback assertions
alone do not prove speech.

`MobileTalkBackSessionProofTest#talkBackControlsNowPlayingAndSeeksPrivateAudio`
mounts the production shell around one private, muted audio player. It connects
no account runtime or media-session service. Use the TalkBack setup above, a
fresh run UUID, and `putio.accessibility.audio` pointing
to caller-owned audio beneath the app's external files directory. The fixture
must last at least 300 seconds: playback starts at 30 seconds and may run through
the preparation and two playing phases at their full budgets before the host
pauses it.

Watch `talkback-session-stage.txt` in `accessibility-proof-<UUID>/`. Preparation
is automatic; at `bar-pause` activate the now-playing Pause action, then Play at
`bar-play`. At `open-player-paused`, open the player from the bar and pause it.
At `seek`, use Forward 10 seconds or the spoken timeline slider while paused.
At `return-and-stop`, go Back and activate Stop playback on the bar. The selector
observes the private player's state, new and live player attachments, and
seek events before advancing; it accepts no host acknowledgement files. The bar
has no seek control, so the observed seek discontinuity itself proves the player
screen was open. `talkback-session-state.txt` records the observed
state. Require `finished` and `OK (1 test)`. Preparation has a 15-second limit,
each user phase 120 seconds, and the entire selector 660 seconds. Capture real
TalkBack utterances and video separately; these checks prove private-player
interaction, not background service behavior or spoken output by themselves.

The reduced-motion check reuses the same zero animation scales without TalkBack.
Open the navigation drawer, a Files or Trash item sheet, the Files sort menu, a
Settings choice dialog, the Add transfer sheet with a shared-link replacement
prompt, and the player, then pull to refresh Files and Transfers. Each surface
must appear in its final state on the first frame after the tap; capture a
screenshot within half a second and a short recording. The app defines no
animation of its own: Material transitions, sheet drags and progress indicators
all read the system scales, so a non-zero scale here is a regression.

Send one hardware gesture at a time, waiting for TalkBack to speak and show the
focused control before the next gesture:

```bash
python3 -B scripts/talkback-input.py --adb "$ADB" --serial emulator-5554 \
  --action swipe-right --width 1080 --height 2400 --rotation 0
```

Actions are `swipe-left`, `swipe-right` and `double-tap`. Supply the current
screenshot's physical dimensions and Android display rotation (0..3); landscape
on the phone emulator normally uses 2400×1080 and rotation 1. The command maps
coordinates to the emulator's natural orientation, bounds every command, and
releases a held touch on interruption or failure. On API 37, injected `input
swipe` and `UiAutomation.injectInputEvent` bypass TalkBack's gesture input;
emulator `event mouse` reaches its actual touch recognizer. This helper changes
no settings, account state or emulator lifecycle. The caller still supervises
instrumentation, recording, artifact capture and settings restoration.

### Playback options device proof

`MobilePlaybackOptionsProofTest` uses the real mobile player and audio service
with caller-owned, local two-track media. It makes no API calls and preserves
the installed account session. Opt in with
`putio.playback.options.enabled=true`, `putio.playback.options.runId=<UUID>`,
and `putio.playback.options.audio` / `putio.playback.options.video` set to
readable fixture paths under the app's external files directory.

Use 180-second AAC audio and H.264 video fixtures containing two differently
labeled audio tracks. The tests capture clean audio/video layouts and settings sheets, select 1.5× speed
and the second track, check
the real player state, recreate the video player, and reconnect to background
audio. Lifecycle and saved-state transitions use a controlled Compose host;
this is local-media proof, not live API or full Activity-recreation proof.
Screenshots go to the `playback-options-proof-<UUID>/` run directory. Remove
only the caller-owned fixtures after instrumentation is idle. The audio test stops its playback at
completion; use an otherwise idle media session.

### Capture and publish

Captures land in `.evidence/` (gitignored) as
`<UTC timestamp>-<label>.png|mp4`; emulator boot logs land in
`.evidence/logs/`.

```bash
./scripts/evidence.sh screenshot --label files-screen
./scripts/evidence.sh record --seconds 15 --label playback --allow-dark
```

Captures are validated: corrupt output is quarantined as `*.corrupt`, and
near-black captures fail and are quarantined as `*.black.*` unless
`--allow-dark` is passed for legitimately dark content (playback, dark
scenes). Quarantined files are never printed as evidence paths.

Never commit evidence files. Eyeball each validated capture, then upload it to
the pull request with `gh pr create --attach .evidence/<file>.png` or
`gh pr comment <n> --attach .evidence/<file>.mp4` (gh 2.99+). Never upload a
quarantined file.

## Live API Proof (putio CLI)

For fixtures and live API state checks, use the `putio` CLI with the shared
test identity `devs-auto`, the same profile the web, iOS, and TV
harnesses use. Never a personal profile, never tokens in the repo:

```bash
putio auth login --profile devs-auto   # device-code flow; approve at the printed URL
putio auth status --profile devs-auto  # verify before relying on it
putio auth profiles list
```

Tokens live in the CLI's own config (`~/.config/putio/`), outside the repo.
The harness is secret-free by default: nothing in bootstrap, build, or proof
requires authentication.

## Borrowing another OAuth client for local proof

Ordinary proof needs no override. Debug builds honour an ignored
`local.properties` key, `putioMobileOAuthClientIdDebugOverride`, that replaces
the mobile client id for local proof only; release builds ignore it. Which
client ids are eligible is put.io-internal. Existing sessions were issued to
the previous client, so `adb shell pm clear` the debug app and sign in again
after changing it. Say which client a proof ran on when you attach evidence.

## Headless / Devbox Notes

- `--headless` adds `-no-window -gpu swiftshader_indirect` to the `-no-snapshot -no-boot-anim -no-audio` every harness boot uses. Software rendering is slower than `auto-no-window` but deterministic; host-GPU headless mode intermittently composites app windows black, which fails the pixel assertion. `screencap`/`screenrecord` capture fine without a window
- Cold headless boots regularly ANR the emulator's own `com.android.systemui`; harness boots set `hide_error_dialogs 1` so the dialog cannot sit over captures. App crashes and ANRs still fail the instrumented proof
- macOS needs Hypervisor.framework (default on Apple Silicon); Linux devboxes need KVM (`emulator -accel-check`). Without acceleration arm64 images are unusably slow
- First boot of a fresh AVD is the slow path (~1 min on an M-series Mac); subsequent boots are faster with `-no-snapshot` still enforced for reproducibility
- On shared machines check `scripts/emulator.sh status` before assuming a free console port; the scripts scan 5554–5584

## Authenticated rename proof

After authenticating the mobile production debug app as `devs-auto`, run the
Files rename flow without `connectedAndroidTest` or `prove.sh`:

```bash
./gradlew --no-daemon :mobile:proveAuthenticatedRename \
  -PputioRenameEnabled=true \
  -PputioRenameSerial=emulator-5554 \
  -PputioRenameFixture=/absolute/path/to/owned-rename-fixture.json
```

The serial must already be running API 37. The task assembles the app and test
APKs, discovers the installed CLI contract with `putio describe --output json`,
validates the CLI account and fixtures before installation, resolves APK
identities with `apkanalyzer`, and installs both with `adb install -r`. Install
failure stops the flow; it never uninstalls or clears app data. CLI checks force
`PUTIO_CLI_PROFILE=devs-auto` and remove `PUTIO_CLI_TOKEN` from the child environment.
The device test separately validates its existing session's account ID through
the app's SDK client before touching a fixture. No token enters an argument.

The caller creates a small, uniquely named fixture folder, records its exact
IDs in an ownership ledger, and removes only those owned fixtures afterward.
The fixture JSON is limited to 24 KiB and contains exactly these fields:

- `expectedAccountId`: positive account ID returned by the `devs-auto` CLI profile.
- `containerId`, `renameItemId`, `cancelItemId`: distinct positive IDs from the ledger.
- `containerName`: exact folder name, unique in its complete search result.
- `renameOriginalName`, `renameNewName`, `cancelOriginalName`: distinct, nonempty,
  exact names. Both rename names must contain a literal space and at least one
  non-ASCII character. Names are preserved exactly; the whole name is replaced.

Both the folder contents and container search must fit a complete 50-item page.
The two items must belong to that folder, and the new name must be unused.
Missing, ambiguous, changed, or incorrectly typed fixtures fail closed. The
flow neither creates fixtures nor interprets readable shared items as owned.
For a second run, update the ledger's original/new names to the actual current
state; the same encrypted session is reused without another login.

The device test navigates Search → folder, renames through overflow, confirms
UI and API readback, then edits a second item through long-press and cancels.
It reads back the exact changed draft before Cancel and checks that Cancel leaves
the server name unchanged. It does not replace
physical keyboard, TalkBack, or live permission-rejection proof. Ordinary
connected tests skip this opt-in test before launching its Activity rule. The
Android runner reports that opt-out as an assumption; AGP may serialize it as
an XML failure even when the connected task succeeds. Use the named proof task
above to establish that the authenticated flow actually ran and passed. The
canonical `verify` task assembles the mobile production debug test APK without
running it, so device-test compilation is checked on each CI change.

Host preflight, install, instrumentation, and capture processing share a
240-second deadline after assembly. A failed, skipped, absent, interrupted, or
incomplete named test fails the task. Cleanup stops only the run's own host
processes and screen recorder, removes only that run's guest capture files, and
never force-stops the app. If instrumentation remains active, cleanup preserves
it and fails: API 37 process dumps can hide instrumentation arguments, so the
harness cannot tell which invocation owns it. Interruption therefore does not
guarantee remote instrumentation has stopped; inspect the reported state before
another run.

The emulator, app installation, authentication, and fixture ledger remain intact.
Cleanup has its own 20-second command budget and preserves failure causes.
Use `--no-daemon` for this manual runtime lane so interruption reaches its
single-use Gradle process. SIGINT can disconnect the client before `CLEANUP FAIL`
reaches its output. Inspect the single-use daemon log under
`$GRADLE_USER_HOME/daemon/<version>/daemon-<pid>.out.log` (default
`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) for cleanup diagnostics, and
confirm guest instrumentation is idle before recovery, another proof attempt, or
any fixture mutation or deletion. If it does not become idle within the caller's
bounded wait, stop and confirm shutdown of only an emulator the caller started
before modifying fixtures. A caller that reused an emulator must preserve it and
leave fixtures untouched until instrumentation is confirmed idle.

Run logs and raw capture remain under `.evidence/rename-<run-id>/`. Raw captures
are not publication evidence. The existing capture gates validate and normalize
them through `scripts/evidence.sh validate-recording --input <file>`, and only a
successful task prints `EVIDENCE <validated-path>` followed by
`PROOF PASS authenticated-rename`. No fixture or credential payload is printed
by CLI preflight.

## Authenticated Trash/Delete device test

`AuthenticatedFilesDeleteTest#authenticatedDeletePreservesSessionAndCancel`
exercises the current confirmed account mode: browse an empty fixture folder,
sort its parent, cancel one named action, and confirm another. With Trash on,
Move to trash has no confirmation: cancelling means leaving the actions sheet,
which sends nothing, and the confirmed move shows the "Moved to Trash" snackbar.
It checks the exact item through the SDK and verifies Trash membership when appropriate.
It never changes the account's Trash setting. A passing run covers only the
mode recorded in its fixture; folder descendant completion is outside this test.

With the existing shared-account session, the instrumentation arguments are
`putio.delete.enabled=true`, `putio.delete.runId=<UUID>`, and
`putio.delete.fixture=<base64 JSON>`. The fixture requires `expectedAccountId`,
`containerId`, `containerName`, `actionItemId`, `actionName`,
`expectedTrashEnabled`, `cancelItemId`, and `cancelName`. Use two distinct empty
folders inside one uniquely named, caller-owned container; choose action and
cancel names whose descending order places the cancel item first. IDs must be
positive, the three file IDs and names must be distinct, and the Trash mode
must match the current account. The parser rejects duplicate or unknown keys.

Follow the guest-idleness contract above. Track a trashed
item separately: removing its active parent does not remove its Trash entry.
Clean only exact owned IDs; never empty Trash or use ID zero. Preserve fixtures
when guest activity or cleanup outcomes remain unknown.

The separately opted-in `FilesDeleteRecoveryUiProofTest` uses
`putio.delete.ui.enabled=true` and the same run ID to capture controlled
error UI without API calls: `uncertainDeleteKeepsItemAndOffersStatusCheck`
for Check status recovery, and `folderTooLargeForTrashOffersConfirmedPermanentDelete`
for a folder over the Trash limit (`synthetic-trash-limit*.png`: the line,
the confirmation, and the result after its Delete). These tests and
`AuthenticatedFilesDeleteTest` write screenshots to the
`delete-proof-<UUID>/` run directory.

`FilesDeleteNavigationUiProofTest#rejectedSearchAndTransferNavigationPreserveRecovery`
uses the same synthetic opt-in and run ID. It mounts the real mobile shell with
controlled reducers: rejected Search/Transfers opens retain the current tab and
Delete recovery; after Check status reconciles the item, a fresh transfer open
succeeds. It makes no API calls and captures `synthetic-navigation.png` under
the same screenshot directory. Invoke this exact named test separately when
refreshing shell navigation proof; it requires no live fixtures.

## Files trash and paging proof

Behaviour: [Files delete and paging](./behavior.md#files-delete-and-paging).
`MobileFilesTrashPagingProofTest` runs the production Files screen and
controller over an in-memory repository with 120 rows in 50-row pages and Trash
on. It makes no API calls; report it as synthetic proof. Opt in with
`putio.files.enabled=true` and `putio.files.runId=<UUID>` and require
`OK (1 test)`. Screenshots land in `files-proof-<UUID>/`: `01-first-page`,
`02-actions`, `03-moved-to-trash` (no confirmation, snackbar with View Trash
and no Undo), `04-second-page-loaded` and `05-last-page` (pages that arrived by
scrolling, no Load more).

## Authenticated Move device test

`AuthenticatedFilesMoveTest#authenticatedMovePreservesCancelAndConfirmsExactParents`
exercises named Cancel, a same-name collision, folder and file moves, a move into
a cached ancestor, and a folder move to root. Each confirmed move checks the exact
item ID and destination parent through the SDK. It preserves the existing shared
account session and checks source sort and viewport after Cancel.

First run `MoveProofFixtureTest#acceptsSevenOwnedItemsAndRejectsAmbiguousFixtures`.
The authenticated selector requires `putio.move.enabled=true`,
`putio.move.runId=<UUID>`, and `putio.move.fixture=<base64 JSON>`.

The fixture requires `expectedAccountId`, `containerId`, `containerName`,
`sourceId`, `sourceName`, `destinationId`, `destinationName`, `folderItemId`,
`folderName`, `fileItemId`, `fileName`, `fileSize`, `collisionItemId`,
`collisionPeerId`, and `collisionName`. Create one unique root container with
source and destination folders. The source holds an empty action folder, a small
UTF-8 file (1–128 bytes), and an empty collision folder; the destination holds an
empty folder with the same collision name. All seven item IDs must be distinct
and positive. Use six distinct names, including non-ASCII action-folder and file
names. The parser rejects unknown/duplicate keys and ambiguous numeric values.
The live preflight checks exact identities, parents and complete owned contents
before any Move. Root destination is selected explicitly in the UI.

Track the action folder separately when it moves to root. After instrumentation
is idle, read back every owned ID and clean leaves before their parents, checking
exact identity and contents before deletion. Unknown mutation outcomes retain the
ownership ledger for read-only reconciliation; do not repeat the mutation.

`FilesMoveRecoveryUiProofTest#uncertainMoveRetainsSourceAndRetriesOnlyReads`
uses `putio.move.ui.enabled=true` and the same run ID for controlled recovery and
picker paging/error UI. With the same opt-in arguments,
`FilesMoveNavigationUiProofTest#rootMoveConsumesBackOnFilesAndAccountUntilRecoveryCompletes`
exercises the activity Back dispatcher while a root Move is pending or awaiting
read recovery, on both Files and Account. These controlled tests perform no API
operations. Screenshots go to the `move-proof-<UUID>/` run directory.

The debug-only Compose test host handles asset-path configuration changes. API 37
can update asset overlays during a test; recreating the plain `ComponentActivity`
loses the content installed by the test and leaves a blank replacement. This
manifest override applies only to that synthetic host. `MainActivity` keeps its
normal recreation behavior and installs product content in `onCreate`.

## Trash bulk actions proof

Behaviour: [Trash](./behavior.md#trash); `TrashActionTest` pins the
verification rules on the JVM.

Live proof on the shared `devs-auto` account covers Delete permanently on an
owned fixture only. Restore all and Empty Trash act on the whole account, which
contains other proofs' items, so they are proven synthetically. Create a unique
root container `android-trash-actions-proof-<UUID>` with one tiny UTF-8 file,
trash the file with `POST /files/delete?skip_trash=false` (the backend reads
`skip_trash` from the query string only; a form value is ignored and the file
is removed permanently when the account has Trash off), record IDs in an
ignored ledger, run the in-app Delete permanently, verify exact
`GET /files/<id>` 404 plus absence from Trash, then delete the empty container
last.

## TV device-code sign-in proof

The TV build links through `put.io/link`; approve its code with the shared
test identity instead of a browser:

```bash
./scripts/emulator.sh boot tv --headless
adb -s emulator-5554 install -r tv/build/outputs/apk/production/debug/tv-production-debug.apk
adb -s emulator-5554 shell am start -n io.put.putio.debug/io.putdotio.android.MainActivity
code=$(adb -s emulator-5554 exec-out uiautomator dump /dev/tty | grep -oE 'Activation code [A-Z0-9]+' | awk '{print $3}')
PUTIO_CLI_PROFILE=devs-auto putio auth approve "$code"
```

The shell appears within one poll interval (3 s). `Get new code` on the
sign-in screen cancels the current attempt and requests another; `Sign out`
under Account returns to a fresh code at once and revokes the token in the
background. A force-stop and
relaunch must land in the shell without a code: that is the Keystore restore.
`pm clear io.put.putio.debug` drops the stored token. The emulator has no
Fire TV feature flag, so it always links as the Android TV client (6221);
Fire TV (6233) needs the physical device set from #51.

## TV platform integration proof

Behaviour: [TV sign-in QR code](./behavior.md#tv-sign-in-qr-code),
[TV Watch Next](./behavior.md#tv-watch-next) and
[TV system search](./behavior.md#tv-system-search). The `putio-tv` image's
launcher shows the Play Next row; use a TV emulator you booted, since the lane
signs in and changes the launcher's rows.

QR: capture the sign-in screen, decode it with ZXing's reader (the `core` jar
Gradle already resolved), and compare it with put.io's own QR image for the
same code; then approve the code read from the QR:

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

Watch Next: upload a uniquely named video of a few minutes to a uniquely named
folder with the `devs-auto` CLI, open it with
`adb shell am start -a android.intent.action.VIEW -d putio://files/<id> io.put.putio.debug`,
let it play past one 15 s sample, then press Home: the Play Next row shows the
card with the screenshot and progress. `putio files start-from set <id> <s>`
places the position for a repeat. Center on the card (or
`-d putio://continue/<id>`) plays on from the saved position without the
prompt; playing to the end, Sign out under Account, and trashing the file then
force-stopping and relaunching the app (the check runs once per process) each
remove the card. The shell user cannot read another
package's Watch Next rows, so check the launcher (`uiautomator dump`, Resume
watching) rather than `content query`.

System search: the provider refuses the shell
(`content query --uri content://io.put.putio.debug.search/search_suggest_query/<q>`
reports the `GLOBAL_SEARCH` denial). `TvGlobalSearchProofTest` queries it from
the app's own process against the signed-in session, which also covers the
quiet restore in a fresh process; it makes one search request:

```bash
./gradlew :tv:assembleProductionDebugAndroidTest
adb -s emulator-5558 install -r tv/build/outputs/apk/androidTest/production/debug/tv-production-debug-androidTest.apk
adb -s emulator-5558 shell am instrument -w -r -e class io.putdotio.android.tv.TvGlobalSearchProofTest \
  -e putio.tv.globalSearch.query <unique name part> -e putio.tv.globalSearch.fileId <id> \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The system search UI on the `putio-tv` image is Google Assistant: without a
Google account it starts this app for the provider (logcat: `Start proc ...
for content provider ... TvSearchSuggestionsProvider`) but shows "Sorry, I
didn't understand." instead of app results, so a result row in the system UI
is unproven here. Remove the fixture folder and the trashed video afterwards.

## TV safe-area proof

`TvSafeAreaProofTest` (TV instrumentation, synthetic account, no API calls)
mounts the signed-in shell with placeholder panes at the emulator's current
display, outlines the safe edge in red, screenshots the collapsed and expanded
drawer and the shell with an inactive account's notice, and fails if any label
or focus target leaves the safe area. It needs
no sign-in, so the existing `putio-tv` session survives:

```bash
./gradlew :tv:assembleProductionDebug :tv:assembleProductionDebugAndroidTest
adb -s emulator-5554 install -r tv/build/outputs/apk/production/debug/tv-production-debug.apk
adb -s emulator-5554 install -r tv/build/outputs/apk/androidTest/production/debug/tv-production-debug-androidTest.apk
run=$(uuidgen | tr 'A-Z' 'a-z')
adb -s emulator-5554 shell wm size 1280x720 && adb -s emulator-5554 shell wm density 213   # optional 720p
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.tv.TvSafeAreaProofTest \
  -e putio.tv.safearea.enabled true -e putio.tv.safearea.runId "$run" \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 pull "/sdcard/Android/data/io.put.putio.debug/files/tv-safearea-proof-$run" .evidence/
adb -s emulator-5554 shell wm size reset && adb -s emulator-5554 shell wm density reset
```

Android accepts `wm size` overrides up to three times the display's largest
initial dimension, but a device can clamp them further (for example with a
configured maximum UI width). The `putio-tv` AVD did not apply a 3840x2160
override on its 1920x1080 panel when this proof was written, so 4K is covered
by the JVM test at xxxhdpi. On another device, check that `wm size` reports the
requested `Override size` before calling a run 4K.

## TV Files browse proof

After the device-code sign-in above, the shell lands in Files with focus on
the first row. D-pad Center opens a folder or explains an unsupported type;
Back pops the folder stack until the root, then the shell owns Back. Up from
the first row reaches Refresh and the Sort button, which opens the centred
sort dialog with focus on the current choice. Sort changes persist to the
account like mobile, so restore the previous order after a proof:

```bash
adb -s emulator-5554 exec-out uiautomator dump /dev/tty | grep -oE 'content-desc="(Open|Play) [^"]+"' | head
```

Center on a media row opens the TV player; Back returns to that row. See
[TV player proof](#tv-player-proof) for the recorded lane.

## TV player proof

Behaviour: [TV playback](./behavior.md#tv-playback). `TvPlayerProofTest`
(`tv/src/androidTest`) mounts a fixed Files listing, the TV shell and the real TV
player screen with the production ExoPlayer factory, then drives it with D-pad
key events. `selectPlaysTheFixtureAndBackReturnsToItsRow`: Down to the video
row, Center to play, Center to pause and resume, Back to hide the controls and
Back to the row. `dpadScrubbingAndBackWalkTheOverlayStack`: Right twice to
scrub, Center to commit, rewind then Back to dismiss seek mode, Back to hide
the playing controls, Center then Back to hide the paused controls, and Back
to the row. `resumeDialogBackLeavesThenContinueAndStartOverWithWriteBack` runs the real
session route (a `PlaybackController` per play and TV write-back) against a
fake position server that starts at 45 s: Center shows the resume dialog with
Continue focused, Down focuses Start from the beginning, Back leaves playback
without a player or a write, Center then Continue plays from 45 s, 16 s of
playback write once, leaving writes once more, then Start from the beginning
plays from zero and Continue resumes from what that playback saved.
`aVideoFinishedWithinTenSecondsOfItsEndOpensAgainWithoutAsking` starts the
server at 70 s: Continue plays to the end, playback leaves, the last write is the
real end (at least 89 s), and Center plays again from the start without the
dialog.
`savedAudioContinuesWithoutAsking` runs only with `putio.tv.player.audioFixture`:
an audio row with a 45 s saved position plays from it without the dialog.
`languageSubtitlesAndSpeedPickersJoinTheBackStack` needs the
multi-track fixture below and automatic subtitles: Down then Up to Language,
Center, Down, Center switches to the second audio track; Right, Center, Up,
Center turns subtitles off; Down, Right, Center seeks and subtitles stay off;
Down, Up, Right, Right, Center opens Speed, Back closes only the picker, then
Down twice picks 1.5×; Back hides the controls and Back returns to the row.
`conversionThenSessionControlsAndARecoverableError` runs the session route on
a fake conversion source: Center on the first video, which has no conversion
requested, shows the conversion interstitial starting it without a press, then
reading in queue, 35 % and 80 % three seconds apart before the fixture plays;
the fake counts exactly one start. `input keyevent KEYCODE_MEDIA_PLAY_PAUSE`, sent as the shell
user as `adb shell` does, pauses and resumes it; the system's media session
list then shows the player with its title as playing, and its transport
controls pause (the paused controls come up) and play it. Leaving removes the
session. The second video's first resolution fails with a network error, and
Try again plays it.
`hideSubtitlesLeavesNoSubtitlesButton` plays the same fixture for a
`hide_subtitles` account: Down, Up reaches Language, Right goes straight to
Speed, and no subtitle is on. `automaticSubtitlesStartOnTheAccountsDefaultOverTheCaptionLanguage`
turns the system's captions on in English, plays a second master whose first
subtitle rendition is German and marked `DEFAULT=YES` as put.io marks the
account's default, expects the German track, and restores the caption
settings; it runs only with `putio.tv.player.defaultSubtitleFixture`.
It makes no API calls; the listing, the resolved source and the position
server stand in for a signed-in session, so report it as controlled-state
proof.

```bash
./gradlew :tv:assembleProductionDebug :tv:assembleProductionDebugAndroidTest
adb -s emulator-5554 install -r tv/build/outputs/apk/production/debug/tv-production-debug.apk
adb -s emulator-5554 install -r tv/build/outputs/apk/androidTest/production/debug/tv-production-debug-androidTest.apk
# Two audio renditions and a WebVTT subtitle rendition; the picker flow needs them, the others play it too.
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
# The account-default flow: a German rendition put.io would mark default, ahead of the English one.
mkdir hls/subs-de && cp hls/video/media_vtt.m3u8 hls/subs-de/
for f in hls/video/media*.vtt; do sed 's/TV proof subtitle/Account default subtitle/' "$f" > "hls/subs-de/${f##*/}"; done
sed -e 's|^#EXT-X-MEDIA:TYPE=SUBTITLES,.*|#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Deutsch",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="de",URI="subs-de/media_vtt.m3u8"\
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=NO,AUTOSELECT=NO,LANGUAGE="en",URI="video/media_vtt.m3u8"|' \
  hls/index.m3u8 > hls/default-subtitle.m3u8
# The audio flow: a 90 s AAC file.
ffmpeg -f lavfi -i sine=frequency=440:duration=90 -c:a aac hls/audio.m4a
adb -s emulator-5554 push hls/. /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.tv.player.TvPlayerProofTest \
  -e putio.tv.player.enabled true -e putio.tv.player.runId "$(uuidgen)" \
  -e putio.tv.player.fixture /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/index.m3u8 \
  -e putio.tv.player.audioFixture /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/audio.m4a \
  -e putio.tv.player.defaultSubtitleFixture /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/default-subtitle.m3u8 \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The fixture must sit under the app's external files directory; an `.m3u8`
plays as HLS, anything else as the original file. Start
`scripts/evidence.sh record --allow-dark` just before the instrumentation for
the clip; append `#<method>` to the class to record one flow. Screenshots go
to `tv-player-proof-<UUID>/`: `01`–`05` for the first flow (focused row,
playing with controls, playing clean, paused, back on the row) and `10`–`18`
for the second (clean, seek mode, committed, rewind seek mode, seek dismissed,
controls dismissed, paused controls, paused clean, back on the row), and
`20`–`26` for the third (Continue focused, Start from the beginning focused,
back on the row after Back, `22b` continued, started over, the dialog after
starting over, continued, back on the row), `90`–`93` for the near-end flow
(the prompt, playing to the end, back on the row, opened from the start), `95`
for the audio flow, and `30`–`41` for the fourth (automatic subtitles, Language
focused, the audio picker, the second track, the subtitle picker, subtitles
off, still off after a seek, the speed picker, the picker dismissed, 1.5×,
controls dismissed, back on the row), and `49`–`60` for the fifth (starting, in queue,
35 %, 80 %, converted and playing, paused and resumed by the remote key,
paused and resumed by the system's controls, the network error, playing after
Try again, back on the row), `70` for the account's default subtitle and `80`
for the hidden Subtitles button. The
proof keeps the Compose test clock in step with real time so the auto-hide
and position timers run as they do in the app. Remove the fixture and
screenshot directories afterwards.

## TV autoplay proof

Behaviour: [TV playback](./behavior.md#tv-playback). `TvAutoplayProofTest`
(`tv/src/androidTest`) mounts the production TV session and signed-in shell on fake
repositories for an account with Autoplay next video and resume on, and plays a
caller-owned 30 s local video for each Files row (long enough that 00:05 is not
within 10 s of the end, which would count as finished): Center on the first
video, it plays to its end, its end position is written, the next video in the folder
asks to continue from 00:05, Center continues, Back twice returns to Files with
the autoplayed row focused, and Center plays it again to its end, after which
playback leaves (the folder's last video) back on that row. A second case turns
the setting off: the first video plays to its end and playback leaves without
looking up the next, back on its row. It makes no API calls, so report it as
controlled-state proof. Push the fixture to `/data/local/tmp`; the test copies
it into its own files directory.

```bash
./gradlew :tv:assembleProductionDebug :tv:assembleProductionDebugAndroidTest
adb -s emulator-5554 install -r tv/build/outputs/apk/production/debug/tv-production-debug.apk
adb -s emulator-5554 install -r tv/build/outputs/apk/androidTest/production/debug/tv-production-debug-androidTest.apk
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=30 -f lavfi -i sine=frequency=440:duration=30 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest tv-autoplay-proof.mp4
adb -s emulator-5554 push tv-autoplay-proof.mp4 /data/local/tmp/tv-autoplay-proof.mp4
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.tv.TvAutoplayProofTest \
  -e putio.tv.autoplay.enabled true -e putio.tv.autoplay.runId "$(uuidgen)" \
  -e putio.tv.autoplay.fixture /data/local/tmp/tv-autoplay-proof.mp4 \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Screenshots go to `tv-autoplay-proof-<UUID>/`: `01` the first row focused, `02`
it playing, `03` the next video's resume prompt, `04` the next playing, `05`
back on the autoplayed row, `06` the last video playing, `07` back on its row
after the folder's end; `off-01` the setting-off video playing, `off-02` back on
its row. Remove `/data/local/tmp/tv-autoplay-proof.mp4`, the
copied `tv-autoplay-fixture.mp4` and the screenshot directory afterwards.

## TV Search proof

Search is the second drawer destination. Focus enters on the pill field;
Center summons the system IME (Gboard TV on the emulator), Search on the IME
submits, and the field also searches 300 ms after typing stops. Right from
the end of the field reaches Settings (Disable, Show and Clear search
history). Down from the field reaches the recent-query chips, then the result
rows and the Load more control; Left from any of them returns to the drawer.
Center on a row plays a video or audio result, opens a folder in Files, or
opens another file's folder with focus on it; Back returns to that row.
Long-press on a chip removes that term. Only a submit or an opened result
keeps a term ([Recent searches](./behavior.md#recent-searches)). The recent
terms are the account's `searchHistory` app-config entry, shared with mobile
and tv-native, so remove any proof terms afterwards:

```bash
adb -s emulator-5554 shell input text 'tears' && adb -s emulator-5554 shell input keyevent KEYCODE_ENTER
adb -s emulator-5554 exec-out uiautomator dump /dev/tty | grep -oE 'content-desc="(Open|Search again for) [^"]+"' | head
```

`adb shell input text` reaches the field without the IME, which is how a
headless proof types; the recorded proof drives Gboard with D-pad keys.

## TV Search history proof

Behaviour: [Recent searches](./behavior.md#recent-searches).
`TvSearchHistoryProofTest` (`tv/src/androidTest`) mounts the production TV session
and signed-in shell on fake repositories, with the production recent-search
store over an in-memory `/config` that logs every write. It types a query one
key at a time past the debounce (nothing kept), opens a result (kept), submits
another, clears them from Settings, disables history (a submitted term is not
kept) and turns it back on, then asserts the exact `searchHistory` and
`searchHistoryEnabled` writes. It makes no API calls, so report it as
controlled-state proof.

```bash
./gradlew :tv:assembleProductionDebug :tv:assembleProductionDebugAndroidTest
adb -s emulator-5554 install -r tv/build/outputs/apk/production/debug/tv-production-debug.apk
adb -s emulator-5554 install -r tv/build/outputs/apk/androidTest/production/debug/tv-production-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.tv.TvSearchHistoryProofTest \
  -e putio.tv.searchHistory.enabled true -e putio.tv.searchHistory.runId "$(uuidgen)" \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Screenshots go to `tv-search-history-proof-<UUID>/`: `01` the stored term, `02`
the results after slow typing with nothing kept, `03` the opened result kept,
`04` the submitted term kept, `05` Settings, `06` cleared, `07` history off
with nothing kept, `08` Settings while off, `09` kept again once on. Remove
the screenshot directory afterwards.

## Copy proof

Behaviour: [History events](./behavior.md#history-events),
[Storage quota](./behavior.md#storage-quota), [Trash](./behavior.md#trash).
`MobileCopyProofTest` (`mobile/src/androidTest`) mounts mobile History, Account and
Trash on controlled state; `TvCopyProofTest` (`tv/src/androidTest`) mounts the
production TV session and signed-in shell on fake repositories whose History
mixes every kind of event. Neither makes API calls, so report them as
controlled-state proof.

```bash
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.MobileCopyProofTest \
  -e putio.copy.enabled true -e putio.copy.runId "$(uuidgen)" \
  io.put.putio.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.tv.TvCopyProofTest \
  -e putio.tv.copy.enabled true -e putio.tv.copy.runId "$(uuidgen)" \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Mobile screenshots go to `copy-proof-<UUID>/`: `01` History after asserting
each event's copy, `02` history off, `03` and `04` the quota used and free,
`05` and `06` Trash with items and empty. TV screenshots go to `tv-copy-proof-<UUID>/`: `01` History
with only the shared file and completed transfer, `02` the quota used, `03`
Trash's retention line, `04` history off, `05` the quota free. Remove the
screenshot directories afterwards.

## TV Search and History opens proof

Behaviour: [History opens](./behavior.md#timestamps-and-history-opens).
`TvExternalOpenProofTest` (`tv/src/androidTest`) mounts the production TV session and
signed-in shell (`TvSessionShell`) on fake repositories and a caller-owned
local video, then drives them with D-pad keys: open Movies in Files, type a
query in Search, Center on the video result (resume prompt from 00:45,
Continue, playing), Back twice to that result, focused, Center on `notes.pdf`
(its folder, Documents, with the row focused), Back to that result, Center on
the Documents folder, Back to it, and Files still shows Movies. It makes no API
calls, so report it as controlled-state proof. The app cannot read a directory
adb creates under `Android/data`, so push the fixture to `/data/local/tmp`; the
test copies it into its own files directory.

```bash
./gradlew :tv:assembleProductionDebug :tv:assembleProductionDebugAndroidTest
adb -s emulator-5554 install -r tv/build/outputs/apk/production/debug/tv-production-debug.apk
adb -s emulator-5554 install -r tv/build/outputs/apk/androidTest/production/debug/tv-production-debug-androidTest.apk
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=90 -f lavfi -i sine=frequency=440:duration=90 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest tv-open-proof.mp4
adb -s emulator-5554 push tv-open-proof.mp4 /data/local/tmp/tv-open-proof.mp4
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.tv.TvExternalOpenProofTest \
  -e putio.tv.open.enabled true -e putio.tv.open.runId "$(uuidgen)" \
  -e putio.tv.open.fixture /data/local/tmp/tv-open-proof.mp4 \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Screenshots go to `tv-open-proof-<UUID>/`: `01` Movies, `02` the results,
`03` the resume prompt, `04` playing, `05` back on the played result, `06` the
document in its folder, `07` back on the document result, `08` the folder,
`09` Files still on Movies. Remove `/data/local/tmp/tv-open-proof.mp4`, the
copied `tv-open-fixture.mp4` and the screenshot directory afterwards.

`TvExternalOpenProofTest#backOnTheDrawerReturnsToThePaneThenThePaneRulesApply`
proves [TV Back](./behavior.md#tv-back) on the same controlled shell and needs
no fixture. On Search, an empty History (its Clear button), Account and a
folder in Files, Back on the drawer returns focus to the pane's row; the next
Back returns to the focused Files row, pops the folder, and finally leaves from
the root (a handler registered before the shell stands in for the system). Run
it with the same instrument command, selecting the method with `#` and dropping
`-e putio.tv.open.fixture`. Screenshots `10`–`21` go to the same
`tv-open-proof-<UUID>/` directory.

`TvExternalOpenProofTest#aPickOnALaterPageOfItsFolderOpensFocusedOnIt` proves
the reveal beyond the first page on the same shell, also without a fixture:
Sample folder lists 3 pages of 50 rows, each later page served after 1.5 s,
with the picked document on the third. Center shows the folder loading (`23`),
Back during it returns to the result with the folder gone (`24`), and Center
again opens the folder focused on the document (`25`) with the row above it
loaded (`26`).

## TV History proof

History is the third drawer destination and, as in tv-native, lists only
shared files and completed transfers. Focus enters on the first event row;
Up from it reaches Clear, Left from anything returns to the drawer. Rows are
grouped under Today, Yesterday, Last week, Last month, and Earlier, and show
the event's kind with its relative time, or its date once it is more than a
week old. Center on an event that names a file opens it as a Search result
does; an event without a file is a row the D-pad can rest on. Clear opens a centred confirmation with stacked buttons
and focus on Cancel; confirming removes the account's whole history, shared
with mobile and the web, so only clear on a proof account:

```bash
adb -s emulator-5554 exec-out uiautomator dump /dev/tty | grep -oE 'content-desc="Open [^"]+"' | head
```

The pane is disabled when the account's `history_enabled` setting is off and
names Account's Keep account history switch. TV's own Account toggle flips the
pane as soon as the setting is confirmed; a change made on mobile or the web
reaches it on the next session validation.

## TV Files actions proof

"Oracle" below is the 34-capture TV behavior oracle that
[#33](https://github.com/putdotio/putio-android/issues/33) gates parity on.

Long-press Center or press Menu on a Files row for the oracle's files-actions
state: a centred dialog titled with the file's name, one full-width button per
action and Cancel last, the first action focused; a row with no actions (a
shared item that is not media, or a text row before the trash setting is
confirmed) opens nothing, as in tv-native. Open in VLC hands the
original `/files/{id}/stream` URL to `org.videolan.vlc` with `ACTION_VIEW`; the
URL carries the account's download token, never the session's access token, and
an account without one gets a notice instead of a URL. A dialog explains when
VLC is not installed. Mark as watched writes the video's
duration as its position and Mark as unwatched clears it; both appear only
when the account's `use_start_from` is confirmed on, and marking watched also
needs a known duration. Move to trash or Delete permanently follows the
confirmed `trash_enabled` setting. Move to trash runs at once; Delete
permanently confirms with Cancel focused. Either then runs the
shared delete operation: its phases, Check status or Retry on failure, and the
fresh listing's verdict show above the rows. Every dialog returns focus to
its row. The shared identity's files are the fixture, so create a throwaway
file before proving a deletion and read the watched state back:

```bash
PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation files.getStartFrom --args '[<id>]' --execute --output json
```

## TV Account proof

Account per oracle captures 09–12 and 14: the avatar, username, quota bar
("X of Y free" with `show_optimistic_usage` on, "X of Y used" otherwise) and
Sign out button in the header, then Playback settings, Storage
settings and App and device information as full-width rows, with Sign out as
the final row. Focus enters on Choose your proxy once account settings load,
and on the header's Sign out until then. Switches save through the shared
`AccountSettingsController` (account-wide `/account/settings`) and
`AndroidAppConfigController` (this app's `/config`); a failed save keeps the
row on the server value and offers Try again above the section. Choose your
proxy loads `/tunnel/routes` when it opens and lists the direct route first;
Video playback type lists MP4 above HLS (default) as the oracle does. Turning
Trash off confirms first with Cancel focused. Keep account history flips the
History pane. Video playback buffer size stays out: TV always buffers as
tv-native's default ([TV playback](./behavior.md#tv-playback)).
Every dialog returns focus to the row that opened it. Settings are the shared
test identity's, so read them before a proof and restore what you flip:

```bash
PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation account.getSettings --execute --output json
```

## TV Trash proof

Trash opens from Account → Manage your trash; Back returns to that row. Focus
enters on the first row, or Refresh while there are none; Up from the first
row reaches Refresh, Restore all, and Empty trash. Center on a row opens a
choice dialog with Restore and Delete permanently; every mutation confirms
in a centred dialog with focus on Cancel, then reports above the list with
Check status or Check trash until the fresh listing confirms it. Restores
invalidate the Files cache like mobile. The list and the empty state carry web's
14-day copy. Trash contents are the shared test identity's, so list the
items before a proof and never confirm a mutation you have not fixtured:

```bash
PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation trash.list --execute --output json
```

## Account Trash and single-item Restore proof

Availability and recovery rules: [Trash](./behavior.md#trash).

Run `TrashRestoreFixtureTest#acceptsOnlyTheOwnedFourItemFixture` before the live
selector
`AuthenticatedTrashRestoreTest#cancelPreservesTrashAndRestoreMakesTheExactItemAvailable`.
The live selector requires `putio.trash.restore.enabled=true`,
`putio.trash.restore.runId=<UUID>`, and
`putio.trash.restore.fixture=<base64 JSON>` as instrumentation arguments.

Create a unique root container named `android-trash-restore-proof-<UUID>` with
three children: a tiny UTF-8 file (1–128 bytes), an empty Cancel folder, and an
empty sentinel folder. Record every exact ID, name, parent, kind, and mutation
outcome in an ignored ownership ledger before each write. Child names must be
distinct and contain both Unicode and the run UUID. The fixture JSON contains
`runId`, `expectedAccountId`, `fileSize`, and four fields for each of `container`,
`file`, `cancel`, and `sentinel`: `<role>Id`, `<role>Name`, `<role>ParentId`, and
`<role>FileType`. Record the file’s actual `FILE` or `TEXT` kind; folders use
`FOLDER`. The parser rejects ambiguous numbers, duplicate or unknown keys,
invalid UUIDs, and unowned relationships.

Verify the complete owned tree, then trash only the file and Cancel folder with
separate one-ID `files.delete(skipTrash=false)` requests. Leave the account
setting unchanged. Locate each target in at most four Trash pages of 50 items.
Cancel must dispatch zero Restore calls; the live repository counter proves this
at the app boundary. The confirmed file Restore dispatches once. App availability
checks allow at most 14 exact GETs, spaced two seconds apart, within 60 seconds;
reserve the fifteenth read for independent caller verification within that same
deadline and spacing. A queued or
uncertain outcome retains the ledger and must never cause another Restore.

After instrumentation is idle, reconcile all potential worker outcomes before
cleanup. Restore the Cancel folder with one separately recorded request and
wait for authoritative availability. Recheck each live owned leaf and empty
folder before permanent deletion, verify exact GET 404, then delete the empty
container last. Unknown errors are not 404. Keep the ledger and container when
an operation remains unresolved; never empty Trash or delete a parent that may
receive a queued restoration.

The synthetic selectors
`TrashRestoreUiProofTest#queuedRestoreRetainsRecoveryAndRetriesOnlyReads` and
`TrashRestoreUiProofTest#ambiguousRestoreAndAuthenticationFailureNeverRepeatMutation`
use `putio.trash.restore.ui.enabled=true` with the same UUID. They mount the real
shell with controlled repositories and make no API calls. Screenshots go to the
`trash-restore-proof-<UUID>/` run directory. Keep synthetic images separate
from the live recording.

## Live audio attachment without source resolution

`MobileLiveAudioAttachmentProofTest#shellRequestReopensLiveAudioWithoutResolvingItsSource`
uses the real `MobilePlaybackService` and MediaController with a caller-owned
local two-track audio fixture. It mounts the production shell with a controlled
account and a counting playback repository that always fails source resolution.
A `NowPlayingRequests` signal exercises the shell's notification-request path:
opening controls and reopening after Back must make zero resolver calls, retain
position, speed and the selected audio track, and support Play/Pause. This proves
real service attachment through shell navigation; it does not tap the Android
notification, exercise MainActivity intent delivery, or simulate process death.

Required instrumentation arguments are:

- `putio.audio.attach.enabled=true`
- `putio.audio.attach.runId=<UUID>`
- `putio.audio.attach.fixture=<absolute device path>`

The fixture must be readable beneath the app's external files directory, contain
two supported audio tracks, and last long enough to start at 30 seconds and
complete the flow. The existing 180-second two-track playback-options fixture
at `/sdcard/Android/data/io.put.putio.mobile.debug/files/playback-options-fixtures/audio.m4a`
can be reused. No API fixture or credential is needed, and the synthetic shell
never initializes authentication. The test refuses to replace any existing
session media item. Cleanup stops playback only while the current item still
matches the run's owned media ID, releases its controller, and leaves the local
fixture and account storage intact.

Screenshots `attached-without-resolve.png` and `reopened-without-resolve.png` go to
the `live-audio-attachment-proof-<UUID>/` run directory. Describe them as real
local-service playback with controlled shell requests, not notification-tap or
live-API evidence.

## Resume and position reporting proof

Behaviour: [Resume and position reporting](./behavior.md#resume-and-position-reporting);
`PlaybackPositionWriterTest`, `PlaybackPositionObserverTest` and
`MobilePlaybackReportingTest` pin the reporting rules on the JVM.

For live proof, use the CLI's explicit `devs-auto` profile to upload a short audio
file and a video in a uniquely named owned folder. Record exact IDs, names, kinds,
initial `start_from` values and ownership before setting a positive saved position.
Open each owned file in the real shell; exercise Resume, Start over, pause, Back,
and background audio with notification controls. Read back exact saved positions
through the CLI after the reporting interval and pause. Keep shared account
settings unchanged. Restore initial positions and delete only owned fixtures
after confirming playback and instrumentation are idle. Capture the prompt and
its playback outcome. Local test-player proof does not establish live API
write-back or service ownership.

## Immersive landscape video proof

`MobileFullscreenVideoProofTest#landscapePlaybackUsesDirectControlsAndRestoresThePreviousWindow`
uses real local Media3 video in a debug-only Activity. The host handles orientation
configuration changes so the production window policy can rotate its real window
without losing the test's injected composition. It verifies a portrait window
with visible system bars becomes landscape with both bars hidden, and Back restores
the original orientation and bar visibility. Saved-state recreation uses Compose's
`StateRestorationTester`; this does not claim full Activity or process recreation.
`portraitVideoKeepsAnUnlockedPortraitWindow` runs only with
`putio.video.fullscreen.portraitFixture` (any portrait MP4 under the same
directory, for example `ffmpeg -f lavfi -i testsrc2=size=720x1280:rate=30:duration=20
-f lavfi -i sine=duration=20 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest portrait.mp4`): from an
unlocked portrait window, the video plays full-screen in portrait with the bars
hidden and no orientation request, and Back restores the bars.

Required arguments are:

- `putio.video.fullscreen.enabled=true`
- `putio.video.fullscreen.runId=<UUID>`
- `putio.video.fullscreen.fixture=<absolute device path>`

Supply a caller-owned landscape MP4 beneath the app's external files directory,
with two supported audio tracks carrying distinct language labels and one embedded
caption track. Use a 180-second fixture with the visible cue `Rehearsal caption proof`
spanning the clip. AAC audio with `eng`/`deu` language metadata and an `eng` mov_text
caption track exercise the intended path. The test declares `PlaybackSubtitles.None`
for the source, so caption controls must come from tracks discovered by the player.

The proof opens the direct Audio, Captions and speed controls, selects the second
audio track, 1.5× and the caption track, then verifies selected tracks and actual
cue text survive saved-state recreation. It checks Off clears the cues and Automatic
restores them. It does not initialize authentication, use an API fixture, clear
account storage, or stop a preexisting audio service; its player factory builds
an ExoPlayer with the production renderers and audio attributes, because the
production private video player streams through the download cache's HTTP source,
which cannot read a local file, and disables the service-stop hook.

Screenshots go to `fullscreen-video-proof-<UUID>/`: initial landscape controls,
selected captions, the speed/audio/captions sheets, restored selections,
Automatic captions and the restored portrait window, plus the portrait video's
window and controls.

The local Sintel fixture derives from the Blender Foundation's
[720p trailer](https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4),
with a cropped and looped excerpt, replacement test tones and synthetic captions.
Publishing its screenshots or clips requires attribution: “© copyright Blender
Foundation | durian.blender.org”, with the [CC BY 3.0 sharing terms](https://durian.blender.org/sharing/).

## Picture-in-picture proof

Behaviour: [Picture-in-picture](./behavior.md#picture-in-picture). `MobilePictureInPictureProofTest`
mounts the real player screen in MainActivity, so the app's manifest declarations are the ones on
trial, plays a caller-owned local MP4 with automatic captions on the real frame clock, and drives
the system window with Home and taps on the system's menu. It makes no API calls and never reaches
authentication; report it as synthetic proof.

- `homeEntersTheWindowWhichKeepsPlayingObeysItsControlsAndExpandsWherePlaybackIs`: Home enters
  the window, playback advances and the position observer reports from it, the menu's Pause holds
  the position, Play resumes, and Expand returns to the same Activity and player, playing on past
  where it expanded, straight to landscape with no portrait configuration on the way.
- `closingTheWindowStopsTheVideoAndTheNextVisitFindsItPaused`: Close stops the Activity and writes
  the position; relaunching from the launcher builds a new player that waits, paused, within one
  second of it.

Opt in with `putio.pip.enabled=true`, `putio.pip.runId=<UUID>` and `putio.pip.fixture=<path>` under
the app's external files directory. On a fresh install that directory exists only once the app
creates it, so run the instrumentation once first: it creates the directory and fails on the
missing fixture. Never create it with `adb shell mkdir`; a shell-owned directory is unreadable to
the app.

```bash
./gradlew :mobile:assembleProductionDebug :mobile:assembleProductionDebugAndroidTest
adb -s emulator-5554 install -r mobile/build/outputs/apk/production/debug/mobile-production-debug.apk
adb -s emulator-5554 install -r mobile/build/outputs/apk/androidTest/production/debug/mobile-production-debug-androidTest.apk
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

Start `scripts/evidence.sh record --allow-dark` just before the instrumentation for the clip, and
append `#<method>` to the class to record one flow. Screenshots go to `pip-proof-<UUID>/`: `01`
to `05` for the first flow (playing, the window playing, paused from the window, expanded,
expanded with the controls showing the position) and `10` to `12` for the second (the window
playing, closed, reopened and paused). The menu's buttons are found once by their accessibility
descriptions (Pause, Expand, Close); the accessibility window list drops the menu after its first
use, so later presses reuse those places and check the player's or the Activity's state. A fresh
device's one-time immersive-mode hint is acknowledged if it shows. `am instrument` force-stops the
app first, and Home, the taps and the relaunch act on the whole device, so use an emulator you
booted with no other proof running. Remove the fixture and the screenshot directories afterwards.

## Mobile share-in proof

Behaviour: [Share-in](./behavior.md#share-in). Never use real credentials in
share proof artifacts.

Prove browser share → editable sheet → Add using a caller-owned transfer fixture;
also exercise cancellation, a second share while editing, and rotation before
confirmation. Follow the existing session-preserving installation and fixture
cleanup rules. The controlled `MobileShellAccessibilityProofTest` also exercises
both replacement choices at 200% font size in portrait and landscape, checks the
entire confirmation message is reachable, and asserts that choosing a draft
submits no transfer. The share proof captures the "1 transfer added" snackbar
after a successful Add.

Magnet and torrent intake need no account to prove the boundary: with the debug
app in any controlled state, open a fake magnet and confirm the sheet shows it
unsubmitted (signed out, it appears after sign-in):

```bash
adb shell am start -a android.intent.action.VIEW \
  -d 'magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Harbor%20film' \
  -p io.put.putio.mobile.debug
```

`MobileTransferIntakeProofTest` then proves the sheet without an account: the
same magnet VIEW intent launches the production `MainActivity`, and its draft
drives the production Transfers screen and controller over
`SdkTransfersRepository` with faked SDK calls and a fake Files repository
holding "Sample folder". It makes no API calls; report it as synthetic proof.
Opt in with `putio.transfers.enabled=true` and `putio.transfers.runId=<UUID>`
and require `OK (1 test)`. Screenshots land in `transfer-intake-proof-<UUID>/`:
`01-magnet-draft-unsubmitted`, `02-save-to-picker`, `03-save-to-sample-folder`,
`04-magnet-added` (the add carried `save_parent_id`), `05-refused-link-kept`
(add-multi refused one of two links) and `06-torrent-draft`; the test then adds
the torrent and checks the upload sends `torrent=true` and the folder.
`MainActivityShareTest` covers the `.torrent` content-URI read on the JVM.

## Mobile share-out and deep links proof

Behaviour: [Share-out](./behavior.md#share-out) and
[Deep links](./behavior.md#deep-links); `MobileFileShareServiceTest`,
`MobileDeepLinksTest` and `MainActivityDeepLinkTest` pin them on the JVM.

Prove on the signed-in API 37 emulator:

```bash
adb shell am start -a android.intent.action.VIEW -d putio://transfers
adb shell am start -a android.intent.action.VIEW -d https://app.put.io/files/<id> -p io.put.putio.mobile.debug
```

Then open a small image's action sheet, choose Share file, and confirm the
chooser shows the content preview. Inspect `dumpsys activity activities` for the
chooser: `clip=` must reference only a `content://` URI and no `oauth_token`.
For the tablet rail layout, run the same flow after `adb shell wm size 1600x2560`
and `adb shell wm density 320`, then restore with `wm size reset` and
`wm density reset`.

### Share-out session proof

`MobileShareSessionProofTest` runs the real `MobileFileShareService` and
`MainActivity` with a controlled session and an in-process download source (an
OkHttp interceptor serving a generated JPEG); it makes no API call and needs no
account. A control export must open the chooser; a second export is held
mid-download while the session signs out, and must remove the share folder and
open no chooser. Follow the [Evidence](#evidence) contract with
`putio.share.session.enabled=true` and `putio.share.session.runId=<UUID>`.
Screenshots `control-chooser-opens.png`, `export-running.png` and
`signed-out-no-chooser.png` go to `share-session-proof-<UUID>/`. Describe them as
synthetic session proof, not a live sign-out.

## Downloads and offline playback proof

Behaviour: [Downloads and offline playback](./behavior.md#downloads-and-offline-playback);
`DownloadsControllerTest`, `MobileDownloadStoreTest`, `MobileDownloadEngineTest`,
`MobileDownloadNotificationsTest`, `OfflinePlaybackPositionsTest`,
`UserScopedCacheKeysTest` and `OfflinePlaybackRepositoryTest` pin the engine,
queue, notification, cache-key and index rules on the JVM.

Prove on the API 37 emulator with the shared `devs-auto` account: download a
small root video from its Files actions sheet, keep the Downloads screen open
and confirm its row advances about once a second, wait for `On this device` in the
Files row and the Downloads screen, then enable airplane mode with
`adb shell cmd connectivity airplane-mode enable` and play it from Downloads.
Cut the network during a larger download and confirm the row reads
`Waiting for network`, then restore it and confirm the download completes by
itself. Inspect `databases/exoplayer_internal.db` afterwards and require zero
`oauth_token` occurrences and a `u<userId>|` prefix on every
`ExoPlayerCacheIndex*` key; the cached playlist bodies are server text and are
expected to contain the token. Delete the local copy from the Downloads sheet
and confirm the cache directory shrinks. Clear only `databases/exoplayer_internal.db*`,
`shared_prefs/io.putdotio.android.downloads.xml` and the internal
`files/downloads/` between runs; never wipe app data or the session.

For the queue, start four or more downloads with the limit at 2 and confirm two
rows download while the rest read `Queued · #n in line` in the order started;
raise the limit and confirm more start. Force low storage with
`adb shell cmd devicestoragemonitor force-low -f`, confirm the rows read
`Waiting for free storage`, then `adb shell cmd devicestoragemonitor reset` and
confirm they resume. Select several rows, delete them, and confirm with the CLI
that the originals are untouched. With `adb shell pm revoke <package>
android.permission.POST_NOTIFICATIONS` (the app is killed; relaunch it), a
finished download posts nothing and Downloads shows the notice; after Turn on or
`pm grant`, the next one posts a notification whose tap opens its row. For a
missing copy, force-stop the app, remove the internal `files/downloads/`
content with `run-as`, relaunch, and confirm the row reads missing and offers
Download again. For offline resume, play a downloaded video online and leave
it partway, enable airplane mode, reopen it, accept Resume, play further and go
Back; disable airplane mode and read the saved position back with the CLI.

Without a live account, two opt-in lanes mount the production Downloads screen,
controller and engine over a real Media3 manager and progressive downloader
reading generated local files; each uses its own index database and cache
directory, makes no API calls and leaves the session alone, so report them as
synthetic proof. Follow the [Evidence](#evidence) contract.

- `MobileDownloadsProgressProofTest` reads one 12 MB file at about 1 MB/s. Opt in
  with `putio.downloads.progress.enabled=true` and
  `putio.downloads.progress.runId=<UUID>`, record the screen while it runs, and
  require `OK (1 test)`: it fails unless the row shows at least five distinct
  byte counts before `On this device`. Screenshots land in
  `downloads-progress-proof-<UUID>/`.
- `MobileDownloadsQueueProofTest` queues five files at a limit of 2, one of
  which fails for storage until retried. Run it after
  `adb shell pm revoke <package> android.permission.POST_NOTIFICATIONS`, with
  `putio.downloads.queue.enabled=true` and `putio.downloads.queue.runId=<UUID>`,
  on an emulator you booted: it forces low storage with
  `cmd devicestoragemonitor force-low -f` and resets it, also on failure. It
  requires two running and three waiting rows in order, every row waiting for
  storage and resuming once it clears (the platform rechecks about once a
  minute), the failure with no notification while the permission is denied, a
  finished-download notification after the test grants it and retries through
  the row sheet, and a bulk delete through Select all. It leaves the permission
  granted, restores the concurrency setting and removes only its own index
  rows. Screenshots land in `downloads-queue-proof-<UUID>/`.

## Shared-with-me items proof

Behaviour: [Shared-with-me items](./behavior.md#shared-with-me-items).
`MobileSharedItemsProofTest` mounts the production Files route and controller
on a faked repository. The synthetic root lists the shared root, a friend
folder, a shared folder, a shared video, an owned video and an owned
destination folder. It makes no API calls, so report it as synthetic proof.
Opt in with `putio.shared.enabled=true` and `putio.shared.runId=<UUID>` and
require `OK (1 test)`. Screenshots land in `shared-proof-<UUID>/`:

- `01-list`: no actions button on the shared root or the friend folder
- `02-shared-file-actions`: Download, Share file and Make a copy only
- `03-copy-picker`: the move picker at root
- `04-copying`: the copy line while the check is held
- `05-copied`: the line after the faked check reports done
- `06-owned-file-actions`: Rename, Move, Move to trash, and no Make a copy

## Move and copy target folder proof

Behaviour: [Move and copy target folder](./behavior.md#move-and-copy-target-folder).
`MobileMoveTargetProofTest` mounts the production Files route and controller
on a faked repository with the on-device `MobileMoveTargetStore`, which it
clears before and after the run. It makes no API calls, so report it as
synthetic proof. Opt in with `putio.movetarget.enabled=true` and
`putio.movetarget.runId=<UUID>` and require `OK (1 test)`. Screenshots land
in `move-target-proof-<UUID>/`:

- `01-move-default-root`: the picker at root with the toggle off
- `02-remember-on-chosen-folder`: the toggle on, at a nested folder
- `03-move-reopens-at-remembered`: the next Move opens there
- `04-back-reads-parent`: Back reads the folder above it
- `05-copy-reopens-at-remembered`: Make a copy opens there too
- `06-after-sign-out-root`: after the sign-out cleanup, root with the toggle off
- `07-missing-folder-root`: a remembered folder that can't be read opens at root
- `08-move-opens-inside-moved-folder`: a folder remembered before it ended up
  two levels inside the folder being moved still opens there
- `09-move-into-itself-refused`: Move here gets put.io's 403, and the outcome
  says a folder can't be moved into itself

## Public links proof

Behaviour: [Public links](./behavior.md#public-links).
`MobilePublicLinksProofTest` mounts the production Files route, the public
links controller and sheet, the system share chooser, and Account's list on
faked repositories. It makes no API calls, so report it as synthetic proof; a
live run needs the app signed in to `devs-auto` through Auth Tab, which takes
the account's web sign-in. Opt in with `putio.publiclinks.enabled=true` and
`putio.publiclinks.runId=<UUID>` and require `OK (1 test)`. Each step holds for
about a second so a recording started beside it shows it. Screenshots land in
`public-links-proof-<UUID>/`:

- `01-shared-file-no-exclusive-access`: a friend's file has no Exclusive access
- `02-owned-file-actions`: an owned file's sheet offers it
- `03-sheet-existing-link`: the item's existing link with Create link
- `04-link-created` and `05-copied`: a new link, then Copy link
- `06-share-chooser`: the chooser with the file name and address only
- `07-revoke-confirm` and `08-revoked`: Revoke confirms, then the link is gone
- `09-daily-limit`: web's copy for put.io's daily limit
- `10-account-list`: Account's list of every link

## Transfer retry proof

Behaviour: [Transfer failures and retry](./behavior.md#transfer-failures-and-retry).
`MobileTransferRetryProofTest` runs the production Transfers screen and
controller over `SdkTransfersRepository` with faked SDK calls: one failed
transfer with a server `error_message`, one without, one downloading. Retry
succeeds for the first and gets a 403 for the second. It makes no API calls;
report it as synthetic proof. Opt in with `putio.transfers.enabled=true` and
`putio.transfers.runId=<UUID>` and require `OK (1 test)`. Screenshots land in
`transfers-proof-<UUID>/`: `01-failure-reasons`, `02-retry-accepted` (no
dialog, "Retrying transfer" snackbar), `03-retry-rejected` (snackbar, no error
dialog).

## Refused request proof

Behaviour: [Refused requests](./behavior.md#refused-requests).
`MobileRefusedRequestProofTest` mounts mobile Files on a failure built from
put.io's error body in-process; it makes no API calls, so report it as
controlled-state proof. Install the mobile debug app and instrumentation APKs,
then:

```bash
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.MobileRefusedRequestProofTest \
  -e putio.refused.enabled true -e putio.refused.runId "$(uuidgen)" \
  io.put.putio.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Require `OK (1 test)`. Screenshots land in `refused-proof-<UUID>/`:
`01-files-refused` (put.io's 400 reason) and `02-files-server-error` (a 503
keeps the app's copy). Remove the directory afterwards.

## Transfers polling CPU benchmark

`TransfersPollingCpuBenchmark` replays the deterministic Transfers histories
from `domain/transfers/src/testFixtures` on a device, with no network or account, and logs the
median thread CPU and wall time per poll for the pre-by-id list walk and the
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
Results live in [Behaviour](./behavior.md#transfers-polling).
