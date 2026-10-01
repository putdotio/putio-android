# putio-design 3.x · Android binding reconciliation

The Android app consumes the `@putdotio/design` DTCG graph as a tier-2
binding. Components and interaction stay native to Material 3 and Compose for
TV. This ledger records the remaining cross-repo gaps without treating preview
CSS as Compose source.

## Reconciled binding details

The design contract records these Android adapter behaviors:

- Navigation indicator geometry remains Material-owned. The current Compose
  Material 3 Expressive implementation renders `56x32dp`, not the older
  `64x32dp` preview value.
- Android TV file-row glyph size remains Compose for TV-owned:
  `ListItemDefaults.IconSize` renders `32dp`, not the preview's `42px` on the
  2x card.
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
`build-logic/src/main/kotlin/DesignTokenCodegen.kt`. Generated Kotlin remains an
output and must not be edited by hand.

## Remaining gaps

- GT America and Berkeley Mono have no licensed, app-embeddable font files.
  Android uses system fonts until licensing and delivery are resolved under
  [#48](https://github.com/putdotio/putio-android/issues/48).
- The design icon catalogs now prefer regular file icons while the Android
  adapter generates filled file icons. [#21](https://github.com/putdotio/putio-android/issues/21)
  owns that decision; the lock manifest is `design/phosphor-icons.lock.json`.
- The production launcher icon is an in-repo vector. A canonical production
  source asset is still absent from the design package; nightly already uses
  the packaged stars icon.
