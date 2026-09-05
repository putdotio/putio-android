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
| Network | first bootstrap downloads several GB of SDK packages plus FFmpeg |

```bash
./scripts/bootstrap.sh
```

Idempotent. Installs cmdline-tools (via Homebrew if missing), accepts
licenses, installs platform/build-tools for the compileSdk, the emulator, the
API 37 Google Play phone image and API 36 Android TV image, creates
the two reusable AVDs, and writes
`local.properties` (`sdk.dir` plus `putioSdkKotlinPath`, defaulting to the
sibling `../putio-sdk-kotlin` checkout, which must be cloned).

SDK root resolution everywhere: `ANDROID_HOME` → `ANDROID_SDK_ROOT` →
`local.properties` `sdk.dir` → known install paths.

## Build and Verify

```bash
./gradlew verify :buildSrc:test               # canonical local gate + codegen tests
./gradlew :app:assembleMobileProductionDebug  # phone/tablet debug APK
./gradlew :app:assembleTvProductionDebug      # Android TV debug APK
```

`verify` runs Android Lint (`warningsAsErrors`, config in `app/lint.xml`),
detekt (config in `detekt.yml`, Compose exemptions only), the local unit
tests (`app/src/test/`, JUnit4 + Robolectric, Compose UI assertions run on
the JVM), and an unsigned minified `mobileProductionRelease` build that proves
the composite Kotlin SDK against minSdk 26 and R8. `:buildSrc:test` covers the
design-token codegen; run it alongside `verify` (CI does). Fix findings at the
source; suppress only with a comment stating the platform constraint.
The evidence regression wraps the installed FFmpeg tools to assert that
recording normalization pins `LC_ALL=C`; it does not require or generate a
host locale.

Two flavor dimensions: `surface` (`mobile`, `tv`) × `channel` (`production`,
`nightly`). Nightly carries its own application id, label, and the stars
launcher icon (`scripts/generate-nightly-icon.sh`); Play internal/closed
tracks ship nightly, the public listing keeps production. Debug application
ids: `io.put.putio.mobile.debug` (mobileProduction), `io.put.putio.debug`
(tvProduction), plus `.nightly` before `.debug` for the nightly channel.
Harness launch proof uses debug builds. The minified release verification APK
is unsigned; nothing in this harness needs release credentials.

## Design system

The theme is a tier-2 binding of putio-design (Material 3 + tokens, dark
only). `design/tokens.dtcg.json` is vendored from `@putdotio/design`;
`:app:generateDesignTokens` (buildSrc) generates `PutioDesignTokens.kt` with
the color schemes — never hand-write colors. See `design/README.md`.
Phosphor icon drawables are vendored by `scripts/generate-icons.sh`.

## CI

`.github/workflows/ci.yml` runs on every PR and push to main: `./gradlew
verify` (including the minified SDK-consumer build) plus both debug flavor
assembles. Manual workflow dispatches additionally upload all four debug APKs
as a one-day `debug-apks` artifact. The `Verify Android app` check is required
by branch protection on `main`. Conventions mirror `putio-sdk-kotlin`: pinned
action SHAs, Temurin 21, `gradle/actions/setup-gradle` caching, concurrency
cancellation.

The private `putio-sdk-kotlin` composite build is checked out as a sibling
using a read-only deploy key stored as the `PUTIO_SDK_KOTLIN_DEPLOY_KEY`
Actions secret; no other CI secret exists.

Both CI lanes record the actual app and SDK checkout SHAs and the app commit's
parent SHAs in the job log and run summary before building. The SDK still follows its default branch; an app
SHA alone does not identify the composite build used by a previous run.
For local proof, record both SHAs and worktree status, using the SDK path
selected by `local.properties` `putioSdkKotlinPath` (or the sibling default):

```bash
SDK_CHECKOUT=/absolute/path/to/the/configured/sdk-checkout
git rev-parse HEAD
git status --short
git -C "$SDK_CHECKOUT" rev-parse HEAD
git -C "$SDK_CHECKOUT" status --short
```

To reproduce a CI pair without resetting existing work, create detached
worktrees at the recorded app and SDK revisions, point the app worktree's
`putioSdkKotlinPath` at that SDK worktree, and run the same verification lane:

```bash
git worktree add --detach ../putio-android-repro <app-sha>
git -C "$SDK_CHECKOUT" worktree add --detach ../putio-sdk-kotlin-repro <sdk-sha>
```

For a pull-request run, the tested app SHA can be a temporary merge commit.
If it is no longer fetchable, the recorded first and second app parents identify
the base and head used for that merge. Fetch those commits, create the app
worktree at the first parent, and merge the second parent there to reconstruct
the tested source. Resolve a merge conflict explicitly; do not substitute a
newer base or head and call it the same proof.

Set `sdk.dir` and the absolute `putioSdkKotlinPath` in the reproduction
worktree's ignored `local.properties` before running Gradle. Include local
modifications with a failure report; SHA pairs describe only committed source.

Emulator-on-CI: `.github/workflows/emulator-smoke.yml` (weekly schedule +
`workflow_dispatch`) runs `LaunchSmokeTest` on a Gradle Managed Device
(`ciPhone`, Pixel 7, API 36, swiftshader) with KVM enabled on the runner. The
ephemeral runner removes its unused .NET, Haskell, Swift, and PowerShell
toolchains before setup to leave room for the managed-device snapshot.
It is deliberately not a PR gate — shared-runner emulator boots are too slow
and flaky to block merges; local proof stays on `scripts/prove.sh`.

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
