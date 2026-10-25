# Agent Guide

Ground-up native rewrite of the put.io Android app: Kotlin and Compose for
phones and tablets (`mobile`) and Android TV / Fire TV (`tv`) from one
codebase. It is not on Google Play yet; [#14](https://github.com/putdotio/putio-android/issues/14)
tracks the rollout. The [README](README.md) lists what works and
[Design](DESIGN.md) the Android design contract. Tests pin the product rules;
script headers and `--help` own the harness.

## Where code lives

| Module           | Owns                                                                                                                                                                                                                                                                                  |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `core/common`    | The put.io failure and result kernel (`PutioFailure`, `PutioResult`, `putioRequest`, API rejection reasons), Files ids, cursors and sort order, the download-token account read, timestamps, avatar URLs, account storage keys, `SessionScopedHolder`                                 |
| `core/design`    | `PutioTheme`, generated design tokens, font families and type and corner scales, the compact switch, destructive button and empty state, the production launcher icon and wordmark, file-type and shared Phosphor drawables, `BasePutioActivity`, the inactive-account notice's words |
| `core/telemetry` | Sentry behind the account's `diagnostics_enabled`: the consent gate, the event allowlist, `TelemetryFailure` and `PutioDiagnostics`, the apps' only way to report                                                                                                                     |
| `domain/<name>`  | One domain's models, SDK repository, reducer and controller, shared by both surfaces: `account` (account settings, app config, inactive-account notice), `auth`, `files`, `history`, `playback`, `search`, `sharing` (public links; mobile only so far), `transfers`, `trash`         |
| `mobile`         | Phone and tablet app: touch UI, shell, navigation, offline downloads, the system file picker's documents provider, launcher shortcuts, the home-screen widget, services and session wiring, and the Google Play listing and publisher both apps share                                 |
| `tv`             | Android TV app: D-pad UI, shell and session wiring                                                                                                                                                                                                                                    |
| `build-logic`    | Convention plugins (`putio.android.application`, `putio.android.library`), design-token codegen, launcher-manifest check, proof tasks                                                                                                                                                 |

- Dependencies point from the apps to `domain` to `core`; `history`, `search`
  and `trash` also build on `files` for `FilesItem`. A domain failure with
  cases of its own wraps a `PutioFailure` (`AccountSettingsFailure`,
  `PlaybackFailure`) instead of cloning the shared cases. Kotlin packages stay
  `io.putdotio.android.*` whatever the module. Libraries build in strict
  Kotlin explicit API mode, which covers `main` and `src/testFixtures`: a
  declaration stays `internal` unless another module uses it, and a new public
  one needs an explicit `public`. Fakes other modules' tests reuse live in the
  owning module's `src/testFixtures`.
- Only `core/telemetry` imports Sentry; detekt's `ForbiddenImport` rejects
  `io.sentry` anywhere else. Report a failure as a `TelemetryFailure` of codes,
  never as a raw error or message. `SentryBoundary` builds every outgoing event
  from an allowlist and drops its attachments; a field it does not copy never
  leaves the device.
- Mobile and TV share data, domain, theme, and component foundations; their
  shells diverge where input differs. TV should feel like Android TV: Compose
  for TV, D-pad focus, system media sessions, and platform search.
- [`putio-sdk-kotlin`](https://github.com/putdotio/putio-sdk-kotlin) is the
  API boundary. Close an SDK gap there instead of adding app-local HTTP.
- Design values come from the locked `@putdotio/design` release and generated
  Phosphor drawables; `scripts/sync-design-assets.sh` (macOS only) and
  `scripts/generate-icons.sh` write them from the locks in `design/`. To take a
  design release, set the lock's version, tarball and `integrity`
  (`npm view @putdotio/design@<version> dist.integrity`), run the sync, and
  replace each digest it reports as unlocked. Never hand-write design values
  or edit generated output. A UI change follows
  [Design](DESIGN.md); when it changes the design, update that file in the
  same pull request. `DESIGN.md` follows the
  [design.md format](https://github.com/google-labs-code/design.md); its
  `colors` block is generated (`./gradlew updateDesignMdColors`), and
  `markdownCheck` checks the block and lints the file.
- Each app has `production` and `nightly` channel flavors. Nightly differs
  only in its id and version-name suffixes, label, and stars launcher icon, so
  its unit-test variants are disabled.

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
  `./scripts/sign-in-devs-auto.sh <serial>`; each approval adds a grant until
  you sign out in the app.
- **Shared emulators.** The machine may be running other emulators. The scripts
  reuse preexisting ones and stop only the serial they booted; never kill
  emulators by name or delete AVDs the harness did not create. A lane that
  drives Home, the launcher, or system settings acts on the whole device: run
  it only on an emulator you booted, and restore every setting it changes.
- **Authenticated installs.** `connectedAndroidTest` and `scripts/prove.sh`
  uninstall the tested app and its session; don't run them between
  authenticated proof steps.
- **Licensed fonts.** GT America and Berkeley Mono land in ignored
  `core/design/brand-fonts/`. Never commit them or attach them anywhere; only
  the apps embed them.
- **Evidence.** Captures land in ignored `.evidence/`. Never commit them or
  upload a quarantined `*.corrupt`, `*.black.*`, or `*.mp4.idle` file, and look
  at each capture before attaching it. Captures of the Blender Foundation's
  Sintel trailer need the attribution "© copyright Blender Foundation |
  durian.blender.org" ([CC BY 3.0](https://durian.blender.org/sharing/)).
- **Legal copy.** The Privacy controls page uses the legal-approved text from
  [support#135](https://github.com/putdotio/support/issues/135); don't reword
  it without a new approval.

## Setup

The machine provides JDK 21 (`.java-version`), Node 24 (`.node-version`),
`python3`, and, on macOS, Homebrew. Then run:

```bash
./scripts/bootstrap.sh   # idempotent; --google-tv adds the Google TV image and AVD
```

It installs the Android SDK packages and FFmpeg, creates the `putio-phone`
(API 37) and `putio-tv` (API 36) AVDs, fetches the licensed brand fonts
(`scripts/sync-brand-fonts.sh`), and writes `sdk.dir` to the
ignored `local.properties`; the first run downloads several GB.
`.worktreeinclude` copies `local.properties` and the fonts into Codex and
Claude worktrees. Bootstrap, build, CI, and launch proof need no credentials;
only live and authenticated lanes use the `devs-auto` profile.

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

The root [`verify` task](build.gradle.kts)'s `dependsOn` list is the gate's
inventory. Its script checks need `bash` 3.2+, `python3`, `node`, `ffprobe`,
and `ffmpeg` with the `freezedetect` filter and `libx264` encoder on PATH.
`markdownCheck` runs oxfmt at the version [package.json](package.json) pins;
`npx --yes oxfmt@<version> '**/*.md'` fixes its findings.

- Fix lint and detekt findings at the source; suppress only the narrowest
  declaration, with a comment naming the constraint. No module has a detekt
  baseline; don't add one.
- Script checks are cached on `scripts/`, the files they verify, and host tool
  versions. A new file a check reads must sit under those inputs, or the check
  will not rerun when it changes.
- `lintVital` is off (`checkReleaseBuilds = false`): AGP discards its report
  whenever full lint runs, so a release lane must run `lint` itself.

## Proof map

Code changes pass `./gradlew verify` and the debug assembles, plus the row
that matches. Report skipped or unavailable proof.

| Change                              | Proof                                                                                                                                                               |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Docs only                           | `./gradlew markdownCheck`; check web links and commands. CI skips builds for docs-only pull requests and checks relative links                                      |
| Domain or core logic                | `./gradlew :domain:<name>:testDebugUnitTest` or `:core:<name>:testDebugUnitTest`; tests live in the owning module                                                   |
| App view models or services         | `./gradlew :mobile:testProductionDebugUnitTest` or `:tv:testProductionDebugUnitTest`                                                                                |
| UI, navigation, playback, or launch | Mobile: the affected feature in [verify-putio-android](.claude/skills/verify-putio-android/SKILL.md); TV: `./scripts/prove.sh tv` or its opt-in `*ProofTest` lane   |
| Google Play delivery                | `./gradlew -PputioVersionName=3.0.1 :mobile:bundleProductionRelease :tv:bundleProductionRelease`, then a `dry_run` of [Google Play delivery](#google-play-delivery) |
| Live API behavior                   | Mobile: [verify-putio-android](.claude/skills/verify-putio-android/SKILL.md) with its fixture ledger; TV: the lane's `devs-auto` steps                              |
| Design tokens or icons              | `./scripts/sync-design-assets.sh` or `./scripts/generate-icons.sh`, then `./gradlew verify`                                                                         |
| Visible change                      | Reviewed captures from `.evidence/`, attached with `gh pr comment <n> --attach <file>`                                                                              |

Shipped user-facing behavior updates the README's "What works today".

## Delivery and CI

Open pull requests against `main`; the repository squash-merges. A push to
`main` runs only [CI](.github/workflows/ci.yml), whose header comments own the
triggers and path filters; CI holds no secrets.

- `Build release` and `Verify Android app` split `verify` plus the four debug
  assembles between them. When either command changes, compare their
  `--dry-run` task lists with the full command's; together they must run
  exactly the same tasks.

[Emulator smoke](.github/workflows/emulator-smoke.yml) runs `LaunchSmokeTest`
and `StaleOAuthCallbackTest` on a Gradle Managed Device weekly and on dispatch.
It is not a pull-request gate because shared-runner emulator boots are too slow
and flaky; `scripts/prove.sh` stays the local proof.

To reproduce a CI failure, check out the app SHA from the run summary in a
detached worktree with `sdk.dir` set and `putioSdkKotlinPath` unset, then run
the same command. A pull-request run tests a temporary merge commit; if it is
gone, merge the recorded second parent into the first and resolve conflicts
explicitly rather than substituting a newer base or head.

### Google Play delivery

[Google Play Internal](.github/workflows/play-internal.yml) uploads both
bundles and tags `v<version>`; [Google Play Release](.github/workflows/play-release.yml)
promotes or changes the rollout. Their inputs own the options.

- Every dispatch, `dry_run` included, needs Altay's OK: it loads the `release`
  Environment's secrets.
- Mobile and TV share the
  [putio-tv-native-legacy](https://github.com/putdotio/putio-tv-native-legacy)
  listing and its version codes. Google Play never takes a code twice, and
  codes up to 94 are taken. Each upload needs a new `version_name`
  ([`putioVersionCode`](build-logic/src/main/kotlin/PutioAndroidApplicationPlugin.kt));
  only an upload Google Play rejected may reuse its name.
- If the upload lands but its `tag` job fails, rerun only that job. A full
  rerun repeats the version code, and Google Play refuses it.
