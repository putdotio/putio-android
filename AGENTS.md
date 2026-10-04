# Agent Guide

Ground-up native rewrite of the put.io Android app: Kotlin and Compose for
phones and tablets (`mobile`) and Android TV / Fire TV (`tv`) from one
codebase. It is not on Google Play yet; [#14](https://github.com/putdotio/putio-android/issues/14)
tracks the rollout. The [README](README.md) lists what works,
[Behaviour](docs/behavior.md) the product rules and the tests that pin them,
and [Harness](docs/harness.md) the emulator lanes, evidence, and live proof.

## Where code lives

| Module | Owns |
| --- | --- |
| `core/common` | API rejection reasons, timestamps, avatar URLs, account storage keys, `SessionScopedHolder` |
| `core/design` | `PutioTheme`, generated design tokens, file-type and shared Phosphor drawables, `BasePutioActivity` |
| `domain/<name>` | One domain's models, SDK repository, reducer and controller, shared by both surfaces: `account` (account settings, app config, inactive-account notice), `auth`, `downloads`, `files`, `history`, `playback`, `search`, `transfers`, `trash` |
| `mobile` | Phone and tablet app: touch UI, shell, navigation, services and session wiring |
| `tv` | Android TV app: D-pad UI, shell and session wiring |
| `build-logic` | Convention plugins (`putio.android.application`, `putio.android.library`), design-token codegen, launcher-manifest check, proof tasks |

- Dependencies point from the apps to `domain` to `core`; the other domains
  build on `files`. Kotlin packages stay `io.putdotio.android.*` whatever the
  module. A declaration stays `internal` unless another module uses it; fakes
  other modules' tests reuse live in the owning module's `src/testFixtures`.
- Mobile and TV share data, domain, theme, and component foundations; their
  shells diverge where input differs. TV should feel like Android TV: Compose
  for TV, D-pad focus, system media sessions, and platform search.
- [`putio-sdk-kotlin`](https://github.com/putdotio/putio-sdk-kotlin) is the
  API boundary. Close an SDK gap there instead of adding app-local HTTP.
- Design values come from the locked `@putdotio/design` release and generated
  Phosphor drawables; [design/README.md](design/README.md) owns both pipelines.
  Never hand-write design values or edit generated output.
- Each app has `production` and `nightly` channel flavors. Nightly differs
  only in its id and version-name suffixes, label, and stars launcher icon, so
  its unit-test variants are disabled.

## Hazards

- **The TV app id is live on Google Play.** `tv` builds `io.put.putio`, the id
  of the put.io TV app users run today, so a release replaces it in place and
  imports its session ([Upgrade from tv-native](docs/behavior.md#upgrade-from-tv-native)).
  Keep the id unless product owners choose a new listing. The repo has no Play
  release lane or signing config yet; anything uploaded to Play by hand under
  that id reaches existing users, and its version code cannot be reused. Play
  internal and closed tracks are meant for nightly, the public listing for
  production.
- **Shared test account.** Live proof uses the `devs-auto` put.io CLI profile
  that the web, iOS, and TV harnesses share. Create uniquely named fixtures,
  record their ids, and remove only those. Never use a personal profile or put
  tokens in the repo; app tokens belong in Android secure storage.
- **Shared emulators.** The machine may be running other emulators. The scripts
  reuse preexisting ones and stop only the serial they booted; never kill
  emulators by name or delete AVDs the harness did not create.
- **Authenticated installs.** `connectedAndroidTest` and `scripts/prove.sh`
  uninstall the tested app and its session; don't run them between
  authenticated proof steps.
- **Evidence.** Captures land in ignored `.evidence/`. Never commit them or
  upload a quarantined `*.corrupt`, `*.black.*`, or `*.mp4.idle` file, and look
  at each capture before attaching it.

## Setup

The machine provides JDK 21 (pinned in `.java-version`), `python3`, and, on
macOS, Homebrew. Then run:

```bash
./scripts/bootstrap.sh   # idempotent; --google-tv adds the Google TV image and AVD
```

It installs the Android SDK packages and FFmpeg, creates the `putio-phone`
(API 37) and `putio-tv` (API 36) AVDs, and writes `sdk.dir` to the ignored
`local.properties`; the first run downloads several GB. `.worktreeinclude`
copies `local.properties` into Codex and Claude worktrees. Bootstrap, build,
CI, and launch proof need no credentials; only live and authenticated lanes use
the `devs-auto` profile.

## Kotlin SDK

The app depends on the released `io.put:putio-sdk-kotlin` from Maven Central;
bump `putioSdkKotlin` in [`libs.versions.toml`](gradle/libs.versions.toml) to
take a new release. To build against unreleased SDK changes, set
`putioSdkKotlinPath` in `local.properties` to an absolute SDK checkout path and
Gradle substitutes it as a composite build. Remove the key to return to the
pinned release, and release the SDK before an app PR depends on it. Local proof
built that way records the SDK checkout's SHA and `git status --short`.

## Build and verify

```bash
./gradlew verify                                                  # canonical gate
./gradlew :mobile:assembleProductionDebug :tv:assembleProductionDebug
```

The root [`verify` task](build.gradle.kts) runs every module's `check` (lint
with warnings as errors, detekt, JVM unit tests, launcher-manifest checks),
unsigned minified release builds of both apps, both instrumentation APKs, the
icon and design-asset lock checks, the script contract tests, and the
`build-logic` tests. The script checks need `bash` 3.2+, `python3`, `ffprobe`,
and `ffmpeg` with the `freezedetect` filter and `libx264` encoder on PATH.

- Fix lint and detekt findings at the source; suppress only with a comment
  naming the platform constraint. Each app's `detekt-baseline.xml` only shrinks.
- Script checks are cached on `scripts/`, the files they verify, and host tool
  versions. A new file a check reads must sit under those inputs, or the check
  will not rerun when it changes.
- `lintVital` is off (`checkReleaseBuilds = false`): AGP discards its report
  whenever full lint runs, so a release lane must run `lint` itself.

## Proof map

Code changes pass `./gradlew verify` and the debug assembles, plus the row
that matches. Report skipped or unavailable proof.

| Change | Proof |
| --- | --- |
| Docs only | None; check the links and commands you touched. Pull-request CI skips `verify` for these paths |
| Domain or core logic | `./gradlew :domain:<name>:testDebugUnitTest` or `:core:<name>:testDebugUnitTest`; tests live in the owning module |
| App view models or services | `./gradlew :mobile:testProductionDebugUnitTest` or `:tv:testProductionDebugUnitTest` |
| UI, navigation, playback, or launch | The affected flow on the emulator: `./scripts/prove.sh <mobile\|tv>` or its lane in [Harness](docs/harness.md) |
| Live API behavior | The lane's `devs-auto` steps in [Harness](docs/harness.md#live-api-proof-putio-cli) |
| Design tokens or icons | The sync in [design/README.md](design/README.md), then `./gradlew verify` |
| Visible change | Reviewed captures from `.evidence/`, attached with `gh pr comment <n> --attach <file>` |

A changed product rule updates its [Behaviour](docs/behavior.md) section and
names the tests that pin it; a new device lane gets a section in
[Harness](docs/harness.md); shipped user-facing behavior updates the README's
"What works today".

## Delivery and CI

Open pull requests against `main`; the repository squash-merges. A push to
`main` runs [CI](.github/workflows/ci.yml) and nothing else: `verify` compiles
the release variants unsigned, and nothing signs, publishes, or uploads to
Play. CI runs `./gradlew verify` plus all four debug
assembles on every `main` push and on pull requests that change more than
Markdown, `docs/`, issue templates, or `LICENSE`; a docs-only pull request
reports the job as skipped. CI holds no secrets and keeps
failed unit-test XML as the `failed-unit-test-reports` artifact. A new push to
a pull request cancels its running check; `main` runs never replace each other.

[Emulator smoke](.github/workflows/emulator-smoke.yml) runs `LaunchSmokeTest`
and `StaleOAuthCallbackTest` on a Gradle Managed Device weekly and on dispatch.
It is not a pull-request gate because shared-runner emulator boots are too slow
and flaky; `scripts/prove.sh` stays the local proof.

To reproduce a CI failure, check out the app SHA from the run summary in a
detached worktree with `sdk.dir` set and `putioSdkKotlinPath` unset, then run
the same command. A pull-request run tests a temporary merge commit; if it is
gone, merge the recorded second parent into the first and resolve conflicts
explicitly rather than substituting a newer base or head.
