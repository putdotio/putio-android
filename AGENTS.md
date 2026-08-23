# Agent Guide

Operating manual for working in this repo autonomously: bootstrap the
toolchain, build both flavors, prove behavior on an emulator, and capture
evidence for PRs.

## Repo

- Native Android app for put.io, covering Android mobile and Android TV / Fire TV from one Compose codebase
- Uses `putio-sdk-kotlin` as the API boundary; do not bypass it with ad hoc HTTP unless the SDK gap is documented first
- Android TV should feel like Android TV: Compose for TV, D-pad focus, system media/session behavior, and platform-native search where applicable
- Program root: [#14](https://github.com/putdotio/putio-android/issues/14); harness: [#15](https://github.com/putdotio/putio-android/issues/15)

## Start Here

- [Overview](./README.md)
- [Migration assessment](./docs/migration-from-tv-native.md)
- Shared workspace: `../putio-frontend/AGENTS.md`
- TV app packet: `../putio-frontend/docs/specs/tv-app/README.md`
- Kotlin SDK guide: `../putio-sdk-kotlin/AGENTS.md`

## Toolchain

The machine provides exactly three things; `scripts/bootstrap.sh` scripts the
rest.

| Machine provides | How |
| --- | --- |
| JDK 21 on PATH | `.java-version` pins 21; `mise install` or `brew install temurin@21` |
| Homebrew (macOS) | only needed if Android cmdline-tools are absent |
| Network | first bootstrap downloads ~3 GB of SDK packages |

```bash
./scripts/bootstrap.sh
```

Idempotent. Installs cmdline-tools (via Homebrew if missing), accepts
licenses, installs platform/build-tools for the compileSdk, the emulator, the
phone + TV system images, creates the two reusable AVDs, and writes
`local.properties` (`sdk.dir` plus `putioSdkKotlinPath`, defaulting to the
sibling `../putio-sdk-kotlin` checkout, which must be cloned).

SDK root resolution everywhere: `ANDROID_HOME` → `ANDROID_SDK_ROOT` →
`local.properties` `sdk.dir` → known install paths.

## Build and Verify

```bash
./gradlew verify                    # canonical local gate (:app:check)
./gradlew :app:assembleMobileDebug  # phone/tablet debug APK
./gradlew :app:assembleTvDebug      # Android TV debug APK
```

`verify` runs Android Lint (`warningsAsErrors`, config in `app/lint.xml`),
detekt (config in `detekt.yml`, Compose exemptions only), and the local unit
tests (`app/src/test/`, JUnit4 + Robolectric, Compose UI assertions run on
the JVM). Fix findings at the source; suppress only with a comment stating
the platform constraint.

Debug application ids: `io.put.putio.mobile.debug` (mobile),
`io.put.putio.debug` (tv). Debug builds only; nothing in this harness needs
release credentials.

## CI

`.github/workflows/ci.yml` runs on every PR and push to main: `./gradlew
verify` plus both flavor assembles, uploading the debug APKs as the run's
`debug-apks` artifact. The `Verify Android app` check is required by branch
protection on `main`. Conventions mirror `putio-sdk-kotlin`: pinned action
SHAs, Temurin 21, `gradle/actions/setup-gradle` caching, concurrency
cancellation.

The private `putio-sdk-kotlin` composite build is checked out as a sibling
using a read-only deploy key stored as the `PUTIO_SDK_KOTLIN_DEPLOY_KEY`
Actions secret; no other CI secret exists.

Emulator-on-CI: `.github/workflows/emulator-smoke.yml` (weekly schedule +
`workflow_dispatch`) runs `LaunchSmokeTest` on a Gradle Managed Device
(`ciPhone`, Pixel 7, API 36, swiftshader) with KVM enabled on the runner.
It is deliberately not a PR gate — shared-runner emulator boots are too slow
and flaky to block merges; local proof stays on `scripts/prove.sh`.

## Emulators

Two reusable AVDs, both API 36 arm64 on Apple Silicon (x86_64 elsewhere):

| AVD | Device profile | System image |
| --- | --- | --- |
| `putio-phone` | pixel_7 | `android-36;google_apis` |
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
proof. One automatic retry covers the cold-boot render flake, where the
emulator composites the app window black for the first minute. Exit 0 pass ·
1 fail · 70 cleanup failure · 130/143 interrupted. Machine-readable stdout
markers: `BOOTED <serial>`, `EVIDENCE <path>`, `PROOF PASS|FAIL <flavor>`.

Flags: `--keep` (leave emulator running), `--ephemeral` (throwaway AVD,
deleted on exit), `--window` (headed), `--skip-build`, `--record`,
`--seconds N` (recording length, max 180).

`--record` runs after verification: the app is force-stopped and relaunched
under active capture, so the clip always has frames (`screenrecord` drops
static screens) and shows a real cold process launch on a settled system.
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

Never commit evidence files. Publish them with the attach CLI and link the
preview URL from the PR and issue:

```bash
attach put .evidence/<file> --repo putdotio/putio-android --pr <number>
```

Follow the installed `attach-cli` skill for login and safe handling; use
`--markdown` only when the PR needs an inline embed. Deeper harness
integration is tracked in
[#50](https://github.com/putdotio/putio-android/issues/50).

## Live API Proof (putio CLI)

For fixtures and live API state checks, use the `putio` CLI with a dedicated
test profile. Never a personal default profile, never tokens in the repo:

```bash
putio auth login --profile putio-android-test   # device-code flow; approve at the printed URL
putio auth status                               # verify before relying on it
putio auth profiles list
```

Tokens live in the CLI's own config (`~/.config/putio/`), outside the repo.
The harness is secret-free by default: nothing in bootstrap, build, or proof
requires authentication.

## Definition of Done (program rule, from #14)

Every child issue of the rewrite epic ships with:

1. `./gradlew verify` plus both flavor assembles green
2. The behavior exercised on the local harness (`scripts/prove.sh` or a
   feature-specific flow on the emulator)
3. Visual proof captured from the harness and published from the PR via the
   attach CLI

## Headless / Devbox Notes

- `--headless` boots with `-no-window -gpu swiftshader_indirect -no-audio -no-boot-anim`. Software rendering is slower than `auto-no-window` but deterministic; host-GPU headless mode intermittently composites app windows black, which fails the pixel assertion. `screencap`/`screenrecord` capture fine without a window
- Cold headless boots regularly ANR the emulator's own `com.android.systemui`; harness boots set `hide_error_dialogs 1` so the dialog cannot sit over captures. App crashes and ANRs still fail the instrumented proof
- macOS needs Hypervisor.framework (default on Apple Silicon); Linux devboxes need KVM (`emulator -accel-check`). Without acceleration arm64 images are unusably slow
- First boot of a fresh AVD is the slow path (~1 min on an M-series Mac); subsequent boots are faster with `-no-snapshot` still enforced for reproducibility
- On shared machines check `scripts/emulator.sh status` before assuming a free console port; the scripts scan 5554–5584

## Worktrees

`.worktreeinclude` carries `local.properties` into Codex and Claude worktrees.
Set `sdk.dir` and an absolute `putioSdkKotlinPath` there, then run
`./gradlew verify`.

## Rules

- Keep mobile and TV shared at the data, domain, theme, and component-foundation layers
- Let UI shells diverge when input model differs: touch for mobile, D-pad/remote for TV
- Prefer SDK changes in `../putio-sdk-kotlin` over app-local API workarounds
- Preserve the current Android TV package/release identity unless product/release owners decide to create a new listing
- Store tokens in Android platform secure storage; never commit sample secrets or OAuth tokens
- Use the workspace docs for cross-repo policy, but keep app-specific build, verification, and architecture notes in this repo
