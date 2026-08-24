# putio-design 3.0.0 · Android binding discrepancies

The 3.0.0 design round was written without reading this repo
(`platforms/android/DESIGN.md` front matter: "No Android source repo is
tracked"). This file lists where the cards and the shipped source disagree, so
the next design revision can be grounded in the actual app instead of
guessing. Owner of each decision noted per row.

## The repo exists

`putdotio/putio-android` is the native Compose rewrite (program
[#14](https://github.com/putdotio/putio-android/issues/14)), one codebase for
Android mobile and Android TV / Fire TV. Point the design system at it.

## androidtv-s00-shell documents the app this repo replaces

The card's tier bar says the Android TV app is "the same React Native codebase
as tv.put.io (`apps/tv-native`)" and its don't-list forbids exactly what
`platforms/android/DESIGN.md` §Android TV specifies:

| Card (androidtv-s00-shell) | DESIGN.md §Android TV | This repo ships |
| --- | --- | --- |
| "Focus is a filled rounded row. Not Compose's scale-plus-elevation" | "Focus is Compose for TV's: the focused `Surface` scales and elevates" | Compose for TV platform focus (scale + elevate, no tilt) |
| Rebuilt from the shipped RN app | Compose for TV | Compose for TV (`androidx.tv:tv-material`) |

Both statements can't bind the same platform. This repo follows DESIGN.md and
the rewrite program: platform focus engine, list-first, no poster wall. The
card should be re-drawn from this repo once a files screen ships, the way the
Roku cards were grounded.

Internal inconsistency in the same sources: DESIGN.md says the TV list glyph
is 42px, the card says 44px. We render 21dp (= 42px at the tv_1080p xhdpi
density). One number should win.

## Nav indicator: the card's 64×32dp is the old M3 token

Measured on device, stock `NavigationBarItem` in the current Compose BOM
(2026.06.01, M3 expressive) draws the active indicator at 56×32dp. The card
and DESIGN.md say 64×32dp — the pre-expressive M3 spec value. Per the tier
rule ("Material decides"), the app ships Material's current number; the spec
should update its metric or accept drift as Material moves.

## TV metrics are px on a 1920×1080 canvas; the app runs at 960×540dp

Android TV at 1080p renders at xhdpi, so every px in the tv token group is
2 physical px per dp. The app halves the card values (80px side padding →
40dp, 64px top → 32dp). The next revision should state TV metrics in dp, as
the phone cards already do.

## Fonts: specified but not deliverable

GT America and Berkeley Mono are specified as the type families; the cards
load them as web fonts from `static.put.io`. No app-embeddable binaries
(ttf/otf) exist in any tracked repo, and both are commercial faces whose
app-embedding licensing is not documented anywhere we can see. The app ships
system fonts (Roboto) until the design/licensing owners deliver embeddable
files. Decision owner: put.io design + licensing.

## The M3 role map is 11 roles; Material has more

`darkColorScheme(...)` roles the contract does not name fall back to M3
baseline values — including baseline purple `secondary`/`tertiary` and their
containers, which stock chips and filled tonal components read. This repo
derives four extra entries to keep stock components on-token:

- `background`/`onBackground` mirror `surface`/`onSurface`
- `primaryContainer`/`onPrimaryContainer` = `--yellow-solid` /
  `--primary-foreground`, because the stock FAB reads `primaryContainer` and
  the android-s00-shell card specs the FAB in exactly those tokens
- `secondaryContainer` = `--yellow-solid` at 26% and `onSecondaryContainer` =
  `--yellow-solid`, because the stock `NavigationBarItem` indicator reads
  `secondaryContainer` and the card specs "64×32dp pill at 26% primary" with
  a yellow glyph — without the mapping the pill renders baseline purple

The next revision should either extend the table or state that baseline
fallbacks are accepted. Same for the Compose for TV `ColorScheme`, which
predates the `surfaceContainer` tiers and names its outline roles
`border`/`borderVariant`; our projection lives in
`buildSrc/src/main/kotlin/DesignTokenCodegen.kt` (`TV_ROLES`).

## SWF has no Phosphor glyph

The put.io API's file kinds are FOLDER, FILE, AUDIO, VIDEO, IMAGE, ARCHIVE,
PDF, TEXT, SWF. Phosphor has no swf/flash file icon; the app maps SWF to
`file-code`. If the design round wants a different stand-in, say so in the
icon section.

## Shipped behavior the cards don't know about

- Flavor matrix is `surface` (`mobile`, `tv`) × `channel` (`production`,
  `nightly`); debug ids append `.debug`. The TV package/release identity
  (`io.put.putio`) is preserved per program rule.
- The production launcher icon is an in-repo vector
  (`app/src/main/res/drawable/putio_icon.xml`), not design-system art;
  nightly uses `app-icon-nightly-stars.png` from putio-design. A production
  icon source in the design system would close that gap.
