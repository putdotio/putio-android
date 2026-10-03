# Agent Guide

Operating manual for working in this repo autonomously: bootstrap the
toolchain, build both flavors, prove behavior on an emulator, and capture
evidence for PRs.

## Repo

- Native Android app for put.io, covering Android mobile and Android TV / Fire TV from one Compose codebase
- Uses [`putio-sdk-kotlin`](https://github.com/putdotio/putio-sdk-kotlin) as the API boundary; do not bypass it with ad hoc HTTP unless the SDK gap is documented first
- Android TV should feel like Android TV: Compose for TV, D-pad focus, system media/session behavior, and platform-native search where applicable
- Program root: [#14](https://github.com/putdotio/putio-android/issues/14)

## Start Here

- [Overview](./README.md)
- [Harness](./docs/harness.md)
- [Behaviour](./docs/behavior.md): product rules the harness proves
- Kotlin SDK: [`io.put:putio-sdk-kotlin`](https://github.com/putdotio/putio-sdk-kotlin) from Maven Central, pinned in [`libs.versions.toml`](./gradle/libs.versions.toml)

## Toolchain

The machine provides exactly four things; `scripts/bootstrap.sh` scripts the
rest.

| Machine provides | How |
| --- | --- |
| JDK 21 on PATH | `.java-version` pins 21; `mise install` or `brew install temurin@21` |
| `python3` on PATH | macOS ships it with the Xcode Command Line Tools; `verify` runs the icon and design asset pipelines through it |
| Homebrew (macOS) | only needed if Android cmdline-tools or FFmpeg are absent |
| Network | first bootstrap downloads several GB of SDK packages plus FFmpeg |

```bash
./scripts/bootstrap.sh
```

Idempotent. Installs cmdline-tools (via Homebrew if missing) and FFmpeg
(Homebrew or apt), accepts licenses, installs platform/build-tools for the
compileSdk, the emulator, the API 37 Google Play phone image and API 36 Android
TV image, creates the two reusable AVDs (`--google-tv` adds the opt-in Google
TV image and AVD), and writes `sdk.dir` to `local.properties`.

## Kotlin SDK

The app depends on the released `io.put:putio-sdk-kotlin` from Maven Central;
bump `putioSdkKotlin` in [`libs.versions.toml`](./gradle/libs.versions.toml)
to take a new release. To build against unreleased SDK changes, set
`putioSdkKotlinPath` in `local.properties` to an absolute SDK checkout path;
Gradle then substitutes that checkout as a composite build. Remove the key to
return to the pinned release, and land the SDK change as a release before an
app PR depends on it.

SDK root resolution in the harness scripts: `ANDROID_HOME` →
`ANDROID_SDK_ROOT` → `local.properties` `sdk.dir` → known install paths.

## Build and Verify

```bash
./gradlew verify                              # canonical local gate
./gradlew :app:assembleMobileProductionDebug  # phone/tablet debug APK
./gradlew :app:assembleTvProductionDebug      # Android TV debug APK
```

The root [`verify` task](./build.gradle.kts) runs Android Lint with warnings
as errors on `mobileProductionDebug` and `tvProductionDebug`, detekt, and the
production debug JVM unit tests; nightly adds resources only, so its unit-test
variants are disabled. `check` also runs `verify<Variant>LauncherManifest`
for every variant: the merged manifest must give `MainActivity` an
`ACTION_MAIN` filter with `LAUNCHER`, and on TV another with
`LEANBACK_LAUNCHER`. Unsigned minified `mobileProductionRelease`,
`tvProductionRelease`, and `tvNightlyRelease` builds prove the composite
Kotlin SDK, resource shrinking, and `lintVital` against R8 for each surface and
channel. It also compiles the instrumentation APK, checks the Phosphor icon and
design asset locks, and runs the shell and Python contract tests; those need
`python3`, `bash` (3.2 or newer, so macOS `/bin/bash` works), `ffprobe`, and
`ffmpeg` with the `freezedetect` filter and `libx264` encoder on PATH. The
contract tests fake the SDK and console-port probe and give nested proof builds
their own temp directory for the serial lock, so they pass beside running
emulators, real proofs, and parallel checkouts. These script checks declare
`scripts/`, the files they verify, and the host tool versions as inputs, so an
unchanged check is skipped or restored from the build cache; a new script file
a check reads must sit under one of those inputs. It also runs the tests of the
[`build-logic`](./build-logic) included build, which owns the design-token
codegen, launcher-manifest check, and host proof task classes. Fix findings at the source; suppress only
with a comment stating the platform constraint.

Two flavor dimensions: `surface` (`mobile`, `tv`) × `channel` (`production`,
`nightly`); [app/build.gradle.kts](./app/build.gradle.kts) owns the application
ids. Nightly carries its own id, label, and the stars launcher icon
(`scripts/sync-design-assets.sh`); Play internal/closed tracks ship nightly,
the public listing keeps production. Harness launch proof uses debug builds,
`io.put.putio.mobile.debug` and `io.put.putio.debug` for production. Nothing in
the harness needs release credentials.

## Design system

The theme is a tier-2 binding of putio-design (Material 3 + tokens, dark
only). `design/putio-design.lock.json` pins the `@putdotio/design` npm release
by version and SHA-512 SRI; `scripts/sync-design-assets.sh` fetches it and
writes `design/tokens.dtcg.json` and the nightly launcher icons, and `verify`
checks both against the lock offline. `:core:design:generateDesignTokens`
(`build-logic`) generates `PutioDesignTokens.kt` with the color schemes and TV
overscan ratios; never hand-write design values. See `design/README.md`.
Phosphor icon drawables are vendored by `scripts/generate-icons.sh`.

## CI

[CI](./.github/workflows/ci.yml) runs `./gradlew verify` plus
all four debug flavor assembles on every PR and push to main. Failed runs keep
unit-test JUnit XML, including assertion diagnostics the job log omits, as the
`failed-unit-test-reports` artifact; manual dispatches also upload the debug
APKs. A new push to a pull request cancels its running check; `main` pushes
and manual dispatches never cancel or replace one another, so every `main`
commit gets a verdict. CI resolves the SDK from Maven Central and holds no
secrets.

Both CI workflows record the app SHA and the app commit's parents in the job
log and run summary; the pinned SDK version makes the app SHA the whole input.
Local proof built with `putioSdkKotlinPath` set must also record that SDK
checkout's SHA and worktree status.

To reproduce a CI run without resetting existing work, create a detached
worktree at the recorded app revision and run the same verification lane:

```bash
git worktree add --detach ../putio-android-repro <app-sha>
```

For a pull-request run, the tested app SHA can be a temporary merge commit.
If it is no longer fetchable, the recorded first and second app parents identify
the base and head used for that merge. Fetch those commits, create the app
worktree at the first parent, and merge the second parent there to reconstruct
the tested source. Resolve a merge conflict explicitly; do not substitute a
newer base or head and call it the same proof.

Set `sdk.dir` in the reproduction worktree's ignored `local.properties`, and
leave `putioSdkKotlinPath` unset, before running Gradle. Include local
modifications with a failure report; SHA pairs describe only committed source.

[Emulator smoke](./.github/workflows/emulator-smoke.yml) runs weekly and on
dispatch: `LaunchSmokeTest` and the credential-free `StaleOAuthCallbackTest` on
the `ciPhone` Gradle Managed Device (API 37). Each suite runs through
`verifyCiPhoneLaunchProof` or `verifyCiPhoneOAuthProof`, which fail unless that
test's own XML result passed. It is deliberately not a PR gate:
shared-runner emulator boots are too slow and flaky to block merges, so
`scripts/prove.sh` stays the local proof.

## Harness

`./scripts/prove.sh <mobile|tv>` builds, boots, installs, launches, verifies,
captures, and tears down; the exit code is the proof. Flags, emulator
lifecycle, evidence validation and upload, feature proof lanes, live API proof
with the `putio` CLI, and headless notes: [Harness](./docs/harness.md).

## Definition of Done

Every change ships with:

1. `./gradlew verify` plus both flavor assembles green
2. The behavior exercised on the local harness (`scripts/prove.sh` or a
   feature-specific flow on the emulator)
3. Visual proof captured from the harness and uploaded to the PR with
   `gh pr comment <n> --attach <file>`, never committed

## Worktrees

`.worktreeinclude` carries `local.properties` into Codex and Claude worktrees.
Set `sdk.dir` there (plus `putioSdkKotlinPath` only for unreleased SDK work),
then run `./gradlew verify`. The optional `putioMobileOAuthClientIdDebugOverride` key
is validated on every build; see [Harness](./docs/harness.md#borrowing-another-oauth-client-for-local-proof).

## Rules

- Keep mobile and TV shared at the data, domain, theme, and component-foundation layers
- Let UI shells diverge when input model differs: touch for mobile, D-pad/remote for TV
- Prefer SDK changes in [`putio-sdk-kotlin`](https://github.com/putdotio/putio-sdk-kotlin) over app-local API workarounds
- Preserve the current Android TV package/release identity unless product/release owners decide to create a new listing
- Store tokens in Android platform secure storage; never commit sample secrets or OAuth tokens
- Keep app-specific build, verification, and architecture notes in this repo
- Finish in-scope edits, `./gradlew verify`, and harness proof without pausing; ask before publishing evidence, Play track changes, signing or secret changes, and anything outside the task
