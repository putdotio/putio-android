# putio-android

Native Android app for put.io, built with Kotlin and Jetpack Compose.

The first target is Android TV / Fire TV parity with the exported React Native TV reference in `../putio-frontend-workspace/docs/specs/tv-native/`, while keeping the codebase shaped for Android mobile from the start.

## Direction

- Compose-first Android app with `mobile` and `tv` flavors
- `putio-sdk-kotlin` as the API boundary through a local composite build
- TV shell built around D-pad focus, Android TV launcher metadata, media sessions, and system search
- Phase-one playback matches the exported Android TV behavior first; libVLC/original-file playback comes later as a deliberate upgrade proof

## Current Status

This repo is a bootstrap shell. The GitHub remote exists at `putdotio/putio-android`, is private, and is listed in `../putio-frontend-workspace/repos.json`.

Current doctrine:

- Android TV parity comes from the exported screenshot/spec bundle, not memory
- Focus polish is expected implementation work
- Colors, menus, icons, and component patterns should be extracted from `tv-native` and refined into native Compose tokens
- `putio-sdk-kotlin` is the API boundary and should be tuned as the app exposes concrete gaps

Available inputs:

- `../putio-web/apps/tv-native` for source-level behavior, colors, menus, icons, components, focus behavior, and API usage
- `../putio-frontend-workspace/docs/specs/tv-native/android-tv/` for exported Android TV screenshots
- `../putio-frontend-workspace/docs/specs/tv-native/README.md` for behavior notes and capture provenance
- `../putio-sdk-kotlin` for the API boundary and app-driven SDK tuning
- A bootable React Native reference app for fresh comparison when a state is missing or ambiguous

## Useful Docs

- [Migration assessment](./docs/migration-from-tv-native.md)
- [Exported TV reference](../putio-frontend-workspace/docs/specs/tv-native/README.md)
- [Platform strategy](../putio-frontend-workspace/docs/platform-strategy.md)
- [TV app spec](../putio-design/docs/specs/tv-app.md)
- [Kotlin SDK](../putio-sdk-kotlin/README.md)

## Planned Verify

```bash
./gradlew verify
./gradlew :app:assembleTvDebug
./gradlew :app:assembleMobileDebug
```
