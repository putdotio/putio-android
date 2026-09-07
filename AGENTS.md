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
- [Harness](./docs/harness.md)
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
the composite Kotlin SDK against minSdk 26 and R8. It also compiles the mobile
production debug instrumentation APK without running it. `:buildSrc:test`
covers design-token codegen and host proof tooling; run it alongside `verify`
(CI does). Fix findings at the
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
as a one-day `debug-apks` artifact. Failed runs retain app unit-test and
`buildSrc` JUnit XML as `failed-unit-test-reports` for three days, including
assertion diagnostics omitted from the job log. Treat the `Verify Android app` check as
the merge gate for `main`; this private repo has no branch protection enforcing
it. Conventions mirror `putio-sdk-kotlin`: pinned
action SHAs, Temurin 21, `gradle/actions/setup-gradle` caching, concurrency
cancellation.

The public `putio-sdk-kotlin` composite build is checked out as a sibling
without credentials; CI holds no secrets.

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

## Harness

One command from source to a verified, evidenced launch:

```bash
./scripts/prove.sh mobile            # build → boot → install → launch → verify → screenshot → teardown
./scripts/prove.sh tv --record       # also captures a screen recording
```

The exit code is the proof. Emulator lifecycle, flags, recording, evidence
validation and publishing, live API proof with the `putio` CLI, and headless
notes: [Harness](./docs/harness.md).

## Definition of Done

Every change ships with:

1. `./gradlew verify` plus both flavor assembles green
2. The behavior exercised on the local harness (`scripts/prove.sh` or a
   feature-specific flow on the emulator)
3. Visual proof captured from the harness and published from the PR via the
   attach CLI

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
- Keep app-specific build, verification, and architecture notes in this repo
- Finish in-scope edits, `./gradlew verify`, and harness proof without pausing; ask before publishing evidence, Play track changes, signing or secret changes, and anything outside the task
