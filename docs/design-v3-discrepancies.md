# putio-design 3.x · Android binding reconciliation

The Android app consumes the `@putdotio/design` DTCG graph as a tier-2
binding. Components and interaction stay native to Material 3 and Compose for
TV. This ledger records the remaining cross-repo gaps without treating preview
CSS as Compose source.

## August 2026 design update

`putio-design` commit `15a3201` expands the Android TV contract with an M3
navigation drawer, Search, Account and continue-watching cards. It changes no
token source or generated token artifact. The app's vendored graph and design's
`dist/tokens.dtcg.json` remain byte-identical at SHA-256
`a915906059426c95bde3fea77b4de72e1097fc50d46842b417143172dfc5c0c6`, so the
Android token version advances to `3.2.1` while retaining the byte-identical
graph.

The update resolves two earlier discrepancies:

- Android TV is now documented as Compose for TV with scale and elevation,
  rather than as the React Native app this repository replaces.
- TV cards now state their 1920x1080 canvas is a 2x rendering of the
  960x540dp app surface.

No Android mobile behavior changed. The new TV implementation work belongs to
[#32](https://github.com/putdotio/putio-android/issues/32),
[#33](https://github.com/putdotio/putio-android/issues/33) and
[#34](https://github.com/putdotio/putio-android/issues/34), not the weekend
mobile auth-to-Files slice.

## Reconciled binding details

The paired design-contract update records the behavior already shipped by the
Android adapter:

- Navigation indicator geometry remains Material-owned. The current Compose
  Material 3 Expressive implementation renders `56x32dp`, not the older
  `64x32dp` preview value.
- Android TV file-row glyphs use `42px` on the 2x card and `21dp` on the xhdpi
  emulator.
- The phone scheme includes the secondary, container, background and inverse
  roles used by stock navigation, bottom-sheet, FAB and snackbar components.
- Compose for TV receives the equivalent container and inverse-role projection
  through its older API, including `surfaceVariant`, `inverseSurface`,
  `inverseOnSurface`, `border` and `borderVariant`. Stock TV `ListItem` focus
  reads `inverseSurface`, so leaving it at the Material baseline is not safe.
- `SWF` uses Phosphor `file-code` because Phosphor has no dedicated SWF glyph.
- The design contract acknowledges that a native Android implementation owns
  the generated platform adapter; `putio-design` remains free of Kotlin and
  Android XML outputs.

The role projections live in
`buildSrc/src/main/kotlin/DesignTokenCodegen.kt`. Generated Kotlin remains an
output and must not be edited by hand.

## Remaining gaps

- GT America and Berkeley Mono have no licensed, app-embeddable font files.
  Android uses system fonts until licensing and delivery are resolved under
  [#48](https://github.com/putdotio/putio-android/issues/48).
- The design icon catalogs now prefer regular file icons while the Android
  adapter generates filled file icons. [#21](https://github.com/putdotio/putio-android/issues/21)
  owns that decision and the future icon lock manifest.
- The production launcher icon is an in-repo vector. A canonical production
  source asset is still absent from the design package; nightly already uses
  the packaged stars icon.
