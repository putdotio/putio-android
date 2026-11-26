# Agent Guide

The native put.io Android app: Kotlin and Compose for phones and tablets
(`mobile`) and Android TV / Fire TV (`tv`) from one codebase. It ships on
Google Play; [#14](https://github.com/putdotio/putio-android/issues/14) tracks
what is left of the rollout. [Architecture](docs/architecture.md) maps the modules, and
[Design](DESIGN.md) is the Android design contract. Tests pin the product rules;
script headers and `--help` own the harness.

## Conventions

- A domain failure with cases of its own wraps a `PutioFailure`
  (`AccountSettingsFailure`, `PlaybackFailure`) instead of cloning the shared
  cases.
- Kotlin packages stay `io.putdotio.android.*` whatever the module.
- A declaration stays `internal` unless another module uses it; a new public
  one needs an explicit `public`.
- Fakes other modules' tests reuse live in the owning module's
  `src/testFixtures`.
- Report a failure as a `TelemetryFailure` of codes.
- A dependency under a license outside `ALLOWED_LICENSES` in
  [PutioAndroidApplicationPlugin](build-logic/src/main/kotlin/PutioAndroidApplicationPlugin.kt)
  fails the build. The app's code is MIT; ask Altay before widening the list.
- A media3-ffmpeg-decoder bump updates `playbackNotices` to the FFmpeg version
  its `ffmpeg` submodule pins; an ass-media bump updates them to the
  libraries its `libass-cmake` submodule pins; a Sentry bump updates
  `telemetryNotices` to the sentry-native its NDK module depends on.
- TV should feel like Android TV: Compose for TV, D-pad focus, system media
  sessions, and platform search.
- [`putio-sdk-kotlin`](https://github.com/putdotio/putio-sdk-kotlin) is the
  API boundary. Close an SDK gap there.
- Derive every `OkHttpClient` from `PutioHttpClient.newBuilder()` outside
  `core/common`, and use `PutioHttpClient.shared` (`callTimeout = 0`) for
  multipart uploads because a custom SDK client drops the SDK's
  upload-timeout exemption (`MobileNetworkingPoolTest`).
- A change to TV startup, sign-in or Home regenerates the TV baseline profile
  with `scripts/generate-baseline-profile.sh` on an emulator you booted.
- Design values and icons are generated: change the locks in `design/`, then
  run `./gradlew syncDesignAssets` or `./gradlew generateIcons`.
- To take a design release:
  1. Set the lock's version, tarball, and `integrity`
     (`npm view @putdotio/design@<version> dist.integrity`).
  2. Run `./gradlew syncDesignAssets`.
  3. Replace each digest it reports as unlocked.

## Hazards

- **The app id is live on Google Play.** `mobile` and `tv` both build
  `io.put.putio` in production, the id of the put.io TV app users run today, so
  a release replaces it in place and TV imports its session. Keep the
  id unless product owners choose a new listing. Only the
  [Google Play delivery](#google-play-delivery) workflows upload.
- **Shared test account.** Live proof uses the `devs-auto` put.io CLI profile
  that the web, iOS, and TV harnesses share. Create uniquely named fixtures,
  record their ids, and remove only those. Never empty Trash, use ID zero, or
  repeat a mutation whose outcome is unknown; `POST /files/delete` reads
  `skip_trash` from the query only, so a form value deletes permanently when
  Trash is off. Restore any account setting you change. Never use a personal
  profile or put tokens in the repo; app tokens belong in Android secure
  storage. Sign the mobile debug app in as `devs-auto` with
  `./scripts/sign-in-devs-auto.sh <serial>`. put.io allows few device codes per
  IP, so the script caches the app's token per host and reuses it on fresh
  devices. Signing out in the app revokes that token for every run on the host.
- **Shared emulators.** The machine may be running other emulators. The scripts
  reuse running ones and stop only the serial they booted; never kill
  emulators by name or delete AVDs the harness did not create. A lane that
  drives Home, the launcher, or system settings acts on the whole device: run
  it only on an emulator you booted, and restore every setting it changes.
- **Authenticated installs.** `connectedAndroidTest` and `scripts/prove.sh`
  uninstall the tested app and its session; run them before or after
  authenticated proof, never between its steps.
- **Licensed fonts.** GT America and Berkeley Mono land in ignored
  `core/design/brand-fonts/`. Never commit them or attach them anywhere; only
  the apps embed them.
- **Evidence.** Captures land in ignored `.evidence/`. Never commit them or
  upload a quarantined `*.corrupt`, `*.black.*`, or `*.mp4.idle` file, and look
  at each capture before attaching it. Captures of the Blender Foundation's
  Sintel trailer need the attribution "© copyright Blender Foundation |
  durian.blender.org" ([CC BY 3.0](https://durian.blender.org/sharing/)).
- **Legal copy.** The Privacy controls page on mobile and TV uses the legal-approved text from
  [support#135](https://github.com/putdotio/support/issues/135); don't reword
  it without a new approval.

## Setup

The machine provides Node from `.node-version`, pnpm from `package.json`,
and `python3`. Then run `./scripts/bootstrap.sh`; its header lists the other
preconditions. Only live and authenticated lanes need the `devs-auto` profile.

## Kotlin SDK

To build against unreleased SDK changes, see `putioSdkKotlinPath` in
[`settings.gradle.kts`](settings.gradle.kts). Release the SDK before an app PR
depends on it.

## Skills

Skills live in `.agents/skills`, linked into `.claude/skills`.
[`skills-lock.json`](skills-lock.json) pins the ones from
[android/skills](https://github.com/android/skills); `npx skills@latest update -p`
refreshes them; `verify` fails when their files differ from the lock.

## Build and verify

```bash
./gradlew verify                                                  # canonical gate
./gradlew verifyMobile                                            # one lane: verifyDocs, verifyScripts, verifyLibraries, verifyMobile, verifyTv, verifyRelease
./gradlew :mobile:assembleProductionDebug :tv:assembleProductionDebug
```

The script checks in [`verify`](build-logic/src/main/kotlin/PutioRootPlugin.kt) need `bash` 3.2+, `python3`,
`node`, `shellcheck`, `ffprobe`, and `ffmpeg` with the `freezedetect` filter and `libx264`
encoder on PATH.
`markdownCheck` runs the locked tools from [pnpm-lock.yaml](pnpm-lock.yaml)
via `node_modules` (`./scripts/bootstrap.sh` or `pnpm install --frozen-lockfile`);
`pnpm exec oxfmt '**/*.md'` fixes its findings.

- Fix lint and detekt findings at the source; suppress only the narrowest
  declaration, with a comment naming the constraint. Every module runs detekt
  without a baseline.
- Script checks are cached on `scripts/`, the files they verify, and host tool
  versions. A new file a check reads must sit under those inputs, or the check
  will not rerun when it changes.
- `lintVital` is off (`checkReleaseBuilds = false`): AGP discards its report
  whenever full lint runs, so a release lane must run `lint` itself.

## Proof map

Code changes pass `./gradlew verify` and the debug assembles, plus the row
that matches. Report skipped or unavailable proof.

| Change                              | Proof                                                                                                                                                                                                                                                                                                                                     |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Docs only                           | `./gradlew markdownCheck`; check web links and commands                                                                                                                                                                                                                                                                                   |
| Domain or core logic                | `./gradlew :domain:<name>:testDebugUnitTest` or `:core:<name>:testDebugUnitTest`; tests live in the owning module                                                                                                                                                                                                                         |
| App view models or services         | `./gradlew :mobile:testProductionDebugUnitTest` or `:tv:testProductionDebugUnitTest`                                                                                                                                                                                                                                                      |
| UI, navigation, playback, or launch | Mobile: `:mobile:verifyCiPhoneLaunchProof` (`-Pandroid.testInstrumentationRunnerArguments.class=io.putdotio.android.LaunchSmokeTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader`) and [verify-putio-android](.agents/skills/verify-putio-android/SKILL.md); TV: `./scripts/prove.sh tv` or its opt-in `*ProofTest` lane |
| Google Play delivery                | `./gradlew -PputioVersionName=3.0.1 :mobile:bundleProductionRelease :tv:bundleProductionRelease`, then a `dry_run` of [Google Play delivery](#google-play-delivery)                                                                                                                                                                       |
| Live API behavior                   | Mobile: [verify-putio-android](.agents/skills/verify-putio-android/SKILL.md) with its fixture ledger; TV: the lane's `devs-auto` steps                                                                                                                                                                                                    |
| Design tokens or icons              | `./gradlew syncDesignAssets` or `./gradlew generateIcons`, then `./gradlew verify`                                                                                                                                                                                                                                                        |
| Visible change                      | Reviewed captures from `.evidence/`, attached with `gh pr comment <n> --attach <file>`                                                                                                                                                                                                                                                    |

Docs stay high level: a new module boundary updates
[Architecture](docs/architecture.md), a new product area updates the README's
feature list, and a token or design-rule change updates DESIGN.md.

## Delivery and CI

Open pull requests against `main`. A merge runs only
[CI](.github/workflows/ci.yml); nothing ships without a Google Play dispatch.

### Google Play delivery

[Google Play Internal](.github/workflows/play-internal.yml) uploads both
bundles; [Google Play Release](.github/workflows/play-release.yml) promotes or
changes the rollout. Their inputs own the options.
[Google Play Data Safety](.github/workflows/play-data-safety.yml) replaces the
listing's Data safety answers with `play/data-safety.csv`. Google can't read
them back, so a change to what the apps collect edits that file in the same
pull request.

- Every dispatch, `dry_run` included, needs Altay's OK: it loads the `release`
  Environment's secrets.
- If an upload lands but its `tag` job fails, rerun only that job. A full
  rerun repeats the version code, and Google Play refuses it.
- Each upload needs a new `version_name`. Mobile and TV share the
  [putio-tv-native-legacy](https://github.com/putdotio/putio-tv-native-legacy)
  listing's version codes.
- Google Play can't downgrade installs. Halt a bad rollout with the `halt`
  input of Google Play Release, then ship the fix under a new `version_name`.
