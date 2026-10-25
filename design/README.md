# Design tokens

`tokens.dtcg.json` is the DTCG token graph from
[`@putdotio/design`](https://github.com/putdotio/putio-design)
(`dist/tokens.dtcg.json`), vendored verbatim. The same release supplies the
launcher icons for each channel (production, nightly, and mobile's debug dev
icon), the adaptive launcher art, and the retro wordmark on the phone welcome
screen and the TV banner. [`design_assets.py`](../scripts/design_assets.py)
writes them; the wordmark converter rejects SVG features it does not model.

`putio-design.lock.json` pins the npm release by version and tarball SHA-512
SRI, plus the SHA-256 of every source asset and written file. At build time
`:core:design:generateDesignTokens` generates the Compose color schemes, the TV
overscan ratios, and `@color/putio_<css-name>` resources for the tokens in
`RESOURCE_TOKENS`, which XML outside Compose reads (the widget and shortcut
icons). Never hand-edit the generated Kotlin, the JSON, or the icons.

Syncing verifies the locked tarball and each source asset, and writes only when
every output matches the lock. It uses `sips`, so it needs macOS:

```bash
./scripts/sync-design-assets.sh           # sync
./scripts/sync-design-assets.sh --check   # network-free drift check; part of verify
```

To take a new design release:

1. Update the version, tarball URL and `integrity`
   (`npm view @putdotio/design@<version> dist.integrity`) in the lock.
2. Run the sync, and replace each digest it reports as unlocked.
3. Run `./gradlew updateDesignMdColors` to regenerate the `DESIGN.md` `colors`
   block, then `./gradlew verify`.

The M3 role → token map lives as data in
[`DesignTokenCodegen.kt`](../build-logic/src/main/kotlin/DesignTokenCodegen.kt);
[Design](../DESIGN.md#colors) shows it as the generated `colors` block.
Generated code is `public` only where the apps read it: `PutioDesignTokens`,
the tokens in `APP_TOKENS`, the overscan ratios and the TV scheme. An app that
needs another raw token adds it to `APP_TOKENS`.

The type and corner scales are Android's own, so they come from the
`DESIGN.md` front matter, not the token graph. The same task writes its
`typography` block as `PutioTypeScale`, each `fontFamily` as a
`PutioFontFamily` ([Brand fonts](#brand-fonts)), and its `rounded` block as
`PutioRadius`. Change a value in `DESIGN.md`; the build regenerates it. The TV
app keeps the Compose for TV type until its design pass.

## Brand fonts

The GT America and Berkeley Mono licences cover embedding them in the apps, not
committing or redistributing them. The repository holds only
`brand-fonts.lock.json`: each file's path on `https://static.put.io`, its
SHA-256, and the `DESIGN.md` family and weight it serves. The sync downloads
missing or changed files into the ignored `core/design/brand-fonts/`, verifies
them before writing, and never follows redirects. Bootstrap runs it:

```bash
./scripts/sync-brand-fonts.sh
```

Every build fails when an installed file does not match its digest, when
`DESIGN.md` sets a weight no locked face covers, or when the lock holds a face
`DESIGN.md` never sets. A family missing any file, as in a fresh clone or CI's
`verify` job, falls back to the system face, and the build says so. Every
`bundle*Release` task requires every locked file, so Google Play builds always
ship the brand fonts.

To take a new face or weight, change `DESIGN.md`, add or replace the lock entry
with the file's SHA-256 (`shasum -a 256`), and run the sync.

## Icons

`phosphor-icons.lock.json` pins `@phosphor-icons/core` by version, tarball
SHA-512 SRI, selected SVG paths, and source and output SHA-256 digests.

```bash
./scripts/generate-icons.sh           # regenerate
./scripts/generate-icons.sh --check   # network-free drift check; part of verify
```

The check also rejects stale generated `ic_ph_*` files.

- Regular weight is the default for chrome; fill marks selected or active
  states and file-kind glyphs.
- Saving to put.io (Transfers) and downloading to this device keep distinct
  glyphs: `cloud-arrow-down` and `download-simple`.
- Drawables stay black source art, tinted by Compose where they are used. XML
  outside Compose, such as the shortcut icons, uses a copy whose lock entry
  names a generated `@color/putio_*` token as its `tint`.
