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
APKs, validates the CLI account and fixtures before installation, resolves APK
identities with `apkanalyzer`, and installs both with `adb install -r`. Install
failure stops the flow; it never uninstalls or clears app data. CLI checks force
`PUTIO_CLI_PROFILE=devs-auto` and remove `PUTIO_CLI_TOKEN` from the child environment.
The device test separately validates its existing session's account ID through
the app's SDK client before touching a fixture. No token enters an argument.

The caller creates a small, uniquely named fixture folder, records its exact
IDs in an ownership ledger, and removes only those owned fixtures afterward.
The fixture JSON contains exactly these fields:

- `expectedAccountId`: positive account ID returned by the `devs-auto` CLI profile.
- `containerId`, `renameItemId`, `cancelItemId`: distinct positive IDs from the ledger.
- `containerName`: exact folder name, unique in its complete search result.
- `renameOriginalName`, `renameNewName`, `cancelOriginalName`: distinct, nonempty,
  exact names. Unicode and spaces are preserved; the whole name is replaced.

Both the folder contents and container search must fit a complete 50-item page.
The two items must belong to that folder, and the new name must be unused.
Missing, ambiguous, changed, or incorrectly typed fixtures fail closed. The
flow neither creates fixtures nor interprets readable shared items as owned.
For a second run, update the ledger's original/new names to the actual current
state; the same encrypted session is reused without another login.

The device test navigates Search → folder, renames through overflow, confirms
UI and API readback, then edits a second item through long-press and cancels.
It checks that Cancel leaves the server name unchanged. It does not replace
physical keyboard, TalkBack, or live permission-rejection proof. Ordinary
connected tests skip this opt-in test before launching its Activity rule. The
Android runner reports that opt-out as an assumption; AGP may serialize it as
an XML failure even when the connected task succeeds. Use the named proof task
above to establish that the authenticated flow actually ran and passed. The
canonical `verify` task assembles the mobile production debug test APK without
running it, so device-test compilation is checked on each CI change.

Host preflight, install, instrumentation, and capture processing share a
240-second deadline after assembly. A failed, skipped, absent, interrupted,
or incomplete named test fails the task. Recording starts before instrumentation
and uses a unique guest path with a checked process ID. Cleanup stops only the
owned instrumentation/recorder, removes only that run's guest capture files,
and leaves the emulator, app installation, authentication, and fixture ledger
intact. Cleanup has its own 20-second command budget and reports failure separately.
Use `--no-daemon` for this manual runtime lane so interruption reaches its
single-use Gradle process; confirm cleanup output before another attempt.

Run logs and raw capture remain under `.evidence/rename-<run-id>/`. Raw captures
are not publication evidence. The existing capture gates validate and normalize
them through `scripts/evidence.sh validate-recording --input <file>`, and only a
successful task prints `EVIDENCE <validated-path>` followed by
`PROOF PASS authenticated-rename`. Inspect the clip before publishing it with the
repository wrapper. No fixture or credential payload is printed by CLI preflight.
