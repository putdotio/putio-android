# putio-android

Native Android app for put.io, built with Kotlin and Jetpack Compose.

The first target is Android TV / Fire TV parity with the existing React Native TV app in `../putio-web/apps/tv-native`, while keeping the codebase shaped for Android mobile from the start.

## Direction

- Compose-first Android app with `mobile` and `tv` flavors
- `putio-sdk-kotlin` as the API boundary through a local composite build
- TV shell built around D-pad focus, Android TV launcher metadata, media sessions, and system search
- Native playback path planned around libVLC, with ExoPlayer/Media3 useful for proof and fallback work

## Current Status

This repo is a bootstrap shell. The GitHub remote and registry entry are still pending. Do not add this repo to `../putio-frontend-handbook/repos.json` until `putdotio/putio-android` exists and visibility is confirmed.

## Useful Docs

- [Migration assessment](./docs/migration-from-tv-native.md)
- [Platform strategy](../putio-frontend-handbook/docs/platform-strategy.md)
- [TV app spec](../putio-design/docs/specs/tv-app.md)
- [Kotlin SDK](../putio-sdk-kotlin/README.md)

## Planned Verify

```bash
./gradlew verify
./gradlew :app:assembleTvDebug
./gradlew :app:assembleMobileDebug
```

