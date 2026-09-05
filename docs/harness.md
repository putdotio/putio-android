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

