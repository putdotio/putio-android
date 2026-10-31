# putio-android

> **Work in progress.** A ground-up rewrite of the put.io Android app, not on
> Google Play yet; expect missing features and breaking changes between
> commits. The put.io TV app on Play stays until this app replaces it;
> [#14](https://github.com/putdotio/putio-android/issues/14) tracks the
> rollout. Bug reports are welcome; open an issue before sending a pull request.

Native Android app for put.io, built with Kotlin and Jetpack Compose for
phones, tablets, Android TV, and Fire TV from one codebase.

## What works today

Mobile, on the emulator harness against the live API:

- OAuth sign-in, with the session in Android secure storage
- Files, Search, History, Transfers, Downloads, Trash and Account, with
  adaptive layouts for tablets
- Item actions from the overflow, multi-select from a long-press, public
  links and in-app image, PDF and text viewers
- Items shared with you and friends' folders, also from `files/<id>` links;
  Search there covers the whole account, since put.io can't scope it
- Video and background audio playback with resume, tracks, subtitles,
  picture-in-picture and positions saved to put.io
- Offline downloads with offline playback, also after a start with no
  network; the session is checked again when the network returns
- Transfer intake from magnet links, `.torrent` files and shared links
- A themed launcher icon, launcher shortcuts, a Transfers widget and the
  system file picker; the widget and picker are proven only on an emulator
  with faked data so far
- Google Cast from the players, resuming locally where the receiver stopped

TV:

- Device-code sign-in with a QR code
- Files, Search, History and Account
- Full-screen playback with D-pad scrubbing, resume and track pickers
- The launcher's Play Next row and Android TV system search, with the
  wordmark for files without a screenshot

Both apps follow the system font size up to a 1.3 font scale, as the iOS app
caps it; widgets and notifications keep the system scale.

Both apps report crashes and player failures to Sentry only while the
account's Diagnostics control is on, and send only exception types, stack
frames, device facts and failure codes.

Release builds send product analytics to PostHog only while the account's
Product analytics control is on: app opens and backgrounds, screens, saved
settings, and video playback starts, ends and failures. Every event passes the
allowlist in
[`AnalyticsEvent.kt`](core/telemetry/src/main/kotlin/io/putdotio/android/telemetry/AnalyticsEvent.kt),
which follows put.io's Analytics event contract. Mobile asks once per launch
while the control is off and unanswered, except on a launch that opens a link
or a shared transfer; debug builds ask only on a launch with the
`putio.harness.analytics_prompt` extra.

## Build and verify

Requires JDK 21, Node 24, `python3`, and, on macOS, Homebrew.

```bash
./scripts/bootstrap.sh   # once per machine: Android SDK, AVDs, brand fonts, local.properties
./gradlew verify
./gradlew :mobile:assembleProductionDebug :tv:assembleProductionDebug
./scripts/prove.sh mobile && ./scripts/prove.sh tv   # emulator launch proof with evidence
```

The [Agent guide](./AGENTS.md#build-and-verify) covers what the gate runs and
[building against an unreleased SDK](./AGENTS.md#kotlin-sdk).

## Docs

- [Design](./DESIGN.md): the Android design contract
- [Agent guide](./AGENTS.md): module layout, hazards, proof map and CI

## Security

Report vulnerabilities through the
[put.io security policy](https://github.com/putdotio/.github/blob/main/SECURITY.md).

## License

MIT, see [LICENSE](./LICENSE).
