# Agent Guide

## Repo

- Native Android app for put.io, covering Android mobile and Android TV / Fire TV from one Compose codebase
- Uses `putio-sdk-kotlin` as the API boundary; do not bypass it with ad hoc HTTP unless the SDK gap is documented first
- Android TV should feel like Android TV: Compose for TV, D-pad focus, system media/session behavior, and platform-native search where applicable

## Start Here

- [Overview](./README.md)
- [Migration assessment](./docs/migration-from-tv-native.md)
- Shared handbook: `../putio-frontend-handbook/AGENTS.md`
- TV product spec: `../putio-design/docs/specs/tv-app.md`
- Kotlin SDK guide: `../putio-sdk-kotlin/AGENTS.md`

## Commands

```bash
./gradlew verify
./gradlew :app:assembleTvDebug
./gradlew :app:assembleMobileDebug
```

## Rules

- Keep mobile and TV shared at the data, domain, theme, and component-foundation layers
- Let UI shells diverge when input model differs: touch for mobile, D-pad/remote for TV
- Prefer SDK changes in `../putio-sdk-kotlin` over app-local API workarounds
- Preserve the current Android TV package/release identity unless product/release owners decide to create a new listing
- Store tokens in Android platform secure storage; never commit sample secrets or OAuth tokens
- Use the handbook docs for cross-repo policy, but keep app-specific build, verification, and architecture notes in this repo

## Verify

Use `./gradlew verify` as the local guardrail once Gradle wrapper and Android SDK setup are complete. Until then, verify doc and scaffold changes with path checks and Gradle file inspection.

