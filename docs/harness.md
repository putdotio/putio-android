# Harness

Emulator, launch-proof, evidence, live-API, and headless operating notes for
this repo. The root [Agent Guide](../AGENTS.md) owns toolchain, build, verify,
and the definition of done.

## Emulators

Two reusable AVDs, arm64 on Apple Silicon (x86_64 elsewhere):

| AVD | Device profile | System image |
| --- | --- | --- |
| `putio-phone` | pixel_7 | `android-37.0;google_apis_playstore` |
| `putio-tv` | tv_1080p | `android-36;android-tv` |

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
transport is unavailable. TV and the CI managed device remain API 36.

Bootstrap never replaces a mismatched AVD. It fails with an explicit command;
stop and delete that exact profile yourself before rerunning bootstrap.

## Launch Proof

One command from source to a verified, evidenced launch:

```bash
./scripts/prove.sh mobile            # build → boot → install → launch → verify → screenshot → teardown
./scripts/prove.sh tv --record       # also captures a screen recording
```

Verification is the instrumented smoke test (`LaunchSmokeTest`), run through
`connectedAndroidTest` on the booted emulator. It asserts RESUMED state, a
3 s stability window, and real composited pixels (mean luma) via platform
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

Flags: `--keep` (leave emulator running), `--ephemeral` (throwaway AVD,
deleted on exit), `--window` (headed), `--skip-build`, `--record`,
`--seconds N` (recording length, 3 to 180).

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

### Mobile accessibility proof

`MobileAccessibilityProofTest` mounts controlled production auth, Files,
Search, Transfers, Trash and Settings surfaces without API calls or session
changes. Its three selectors exercise named actions, sheets, dialogs and the
real keyboard. Set the API 37 emulator's system `font_scale` to `2.0` and all
three global animation scales (`animator_duration_scale`,
`window_animation_scale`, `transition_animation_scale`) to `0` before running.
Record their exact prior values, including absent keys, and restore them after
proof. This lane proves large-font reachability with animations disabled;
Compose semantics assertions do not establish actual TalkBack speech.

Install the mobile production debug app and test APKs with `adb install -r`.
Invoke only the named class through bounded `am instrument`, passing
`putio.accessibility.enabled=true` and `putio.accessibility.runId=<UUID>`.
Require `OK (3 tests)`. Screenshots land in `accessibility-proof-<UUID>/`
under the target app's external files directory. Follow the session-preserving
invocation and guest-idleness rules below; do not use `connectedAndroidTest`
or `prove.sh` on the authenticated installation.

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
Require `OK (1 test)` per invocation; preserve and restore caller settings.

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
Use the same API 37, font scale 2.0, disabled-animation and actual TalkBack setup,
with a fresh UUID and the existing `putio.accessibility.enabled=true` and
`putio.accessibility.runId=<UUID>` instrumentation arguments. Install with
`adb install -r`, then invoke this exact selector through bounded `am instrument`;
no media fixture arguments are needed.

Follow `talkback-choices-stage.txt` under `accessibility-proof-<UUID>/`: activate
Sign in at `sign-in`, Resume at `resume`, and Start over at `start-over`.
The fixed examples are “Rehearsal video.mp4” at 01:23 and “Rehearsal audio.mp3”
at 00:42. Each stage advances only after its real callback fires exactly once;
a wrong choice or dismissal fails. `talkback-choices-state.txt` records the
observed action sequence. Require `finished` plus `OK (1 test)`. Each phase is
bounded to 120 seconds and the whole walkthrough to 400 seconds. Capture and
inspect the actual TalkBack utterances and host recording; callback assertions
alone do not prove speech. Preserve and restore device settings and the account
session using the same host-supervision rules as the player lane.

`MobileTalkBackSessionProofTest#talkBackControlsNowPlayingAndSeeksPrivateAudio`
mounts the production shell around one private, muted audio player. It connects
no account runtime or media-session service. Use the same accessibility opt-in
and device settings, a fresh run UUID, and `putio.accessibility.audio` pointing
to caller-owned audio beneath the app's external files directory. The fixture
must last at least 300 seconds: playback starts at 30 seconds and may run through
the preparation and two playing phases at their full budgets before the host
pauses it. Install with `adb install -r` and invoke this
exact selector through bounded `am instrument`.

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
the installed account session. Install the debug app and test APKs with
`adb install -r`, then invoke this exact class through `am instrument` with
`putio.playback.options.enabled=true`, `putio.playback.options.runId=<UUID>`,
and `putio.playback.options.audio` / `putio.playback.options.video` set to
readable fixture paths under the app's external files directory.

Use 180-second AAC audio and H.264 video fixtures containing two differently
labeled audio tracks. The tests capture clean audio/video layouts and settings sheets, select 1.5× speed
and the second track, check
the real player state, recreate the video player, and reconnect to background
audio. Lifecycle and saved-state transitions use a controlled Compose host;
this is local-media proof, not live API or full Activity-recreation proof.
Screenshots go to `playback-options-proof-<UUID>/` under external files.
Validate and inspect them before publishing, and remove only the caller-owned
fixtures after instrumentation is idle. The audio test stops its playback at
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

Never commit evidence files. Eyeball each validated capture before publishing
it with the wrapper. The wrapper selects installed `attach` first, falls back
to `gh attach`, rejects quarantined files, and never invokes login or touches
`gh auth`:

```bash
./scripts/publish-evidence.sh .evidence/<file> --pr <number>
```

Follow the installed `attach-cli` skill for login and safe handling; use
`--markdown` only when the PR needs an inline embed. The harness deliberately
fails closed on a missing CLI, custom deployment client id, authentication, or
allowlist error while preserving the validated local capture.

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

- `--headless` boots with `-no-window -gpu swiftshader_indirect -no-audio -no-boot-anim`. Software rendering is slower than `auto-no-window` but deterministic; host-GPU headless mode intermittently composites app windows black, which fails the pixel assertion. `screencap`/`screenrecord` capture fine without a window
- Cold headless boots regularly ANR the emulator's own `com.android.systemui`; harness boots set `hide_error_dialogs 1` so the dialog cannot sit over captures. App crashes and ANRs still fail the instrumented proof
- macOS needs Hypervisor.framework (default on Apple Silicon); Linux devboxes need KVM (`emulator -accel-check`). Without acceleration arm64 images are unusably slow
- First boot of a fresh AVD is the slow path (~1 min on an M-series Mac); subsequent boots are faster with `-no-snapshot` still enforced for reproducibility
- On shared machines check `scripts/emulator.sh status` before assuming a free console port; the scripts scan 5554–5584


## Authenticated rename proof

After authenticating the mobile production debug app as `devs-auto`, run the
Files rename flow without `connectedAndroidTest` or `prove.sh`:

```bash
./gradlew --no-daemon :app:proveAuthenticatedRename \
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
240-second deadline after assembly. Instrumentation output is limited to 1 MiB
before decoding. A failed, skipped, absent, interrupted,
or incomplete named test fails the task. Recording starts before instrumentation
and uses a unique guest path with a checked process ID. Cleanup reaps owned host
processes and the verified recorder, and removes only that run's guest capture
files. It reaps adb clients without their descendants: a client can start the
shared adb server. SDK, CLI, and validation helpers retain tree cleanup.
Recorder shutdown checks the guest PID independently of the host adb
client, waits for graceful exit, and escalates only while ownership still matches.
Missing recorder ownership fails cleanup and preserves its capture files.
It never force-stops the app. If instrumentation remains active, even with
the same runner component, cleanup preserves it and explicitly fails: API 37
process dumps can hide arguments in a parcelled Bundle, so the harness cannot
establish which invocation owns that instrumentation. Interruption therefore does
not guarantee remote instrumentation has stopped; inspect the reported state
before another run.

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
`PROOF PASS authenticated-rename`. Inspect the clip before publishing it with the
repository wrapper. No fixture or credential payload is printed by CLI preflight.

## Authenticated Trash/Delete device test

`AuthenticatedFilesDeleteTest#authenticatedDeletePreservesSessionAndCancel`
exercises the current confirmed account mode: browse an empty fixture folder,
sort its parent, cancel one named action, and confirm another. It checks the
exact item through the SDK and verifies Trash membership when appropriate.
It never changes the account's Trash setting. A passing run covers only the
mode recorded in its fixture; folder descendant completion is outside this test.

Build the mobile production debug app and instrumentation APK, install both
with `adb install -r`, and invoke only the named test on an explicitly selected
API 37 emulator with the existing shared-account session. Instrumentation
arguments are `putio.delete.enabled=true`, `putio.delete.runId=<UUID>`, and
`putio.delete.fixture=<base64 JSON>`. The fixture requires `expectedAccountId`,
`containerId`, `containerName`, `actionItemId`, `actionName`,
`expectedTrashEnabled`, `cancelItemId`, and `cancelName`. Use two distinct empty
folders inside one uniquely named, caller-owned container; choose action and
cancel names whose descending order places the cancel item first. IDs must be
positive, the three file IDs and names must be distinct, and the Trash mode
must match the current account. The parser rejects duplicate or unknown keys.

The caller owns bounded process supervision, recording, fixture cleanup, and
emulator lifetime, following the guest-idleness contract above. Track a trashed
item separately: removing its active parent does not remove its Trash entry.
Clean only exact owned IDs; never empty Trash or use ID zero. Preserve fixtures
when guest activity or cleanup outcomes remain unknown.

The separately opted-in
`FilesDeleteRecoveryUiProofTest#uncertainDeleteKeepsItemAndOffersStatusCheck`
uses `putio.delete.ui.enabled=true` and the same run ID to capture controlled
error UI without API calls. Report this as synthetic UI evidence. Both tests
write screenshots below the target app's external files directory at
`delete-proof-<UUID>/`. Pull and inspect them, validate the caller's recording
with the existing evidence command, then publish through the repository wrapper.

`FilesDeleteNavigationUiProofTest#rejectedSearchAndTransferNavigationPreserveRecovery`
uses the same synthetic opt-in and run ID. It mounts the real mobile shell with
controlled reducers: rejected Search/Transfers opens retain the current tab and
Delete recovery; after Check status reconciles the item, a fresh transfer open
succeeds. It makes no API calls and captures `synthetic-navigation.png` under
the same screenshot directory. Invoke this exact named test separately when
refreshing shell navigation proof; it requires no live fixtures.

## Authenticated Move device test

`AuthenticatedFilesMoveTest#authenticatedMovePreservesCancelAndConfirmsExactParents`
exercises named Cancel, a same-name collision, folder and file moves, a move into
a cached ancestor, and a folder move to root. Each confirmed move checks the exact
item ID and destination parent through the SDK. It preserves the existing shared
account session and checks source sort and viewport after Cancel.

Use the same API 37 installation and caller supervision contract as Delete above.
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
operations; report them as synthetic proof. Screenshots are written to `move-proof-<UUID>/` below the target app's
external files directory. Pull and inspect these and the validated recording
before publishing through the repository evidence wrapper.

The debug-only Compose test host handles asset-path configuration changes. API 37
can update asset overlays during a test; recreating the plain `ComponentActivity`
loses the content installed by the test and leaves a blank replacement. This
manifest override applies only to that synthetic host. `MainActivity` keeps its
normal recreation behavior and installs product content in `onCreate`.

## Trash bulk actions

Each Trash row's actions sheet offers Restore and Delete permanently; the
summary offers Restore all and Empty Trash. Every action confirms in a dialog,
submits exactly once, then verifies with one fresh first-page Trash read.
Delete permanently is verified when the item is absent from a complete first
page, or from any first page after an acknowledged request; an uncertain
request whose item is absent from a partial page stays inconclusive, and a
complete page that still lists it settles the outcome as not done. Empty
Trash verifies only a known-empty Trash; a page with items settles it as not
done. Restore all submits the initial snapshot cursor when the server issued
one (so every ID of that listing is covered), otherwise the loaded IDs, and
marks every cached Files folder stale because restored items can land
anywhere. It verifies against that snapshot: a complete page holding none of
the submitted IDs, and (with a cursor) only rows deleted after the newest
loaded one, counts as done even when newer items have since arrived. While an
action is inconclusive or its read failed, Check Trash repeats only the read;
no other mutation is offered. After a failed refresh the loaded snapshot may
be stale, so Restore all and Empty Trash wait for a successful read.

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
adb -s emulator-5554 install -r app/build/outputs/apk/tvProduction/debug/app-tv-production-debug.apk
adb -s emulator-5554 shell am start -n io.put.putio.debug/io.putdotio.android.MainActivity
code=$(adb -s emulator-5554 exec-out uiautomator dump /dev/tty | grep -oE 'Activation code [A-Z0-9]+' | awk '{print $3}')
PUTIO_CLI_PROFILE=devs-auto putio auth approve "$code"
```

The shell appears within one poll interval (3 s). `Get new code` on the
sign-in screen cancels the current attempt and requests another; `Sign out`
under Account revokes the grant and returns to a fresh code. A force-stop and
relaunch must land in the shell without a code: that is the Keystore restore.
`pm clear io.put.putio.debug` drops the stored token. The emulator has no
Fire TV feature flag, so it always links as the Android TV client (6221);
Fire TV (6233) needs the physical device set from #51.

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

Media rows call the playback hook, which is a no-op until #34.

## TV Search proof

Search is the second drawer destination. Focus enters on the pill field;
Center summons the system IME (Gboard TV on the emulator), Search on the IME
submits, and the field also searches 300 ms after typing stops. Down from
the field reaches the recent-query chips, then the result rows and the
Load more control; Left from any of them returns to the drawer. Center on a
row opens it in Files, which jumps to the result's folder. Long-press on a
chip removes that term. The recent terms are the account's `searchHistory`
app-config entry, shared with mobile, so remove any proof terms afterwards:

```bash
adb -s emulator-5554 shell input text 'tears' && adb -s emulator-5554 shell input keyevent KEYCODE_ENTER
adb -s emulator-5554 exec-out uiautomator dump /dev/tty | grep -oE 'content-desc="(Open|Search again for) [^"]+"' | head
```

`adb shell input text` reaches the field without the IME, which is how a
headless proof types; the recorded proof drives Gboard with D-pad keys.

## TV History proof

History is the third drawer destination. Focus enters on the first event row;
Up from it reaches Clear, Left from anything returns to the drawer. Rows are
grouped under Today, Yesterday, Last week, Last month, and Earlier, and show
the event's kind with its relative time, or its date once it is more than a
week old. Center on an event that names a file opens that file's folder in
Files; an event without a file is a row the D-pad can rest on. Clear opens a centred confirmation with stacked buttons
and focus on Cancel; confirming removes the account's whole history, shared
with mobile and the web, so only clear on a proof account:

```bash
adb -s emulator-5554 exec-out uiautomator dump /dev/tty | grep -oE 'content-desc="Open [^"]+"' | head
```

The pane is disabled when the account's `history_enabled` setting is off; the
mobile Account screen toggles it, and the TV pane follows on the next session
validation.

## TV Files actions proof

Long-press Center or press Menu on a Files row for the oracle's files-actions
state: a centred dialog titled with the file's name, one full-width button per
action and Cancel last, the first action focused. Open in VLC hands the
original `/files/{id}/stream` URL to `org.videolan.vlc` with `ACTION_VIEW`; a
dialog explains when VLC is not installed. Mark as watched writes the video's
duration as its position and Mark as unwatched clears it; both appear only
when the account's `use_start_from` is confirmed on, and marking watched also
needs a known duration. Move to trash or Delete permanently follows the
confirmed `trash_enabled` setting, confirms with Cancel focused, then runs the
shared delete operation: its phases, Check status or Retry on failure, and the
fresh listing's verdict show above the rows. Every dialog returns focus to
its row. The shared identity's files are the fixture, so create a throwaway
file before proving a deletion and read the watched state back:

```bash
PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation files.getStartFrom --args '[<id>]' --execute --output json
```

## TV Account proof

Account per oracle captures 09–12 and 14: the avatar, username, "X of Y free"
bar and Sign out button in the header, then Playback settings, Storage
settings and App and device information as full-width rows, with Sign out as
the final row. Focus enters on Choose your proxy once account settings load,
and on the header's Sign out until then. Switches save through the shared
`AccountSettingsController` (account-wide `/account/settings`) and
`AndroidAppConfigController` (this app's `/config`); a failed save keeps the
row on the server value and offers Try again above the section. Choose your
proxy loads `/tunnel/routes` when it opens and lists the direct route first;
Video playback type lists MP4 above HLS (default) as the oracle does. Turning
Trash off confirms first with Cancel focused. Keep account history flips the
History pane. Video playback buffer size stays out: there is no server key.
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
invalidate the Files cache like mobile. The empty state carries the oracle's
14-day copy. Trash contents are the shared test identity's, so list the
items before a proof and never confirm a mutation you have not fixtured:

```bash
PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation trash.list --execute --output json
```

## Account Trash and single-item Restore proof

On mobile, Trash is opened from Account → Manage Trash, including when the
Trash setting is off. Restore acknowledges queueing with “Restore started.” An exact-ID Files
GET must return the selected kind and a valid current parent before the app says
the item is available again. Restored names and parents can change. Pending
recovery survives tab navigation and activity recreation; Check status repeats
only the read. This does not claim persistence across process death.

Run `TrashRestoreFixtureTest#acceptsOnlyTheOwnedFourItemFixture` before the live
selector
`AuthenticatedTrashRestoreTest#cancelPreservesTrashAndRestoreMakesTheExactItemAvailable`.
The live selector requires `putio.trash.restore.enabled=true`,
`putio.trash.restore.runId=<UUID>`, and
`putio.trash.restore.fixture=<base64 JSON>`. Use the same existing authenticated
API 37 installation and caller supervision contract above.

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
shell with controlled repositories and make no API calls. Screenshots go to
`trash-restore-proof-<UUID>/` in the target app’s external files directory. Keep
synthetic images separate from the live recording; inspect and validate media
before publishing through the existing evidence wrapper.

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

Install the app and test APKs with `adb install -r`, then run only this selector
on the existing API 37 emulator. Do not use `connectedAndroidTest` or `prove.sh`
on an authenticated installation. Required instrumentation arguments are:

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
`live-audio-attachment-proof-<UUID>/` below the target app's external files
directory. Pull and inspect them before publishing with the existing evidence
wrapper. Describe them as real local-service playback with controlled shell
requests, not notification-tap or live-API evidence. The caller owns bounded
instrumentation supervision, emulator lifetime, and any recording, following the
session-preserving invocation contract above.

## Resume and position reporting

Fresh audio/video resolution with `use_start_from` enabled and a positive saved
position offers Resume or Start over before preparing the player. The retained
controller keeps that decision across Activity recreation. Live audio attachment
and retained player-error recovery bypass the prompt. Start over starts locally
at zero; it does not immediately reset the server position.

One observer belongs to each actual player: the private video owner or the audio
service. Screens and notification controllers do not duplicate audio reporting.
The observer samples advancing playback every 15 seconds and captures positive
positions on pause, stop, end, error, item replacement and owner exit. Buffering
and same-item seek events do not send immediate writes. The application writer
deduplicates positions within the same second, permits one request in flight and
one latest pending snapshot, and times out a request after 15 seconds. Under slow
requests or rapid file switches, newer snapshots can replace intermediate queued
exit positions. Failed writes keep their typed cause and wait for a new position;
there is no immediate retry loop or completion-percentage reset rule.

Reporting requires an app-issued item lease, the same signed-in session, and a
confirmed enabled resume setting. Pending/failed resume-setting writes suspend
reporting; unrelated setting writes do not. Session exit or disabled/unconfirmed
policy cancels requests and discards pending positions without flushing. The
application owns this policy subscription so task removal does not stop audio
updates. A source resolved with resume disabled receives no reporting lease.
An authoritative authentication failure from a position write expires only the
session that issued it. Rejection runs outside the cancellable reporting job,
and checks the session identity again under the authentication controller's lock.

For live proof, use the CLI's explicit `devs-auto` profile to upload a short audio
file and a video in a uniquely named owned folder. Record exact IDs, names, kinds,
initial `start_from` values and ownership before setting a positive saved position.
Install with `adb install -r`, preserving the existing authenticated API 37 app.
Open each owned file in the real shell; exercise Resume, Start over, pause, Back,
and background audio with notification controls. Read back exact saved positions
through the CLI after the reporting interval and pause. Keep shared account
settings unchanged. Restore initial positions and delete only owned fixtures
after confirming playback and instrumentation are idle. Capture the prompt and
its playback outcome, validate recordings, inspect, and publish through the
existing evidence wrapper. Local test-player proof does not establish live API
write-back or service ownership.

## Immersive landscape video proof

`MobileFullscreenVideoProofTest#landscapePlaybackUsesDirectControlsAndRestoresThePreviousWindow`
uses real local Media3 video in a debug-only Activity. The host handles orientation
configuration changes so the production window policy can rotate its real window
without losing the test's injected composition. It verifies a portrait window
with visible system bars becomes landscape with both bars hidden, and Back restores
the original orientation and bar visibility. Saved-state recreation uses Compose's
`StateRestorationTester`; this does not claim full Activity or process recreation.

Run only this selector on API 37 after installing app and instrumentation APKs
with `adb install -r`. Required arguments are:

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
account storage, or stop a preexisting audio service; its player factory uses the
production private video player with the service-stop hook disabled.

Screenshots are written below the target app's external files directory in
`fullscreen-video-proof-<UUID>/`: initial landscape controls, selected captions,
the speed/audio/captions sheets, restored selections, Automatic captions and the
restored portrait window. Pull and
inspect them before using the existing evidence publisher. The caller owns the
local fixture, bounded instrumentation supervision, recording and emulator lifetime.

The local Sintel fixture derives from the Blender Foundation's
[720p trailer](https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4),
with a cropped and looped excerpt, replacement test tones and synthetic captions.
Publishing its screenshots or clips requires attribution: “© copyright Blender
Foundation | durian.blender.org”, with the [CC BY 3.0 sharing terms](https://durian.blender.org/sharing/).

## Mobile share-in

The mobile launcher accepts `ACTION_SEND` with `text/plain`. A URL or magnet
opens an editable Add transfer sheet after sign-in; only Add submits it. Text
with one unambiguous supported link prefills that link. Ambiguous prose and multiple links remain
editable with guidance to choose one. A new share cannot overwrite an existing
draft without confirmation or interrupt a running mutation. Input is limited to
16 KiB of UTF-8; oversized shares are rejected without truncation. A rejected
oversized edit keeps the previous draft but blocks Add until the user edits it.

Share payloads and add-transfer drafts stay in Activity-owned memory. Rotation
retains them; process death discards them. Saved state contains only consumed
request metadata, and received share extras and ClipData are removed from the
retained Activity intent. Never use real credentials in share proof artifacts.

Prove browser share → editable sheet → Add using a caller-owned transfer fixture;
also exercise cancellation, a second share while editing, and rotation before
confirmation. Follow the existing session-preserving installation and fixture
cleanup rules. The controlled `MobileShellAccessibilityProofTest` also exercises
both replacement choices at 200% font size in portrait and landscape, checks the
entire confirmation message is reachable, and asserts that choosing a draft
submits no transfer. After a successful Add, Transfers shows a "Transfer added"
snackbar once per request; the share proof captures it.

## Mobile share-out and deep links

Share file (Files and Downloads action sheets, non-folder items only) starts a
foreground `dataSync` service that fetches the original file through the API
download endpoint with the session header, stores it under private
`files/shares/<fileId>/<name>`, and opens the system chooser with a
`FileProvider` content URI (`${applicationId}.share`) carrying a read grant.
The payload is the stream only: no text, subject or URL, so no token reaches the
chooser. Progress and failure use the foreground notification, which the drawer
hides while the app holds no notification permission. A service cannot start an
Activity from the background, so the chooser opens from the resumed Activity:
immediately when one exists, otherwise a "ready" notification brings the app
back and the next resume opens it. After ten minutes without a resume the
export and notification are dropped. Each export removes every earlier export
first; a recipient still reading one keeps its open descriptor. Launch removes
exports older than a day.
`MobileFileShareServiceTest` asserts the payload shape, the ready notification,
service stop rules, and the name sanitizer.

Deep links: `https://{app.put.io,put.io,www.put.io}/{files,files/<id>,transfers,search,history,trash}`
(`autoVerify`; the `assetlinks.json` publication is a release-owner task, so
unverified installs still open through the app chooser or an explicit package)
and `putio://{files,transfers,search,history,trash,downloads}`. `putio://auth`
stays with the OAuth receiver. A link is consumed once per Activity intent, any
put.io URI is removed from the retained intent, and routing waits for sign-in;
an unrouted link survives process death as its token-free `putio://` form. A file
id resolves through the item resolver and reuses the navigation-failure dialog.

Prove on the signed-in API 37 emulator:

```bash
adb shell am start -a android.intent.action.VIEW -d putio://transfers
adb shell am start -a android.intent.action.VIEW -d https://app.put.io/files/<id> -p io.put.putio.mobile.debug
```

Then open a small image's action sheet, choose Share file, and confirm the
chooser shows the content preview. Inspect `dumpsys activity activities` for the
chooser: `clip=` must reference only a `content://` URI and no `oauth_token`.

## Downloads and offline playback

Downloads use Media3's `DownloadService` and `SimpleCache` under the app's
internal files directory (`files/downloads/`), never external storage: cached
playlist bodies carry the server's token. A video download stores the HLS rendition the player
streams, subtitle renditions included; an audio download stores the original
file. Media3 owns bytes, resume and the foreground notification, which shows a
count and progress only. The app's index in private SharedPreferences holds
file id, name, type, rendition and status per user; it never holds a URL.

Requests carry the token-free API URL, and a resolving data source adds the
session header for `api.put.io` hosts. Playlist bodies from the server embed
`oauth_token` in their child URLs; the cache key factory strips that query and
prefixes the owning user id, so the Media3 index stays token-free and two
accounts never share cached bytes. There is one `DownloadManager`; request ids
are `userId:fileId`, each request downloads under its owner's keys, and a
sign-out parks that user's transfers with a stop reason until the owner signs in
again. Playback reads through the same cache with a null write sink, so
streaming never fills the download directory.
Inspect `databases/exoplayer_internal.db` after a proof and require zero
`oauth_token` occurrences and a `u<userId>|` prefix on every
`ExoPlayerCacheIndex*` key. The cached playlist bodies are server text and are
expected to contain the token; they live only in app-private storage and are
removed with the download. On start the engine reconciles Media3's own index
into the app's rows, so a transfer that completed while the UI was dead reads
On this device after relaunch.

Prove on the API 37 emulator with the shared `devs-auto` account: download a
small root video from its Files actions sheet, wait for `On this device` in the
Files row and the Downloads screen, then enable airplane mode with
`adb shell cmd connectivity airplane-mode enable` and play it from Downloads.
Cut the network during a larger download and confirm the row reads
`Waiting for network`, then restore it and confirm the download completes by
itself. Delete the local copy from the Downloads sheet and confirm the cache
directory shrinks. Clear only `databases/exoplayer_internal.db*`,
`shared_prefs/io.putdotio.android.downloads.xml` and the internal
`files/downloads/` between runs; never wipe app data or the session.
