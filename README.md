<div align="center">
  <p>
    <img src="https://static.put.io/images/putio-boncuk.png" width="72" alt="put.io boncuk">
  </p>

  <h1>putio-android</h1>

  <p>
    Native Android app for put.io on phones, tablets, Android TV, and Fire TV
  </p>

  <p>
    <a href="https://github.com/putdotio/putio-android/actions/workflows/ci.yml?query=branch%3Amain" style="text-decoration:none;"><img src="https://img.shields.io/github/actions/workflow/status/putdotio/putio-android/ci.yml?branch=main&style=flat&label=ci&colorA=000000&colorB=000000" alt="CI"></a>
    <a href="https://github.com/putdotio/putio-android/blob/main/LICENSE" style="text-decoration:none;"><img src="https://img.shields.io/github/license/putdotio/putio-android?style=flat&colorA=000000&colorB=000000" alt="license"></a>
  </p>
</div>

> [!NOTE]
> Work in progress and not on Google Play yet. The current put.io TV app stays
> until this one replaces it; [#14](https://github.com/putdotio/putio-android/issues/14)
> tracks the rollout. Open an issue before sending a pull request.

## Use

Sign in with your put.io account to browse, search, and stream your files.

- **Mobile:** Files, Search, History, Transfers, Trash, and Account; offline
  Downloads; video and audio playback with Cast; a widget, shortcuts, and the
  system file picker.
- **TV:** device-code sign-in, Home, Files, Search, History, Trash, and
  Account; full-screen playback; the launcher's Play Next row and system
  search.

Crash reports stay on unless Diagnostics is off. Product analytics run only
once the account turns them on.

## Build

Requires JDK 21, Node 24, `python3`, and, on macOS, Homebrew.

```bash
./scripts/bootstrap.sh
./gradlew verify
./gradlew :mobile:assembleProductionDebug :tv:assembleProductionDebug
```

`bootstrap.sh` runs once per machine. [Build and verify](./AGENTS.md#build-and-verify)
covers the gate and [building against an unreleased SDK](./AGENTS.md#kotlin-sdk).

## Docs

- [Design](./DESIGN.md): the Android design contract
- [Architecture](./docs/architecture.md): product areas and module boundaries
- [Agent guide](./AGENTS.md): hazards, proof map, and CI
- [Security policy](https://github.com/putdotio/.github/blob/main/SECURITY.md):
  report vulnerabilities privately

## License

[MIT](./LICENSE)
