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
