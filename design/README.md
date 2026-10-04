# Design tokens

`tokens.dtcg.json` is the DTCG token graph from
[`@putdotio/design`](https://github.com/putdotio/putio-design)
(`dist/tokens.dtcg.json`), vendored verbatim. The nightly launcher icons in
`{mobile,tv}/src/nightly/res/drawable-*/putio_icon.png` are downscaled from the same
release's `system/assets/app-icon-nightly-stars.png`. The phone welcome
wordmark `mobile/src/main/res/drawable/putio_wordmark.xml` is converted from
`system/assets/logo-retro-dark.svg`: paths keep their fills and even-odd rule,
and a group translation absorbs the SVG's offset viewBox. The converter rejects
SVG features it does not model.

`putio-design.lock.json` pins the npm release by version and tarball SHA-512
SRI, and pins the SHA-256 of every source asset and of every written file.
The Compose color schemes and the TV overscan ratios (`tv.overscan.x/y`) are
generated from the JSON at build time by `:core:design:generateDesignTokens` (task
class in `build-logic/`), which records the locked version in the generated
header. Never hand-edit the generated Kotlin, the JSON, or the icons.

Syncing downloads that one tarball, verifies it and each source asset before
use, and writes the files only when every output matches the lock. Icon
downscaling uses `sips`, so syncing needs macOS:

```bash
./scripts/sync-design-assets.sh
```

`verify` runs the network-free drift check, which hashes every locked file:

```bash
./scripts/sync-design-assets.sh --check
```

To take a new design release, update the version, tarball URL and `integrity`
(`npm view @putdotio/design@<version> dist.integrity`) in the lock, run the
sync, and replace each digest it reports as unlocked. Then run
`./gradlew verify`, which regenerates and re-pins the scheme.

The M3-role → token map is the binding contract in
[`platforms/android/DESIGN.md`](https://github.com/putdotio/putio-design/blob/main/platforms/android/DESIGN.md);
it lives as data in `build-logic/src/main/kotlin/DesignTokenCodegen.kt`. The
generated code is `public` only where the apps read it: `PutioDesignTokens`, the
tokens listed in `APP_TOKENS`, the overscan ratios and the TV scheme. Every other
token and the mobile scheme, which `PutioTheme` applies, stay `internal`, so an
app that needs another raw token adds it to `APP_TOKENS`.

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

Video opens in immersive fullscreen. On phones, a landscape video turns the
window to sensor landscape; a portrait video keeps the window's orientation.
Tablets and multiwindow use the available window. Back restores the shell's
orientation and system bars. Controls auto-hide during playback; tapping
reveals them, and system navigation remains available by an edge swipe.
TalkBack and keyboard navigation keep controls visible.

The overlay has a back arrow and raw filename, screen-centered play/pause and
ten-second seek buttons, and a compact timeline with inline timestamps. Audio,
Speed and Captions share a centered row of 20dp icons and labels. A dimmed overlay
protects transport contrast without separate decorative button circles.
Captions are always discoverable unless the account hides subtitles
(`hide_subtitles`): Media3's supported tracks determine the list, including
embedded tracks absent from API subtitle metadata. Sheets retain the existing
speed, audio, and caption choices across player recreation.

Hierarchy references inspected through Mobbin:
[Netflix](https://mobbin.com/screens/2070ec46-5424-4a50-acf2-9f5f90d39b79) and
[Google TV](https://mobbin.com/screens/49c20f2e-b42b-4bdf-b0b1-367383b30623).
These are iOS references; native controls follow the Android binding above,
[Android immersive-content guidance](https://developer.android.com/design/ui/mobile/guides/layout-and-content/immersive-content),
and [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).
Overlay scrims protect legibility, controls have at least 48dp touch targets,
and choice sheets use Material 3. Short windows scroll the overlay rather than
clipping transport or settings. No catalogue metadata or artwork is invented.
