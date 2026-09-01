# putio-android

Native Android app for put.io, built with Kotlin and Jetpack Compose for mobile,
Android TV, and Fire TV from one codebase. Mobile ships first; the TV surface
then reuses the shared data, domain, theme, and component foundations.

## Direction

- Compose-first Android app with `mobile` and `tv` flavors
- `putio-sdk-kotlin` as the API boundary through a local composite build
- Mobile-first rollout with touch and tablet-adaptive navigation
- TV shell built around D-pad focus, Android TV launcher metadata, media sessions, and system search
- TV behavior parity follows the exported Android TV packet; libVLC/original-file playback remains a deliberate later upgrade proof

## Current Status

The shared foundation, secure mobile Auth Tab flow, adaptive shell, and initial
Files surface are implemented. Rollout work is tracked under
[epic #14](https://github.com/putdotio/putio-android/issues/14).

Current doctrine:

- Android TV parity comes from the exported screenshot/spec bundle, not memory
- `tv-native` is a behavior oracle, not a visual or architectural source of truth
- Focus polish is expected implementation work
- Colors and component roles come from `@putdotio/design`; Phosphor is the icon source
- `putio-sdk-kotlin` is the API boundary and should be tuned as the app exposes concrete gaps

Available inputs:

- `../putio-web/apps/tv-native` for behavior, focus, routes, and fresh captures when the packet is ambiguous
- `../putio-frontend/docs/specs/tv-app/android-tv/` for exported Android TV screenshots
- `../putio-frontend/docs/specs/tv-app/current-baseline.md` for behavior notes and capture provenance
- `design/tokens.dtcg.json` for the vendored `@putdotio/design` token graph
- `scripts/generate-icons.sh` for the vendored Phosphor drawable set
- `../putio-sdk-kotlin` for the API boundary and app-driven SDK tuning
- A bootable React Native reference app for fresh comparison when a state is missing or ambiguous

## Useful Docs

- [Migration assessment](./docs/migration-from-tv-native.md)
- [TV app packet](../putio-frontend/docs/specs/tv-app/README.md)
- [Platform strategy](../putio-frontend/docs/platform-strategy.md)
- [TV app feature spec](../putio-frontend/docs/specs/tv-app/feature-spec.md)
- [Kotlin SDK](../putio-sdk-kotlin/README.md)

## Verify

```bash
./scripts/bootstrap.sh   # once per machine; provisions SDK, AVDs, local.properties
./gradlew verify
./gradlew :app:assembleTvProductionDebug
./gradlew :app:assembleMobileProductionDebug
./scripts/prove.sh mobile && ./scripts/prove.sh tv   # emulator launch proof + evidence
```

[AGENTS.md](./AGENTS.md) is the operating manual for setup, emulator flows,
and evidence conventions.
