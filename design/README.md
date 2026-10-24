# Design tokens

`tokens.dtcg.json` is the DTCG token graph from
[`@putdotio/design`](https://github.com/putdotio/putio-design)
(`dist/tokens.dtcg.json`), vendored verbatim. From the same release, the
production launcher icons `core/design/src/main/res/drawable-*/putio_icon.png`
are downscaled from `system/assets/app-icon-prod-dark.png`, the nightly ones
`{mobile,tv}/src/nightly/res/drawable-*/putio_icon.png`, which override them in
nightly builds, from `system/assets/app-icon-nightly-stars.png`. The wordmark
`core/design/src/main/res/drawable/putio_wordmark.xml`, on the phone welcome
screen and the TV banner (`tv/src/main/res/drawable/tv_banner.xml`, on
`@color/putio_app_bg`), is converted from `system/assets/logo-retro-dark.svg`:
paths keep their fills and even-odd rule, and a group translation absorbs the
SVG's offset viewBox. The converter rejects SVG features it does not model.

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
`./gradlew updateDesignMdColors` to regenerate the contract's `colors` block,
and `./gradlew verify`, which regenerates and re-pins the scheme.

The M3-role → token map is the binding contract in
[Design](../DESIGN.md#colors), which shows it as a generated `colors` block;
it lives as data in
[`DesignTokenCodegen.kt`](../build-logic/src/main/kotlin/DesignTokenCodegen.kt).
Generated code is `public` only where the apps read it: `PutioDesignTokens`, the
tokens in `APP_TOKENS`, the overscan ratios and the TV scheme. Every other token
and the mobile scheme, which `PutioTheme` applies, stay `internal`; an app that
needs another raw token adds it to `APP_TOKENS`.

The type and corner scales are Android's own, so they come from the root
`DESIGN.md` front matter instead of the token graph. The same task writes its
`typography` block as `PutioTypeScale`, each style's `fontFamily` as a
`PutioFontFamily` ([Brand fonts](#brand-fonts)), and its `rounded` block as
`PutioRadius`. `PutioTheme` maps the scale onto the Material slots stock
components read, sets every other slot in GT America, and `putioMono()` sets
numbers in Berkeley Mono. Change a value in `DESIGN.md`; the build regenerates
it. The TV app keeps the Compose for TV type until its design pass.

# Brand fonts

put.io's GT America and Berkeley Mono licences cover embedding them in the
Android apps, not committing or redistributing them, so the repository holds
only `brand-fonts.lock.json`. It pins each file's path on
`https://static.put.io`, its SHA-256, and the `DESIGN.md` family and weight it
serves: GT America Standard Regular (400) and Medium (500), and Berkeley Mono
Variable, whose weight axis covers 400. The faces are TrueType, Android's
native outline format. The sync downloads each missing or changed file into
the ignored `core/design/brand-fonts/`, verifies it before writing, and never
follows redirects. Bootstrap runs it:

```bash
./scripts/sync-brand-fonts.sh
```

`:core:design:generateDesignTokens` copies the installed files to `res/font`
and generates `PutioFontFamily`. Offline, in every build, it fails when an
installed file does not match its digest, when `DESIGN.md` sets a family weight
no locked face covers, or when the lock holds a face `DESIGN.md` never sets. A
family missing any of its files, as in a fresh clone or the CI `verify` job,
falls back to the system face (Roboto or the system monospace) and the build
says so. Every `bundle*Release` task, which only Google Play delivery runs,
depends on `:core:design:requireBrandFonts` and fails unless every locked file
is installed. CI's release job fetches the fonts, so it builds what ships.

To take a new face or weight, change `DESIGN.md`, add or replace the lock entry
with the file's SHA-256 (`shasum -a 256`), and run the sync.

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
states, and file-kind glyphs use fill. Saving to put.io (Transfers) and
downloading to this device keep distinct glyphs: `cloud-arrow-down` and
`download-simple`. Drawables stay black source art, tinted by Compose at the
point of use.
