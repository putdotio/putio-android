# Design tokens

`tokens.dtcg.json` is the DTCG token graph from
[`@putdotio/design` 3.2.1](https://github.com/putdotio/putio-design)
(`dist/tokens.dtcg.json`), vendored verbatim.

The Compose color schemes are generated from this file at build time by
`:app:generateDesignTokens` (task class in `buildSrc/`). Never hand-edit the
generated Kotlin or this JSON; to take a new design release:

```bash
cp ../putio-design/dist/tokens.dtcg.json design/tokens.dtcg.json
./gradlew verify   # regenerates and re-pins the scheme
```

The M3-role → token map is the binding contract in
[`platforms/android/DESIGN.md`](https://github.com/putdotio/putio-design/blob/main/platforms/android/DESIGN.md);
it lives as data in `buildSrc/src/main/kotlin/DesignTokenCodegen.kt`.

# Icons

`phosphor-icons.lock.json` pins `@phosphor-icons/core` by version, npm tarball
SHA-512 SRI, selected SVG paths, and source/output SHA-256 digests. Regeneration
downloads that one tarball, verifies it before parsing, and writes the selected
Android vector drawables:

```bash
./scripts/generate-icons.sh
```

The canonical `verify` task runs the network-free drift check. It hashes every
locked drawable and rejects stale generated `ic_ph_*` files:

```bash
./scripts/generate-icons.sh --check
```

Regular weight is the default for chrome, fill is reserved for selected or
active states, and file-kind glyphs use fill. Drawables remain black source art
and are tinted by Compose at the point of use.

# Mobile video

Video opens in immersive fullscreen with sensor landscape on phones. Tablets
and multiwindow use the available window. Back restores the shell's orientation
and system bars. Controls auto-hide during playback; tapping reveals them,
and system navigation remains available by an edge swipe. TalkBack and keyboard
navigation keep controls visible.

The overlay has a back arrow and raw filename, screen-centered play/pause and
ten-second seek buttons, and a compact timeline with inline timestamps. Audio,
Speed and Captions share a centered row of 20dp icons and labels. A dimmed overlay
protects transport contrast without separate decorative button circles.
Captions are always discoverable: Media3's supported tracks determine the list,
including embedded tracks absent from API subtitle metadata. Sheets retain the
existing speed, audio, and caption choices across player recreation.

Hierarchy references inspected through Mobbin:
[Netflix](https://mobbin.com/screens/2070ec46-5424-4a50-acf2-9f5f90d39b79) and
[Google TV](https://mobbin.com/screens/49c20f2e-b42b-4bdf-b0b1-367383b30623).
These are iOS references; native controls follow the Android binding above,
[Android immersive-content guidance](https://developer.android.com/design/ui/mobile/guides/layout-and-content/immersive-content),
and [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).
Overlay scrims protect legibility, controls have at least 48dp touch targets,
and choice sheets use Material 3. Short windows scroll the overlay rather than
clipping transport or settings. No catalogue metadata or artwork is invented.
