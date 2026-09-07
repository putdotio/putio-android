# putio-android

> **Work in progress.** This is a ground-up rewrite of the put.io Android app
> and is not on Google Play yet. Expect missing features and breaking changes
> between commits. The Android TV app on Play today is the existing put.io
> TV app; it stays there until this app replaces it.
> [Epic #14](https://github.com/putdotio/putio-android/issues/14) tracks the
> rollout. Bug reports are welcome; open an issue before sending a pull request.

Native Android app for put.io, built with Kotlin and Jetpack Compose for
phones, tablets, Android TV, and Fire TV from one codebase. Mobile ships first;
the TV surface reuses the shared data, domain, theme, and component layers.

## What works today

Mobile, on the emulator harness against the live API:

- OAuth sign-in through an Auth Tab, with the session in Android secure storage
- Files: browse, paginate, sort per folder, rename, move, trash, delete, and
  watched progress on media rows
- Trash: browse, restore one or all, delete permanently, empty
- Transfers and search
- Video playback with subtitles, autoplay next, and configurable HLS or MP4
- Account settings: subtitles, history, Trash, resume playback, proxy route,
  and default sort order, each saved to the account and shared across devices

The TV flavor builds and launches on the Android TV emulator but has no
feature surfaces yet. Not started: downloads, sharing, Chromecast,
Picture-in-Picture, and the Play release lane.

## Direction

- Compose-first app with `mobile` and `tv` flavors
- [`putio-sdk-kotlin`](https://github.com/putdotio/putio-sdk-kotlin) is the
  API boundary, consumed as a Gradle composite build until it ships on Maven
  Central
- Mobile-first rollout with touch and tablet-adaptive navigation
- TV shell built around D-pad focus, Android TV launcher metadata, media
  sessions, and system search
- TV behavior matches the current put.io Android TV app; libVLC or
  original-file playback is a later, separately proven upgrade
- Colors and component roles come from
  [`@putdotio/design`](https://github.com/putdotio/putio-design); Phosphor is
  the icon source

## Build and verify

Requires JDK 21 and, on macOS, Homebrew. Everything else is scripted.

```bash
./scripts/bootstrap.sh   # once per machine; installs the Android SDK, AVDs, local.properties
./gradlew verify
./gradlew :app:assembleMobileProductionDebug
./gradlew :app:assembleTvProductionDebug
./scripts/prove.sh mobile && ./scripts/prove.sh tv   # emulator launch proof with evidence
```

`verify` runs lint, detekt, and the unit tests, then assembles an unsigned
minified mobile release APK to prove the composite SDK survives minSdk 26 and
R8. The phone AVD runs the API 37 Google Play image and checks Chrome Auth Tab
readiness on every boot; Android TV and the scheduled CI device stay on API 36.

The `bootstrap` script expects `putio-sdk-kotlin` cloned as a sibling
directory, or a `putioSdkKotlinPath` in `local.properties`.

## Docs

- [Agent guide](./AGENTS.md): setup, emulator flows, evidence conventions, and
  the definition of done
- [Harness](./docs/harness.md): emulator lifecycle, recording, live API proof
- [Design system binding](./design/README.md): tokens and icon generation

## License

MIT, see [LICENSE](./LICENSE).
