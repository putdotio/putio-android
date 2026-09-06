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
1 fail · 70 cleanup failure · 130/143 interrupted. Machine-readable stdout
markers: `BOOTED <serial>`, `EVIDENCE <path>`, `PROOF PASS|FAIL <flavor>`.

The connected suite uninstalls the tested apps afterward; `prove.sh` reinstalls
the app for capture. Authenticate after launch proof for interactive feature
checks, and do not run `connectedAndroidTest` or `prove.sh` between authenticated
steps. The device auth tests use separate preferences and Keystore aliases so
their own setup and cleanup do not touch an existing session.

Flags: `--keep` (leave emulator running), `--ephemeral` (throwaway AVD,
deleted on exit), `--window` (headed), `--skip-build`, `--record`,
`--seconds N` (recording length, max 180).

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

Debug builds honour an ignored `local.properties` key that replaces the mobile
client id and skips the TV-client guard. Use it only while mobile client `9677`
lacks a backend grant (for example `account:write`, putdotio/putio#4686); the
borrowed client must list `putio://auth` as a callback.

```properties
putioMobileOAuthClientIdDebugOverride=6221
```

Release builds ignore the key. Existing sessions were issued to the previous
client, so `adb shell pm clear` the debug app and sign in again after changing
it. Say which client a proof ran on when you attach evidence.

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
request whose item is absent from a partial page stays inconclusive. Empty
Trash verifies only a known-empty Trash. Restore all submits the initial
snapshot cursor when the server issued one (so every ID of that listing is
covered), otherwise the loaded IDs, and marks every cached Files folder stale
because restored items can land anywhere. While an action is pending, Check
Trash repeats only the read; no other mutation is offered.

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

## Account Trash and single-item Restore proof

Trash is opened from Account → Manage Trash, including when the Trash setting
is off. Restore acknowledges queueing with “Restore started.” An exact-ID Files
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
