# Design tokens

`tokens.dtcg.json` is the DTCG token graph from
[`@putdotio/design`](https://github.com/putdotio/putio-design)
(`dist/tokens.dtcg.json`), vendored verbatim. From the same release, the
nightly launcher icons `{mobile,tv}/src/nightly/res/drawable-*/putio_icon.png`
are downscaled from `system/assets/app-icon-nightly-stars.png`, and the phone
welcome wordmark `mobile/src/main/res/drawable/putio_wordmark.xml` is converted
from `system/assets/logo-retro-dark.svg`: paths keep their fills and even-odd
rule, and a group translation absorbs the SVG's offset viewBox. The converter
rejects SVG features it does not model.

`putio-design.lock.json` pins the npm release by version and tarball SHA-512
SRI, plus the SHA-256 of every source asset and written file. At build time
`:core:design:generateDesignTokens` generates the Compose color schemes and the
TV overscan ratios (`tv.overscan.x/y`) from the JSON, recording the locked
version in the generated header. It also writes `@color/putio_<css-name>`
resources for the tokens in `RESOURCE_TOKENS`, for XML Compose cannot reach:
the home-screen widget's RemoteViews layout and the adaptive shortcut icons.
Never hand-edit the generated Kotlin, the JSON, or the icons.

Syncing downloads the locked tarball, verifies it and each source asset, and
writes files only when every output matches the lock. Icon downscaling uses
`sips`, so syncing needs macOS:

```bash
./scripts/sync-design-assets.sh           # sync
./scripts/sync-design-assets.sh --check   # network-free drift check; part of verify
```

To take a new design release, update the version, tarball URL and `integrity`
(`npm view @putdotio/design@<version> dist.integrity`) in the lock, run the
sync, and replace each digest it reports as unlocked. Then run
`./gradlew verify`, which regenerates and re-pins the scheme.

The M3-role → token map is the binding contract in
[Design](../docs/design.md#binding);
it lives as data in
[`DesignTokenCodegen.kt`](../build-logic/src/main/kotlin/DesignTokenCodegen.kt).
Generated code is `public` only where the apps read it: `PutioDesignTokens`, the
tokens in `APP_TOKENS`, the overscan ratios and the TV scheme. Every other token
and the mobile scheme, which `PutioTheme` applies, stay `internal`; an app that
needs another raw token adds it to `APP_TOKENS`.

# Icons

`phosphor-icons.lock.json` pins `@phosphor-icons/core` by version, npm tarball
SHA-512 SRI, selected SVG paths, and source and output SHA-256 digests.
Regeneration downloads that tarball, verifies it before parsing, and writes the
selected Android vector drawables:

```bash
./scripts/generate-icons.sh           # regenerate
./scripts/generate-icons.sh --check   # network-free drift check; part of verify
```

The check hashes every locked drawable and rejects stale generated `ic_ph_*`
files. Regular weight is the default for chrome, fill marks selected or active
states, and file-kind glyphs use fill. Drawables stay black source art, tinted
by Compose at the point of use.
