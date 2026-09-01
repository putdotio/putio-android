# Design tokens

`tokens.dtcg.json` is the DTCG token graph from
[`@putdotio/design` 3.0.0](https://github.com/putdotio/putio-design)
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
