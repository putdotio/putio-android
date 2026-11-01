# putio-android

> **Work in progress.** A ground-up rewrite of the put.io Android app, not on
> Google Play yet; expect missing features and breaking changes between
> commits. The put.io TV app on Play stays until this app replaces it;
> [#14](https://github.com/putdotio/putio-android/issues/14) tracks the
> rollout. Bug reports are welcome; open an issue before sending a pull request.

Native Android app for put.io, built with Kotlin and Jetpack Compose for
phones, tablets, Android TV, and Fire TV from one codebase.

## What works today

- Mobile: sign-in, Files, Search, History, Transfers, Downloads with offline
  playback, Trash and Account, video and audio playback with Cast, and the
  widget, shortcuts and system file picker.
- TV: device-code sign-in, Home, Files, Search, History, Trash and Account,
  full-screen playback, and the launcher's Play Next row and system search.
- Both: crash reports unless Diagnostics is off, and product analytics only
  once the account turns them on.

[Architecture](./docs/architecture.md) describes the product areas and how
the code is split.

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
- [Architecture](./docs/architecture.md): product areas and module boundaries
- [Agent guide](./AGENTS.md): module layout, hazards, proof map and CI

## Security

Report vulnerabilities through the
[put.io security policy](https://github.com/putdotio/.github/blob/main/SECURITY.md).

## License

MIT, see [LICENSE](./LICENSE).
