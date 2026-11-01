---
version: alpha
name: "put.io on Android"
description: "Design contract for the Android phone, tablet and TV apps: Material 3 Expressive on the put.io token graph."
tier: 2
mode: "dark only"
canvas:
  phone: "412x915dp"
  tablet: "1280x800dp"
  tv: "960x540dp"
typography:
  headline-lg:
    fontFamily: GT America
    fontSize: 28px
    fontWeight: 500
    lineHeight: 36px
  title-lg:
    fontFamily: GT America
    fontSize: 22px
    fontWeight: 500
    lineHeight: 28px
  body-lg:
    fontFamily: GT America
    fontSize: 16px
    fontWeight: 400
    lineHeight: 22px
  body-md:
    fontFamily: GT America
    fontSize: 15px
    fontWeight: 400
    lineHeight: 20px
  body-sm:
    fontFamily: GT America
    fontSize: 14px
    fontWeight: 400
    lineHeight: 20px
  meta:
    fontFamily: GT America
    fontSize: 13px
    fontWeight: 400
    lineHeight: 18px
  label-md:
    fontFamily: GT America
    fontSize: 13px
    fontWeight: 500
    lineHeight: 18px
  label-sm:
    fontFamily: GT America
    fontSize: 12px
    fontWeight: 500
    lineHeight: 16px
  mono:
    fontFamily: Berkeley Mono
    fontSize: 13px
    fontWeight: 400
    lineHeight: 18px
    fontFeature: '"tnum"'
  tv-title:
    fontFamily: GT America
    fontSize: 22px
    fontWeight: 500
    lineHeight: 28px
  tv-detail-title:
    fontFamily: GT America
    fontSize: 18px
    fontWeight: 500
    lineHeight: 24px
  tv-body:
    fontFamily: GT America
    fontSize: 13px
    fontWeight: 400
    lineHeight: 18px
  tv-button:
    fontFamily: GT America
    fontSize: 14px
    fontWeight: 500
    lineHeight: 20px
  tv-label:
    fontFamily: GT America
    fontSize: 12px
    fontWeight: 500
    lineHeight: 16px
  tv-meta:
    fontFamily: GT America
    fontSize: 12px
    fontWeight: 400
    lineHeight: 16px
  tv-mono:
    fontFamily: Berkeley Mono
    fontSize: 12px
    fontWeight: 400
    lineHeight: 16px
    fontFeature: '"tnum"'
rounded:
  progress: 2px
  extra-small: 4px
  small: 8px
  medium: 12px
  large: 16px
  large-increased: 20px
  extra-large: 28px
  full: 9999px
spacing:
  hairline: 2px
  card-gap: 8px
  gutter: 16px
  dialog-padding: 24px
  touch-target: 48px
  icon: 24px
components:
  navigation-bar:
    backgroundColor: "{colors.surface-container-low}"
    height: 80px
  navigation-indicator:
    backgroundColor: "{colors.secondary-container}"
    rounded: "{rounded.full}"
  navigation-rail:
    width: 96px
  search-bar:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface-variant}"
    rounded: "{rounded.full}"
    height: 56px
  top-app-bar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    typography: "{typography.title-lg}"
    height: 64px
  tab-title:
    textColor: "{colors.on-surface}"
    typography: "{typography.headline-lg}"
  file-row:
    textColor: "{colors.on-surface}"
    typography: "{typography.body-lg}"
    height: 64px
  settings-row:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.on-surface}"
    typography: "{typography.body-md}"
    rounded: "{rounded.extra-small}"
    height: 56px
  settings-group:
    rounded: "{rounded.large}"
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.full}"
    height: 40px
  button-tonal:
    backgroundColor: "{colors.surface-container-highest}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.full}"
    height: 40px
  button-destructive:
    backgroundColor: "{colors.error}"
    textColor: "{colors.background}"
    rounded: "{rounded.full}"
    height: 40px
  destructive-text:
    textColor: "{colors.red-text-secondary}"
  fab-extended:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary-container}"
    rounded: "{rounded.large}"
    height: 56px
  action-tile:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.large-increased}"
    height: 72px
  bottom-sheet:
    backgroundColor: "{colors.surface-container-low}"
    rounded: "{rounded.extra-large}"
  dialog:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    typography: "{typography.title-lg}"
    rounded: "{rounded.extra-large}"
    padding: 24px
  menu:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.large}"
    width: 240px
  menu-item-selected:
    backgroundColor: "{colors.surface-container-highest}"
    height: 44px
  notice:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.large}"
  snackbar:
    backgroundColor: "{colors.inverse-surface}"
    textColor: "{colors.inverse-on-surface}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.small}"
    height: 48px
  transfer-card:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.large}"
  transfer-field:
    backgroundColor: "{colors.surface-container-high}"
    rounded: "{rounded.medium}"
  state-dot-downloading:
    backgroundColor: "{colors.primary}"
  state-dot-seeding:
    backgroundColor: "{colors.green-solid}"
  state-dot-failed:
    backgroundColor: "{colors.error}"
  switch-on:
    backgroundColor: "{colors.on-surface}"
    width: 44px
    height: 26px
  switch-off:
    backgroundColor: "{colors.surface}"
    width: 44px
    height: 26px
  switch-off-handle:
    backgroundColor: "{colors.solid}"
    rounded: "{rounded.full}"
    width: 12px
    height: 12px
  switch-on-handle:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.full}"
    width: 18px
    height: 18px
  progress:
    backgroundColor: "{colors.primary}"
    rounded: "{rounded.progress}"
    height: 4px
  widget:
    backgroundColor: "{colors.background}"
    textColor: "{colors.on-background}"
    rounded: "{rounded.extra-large}"
---

# put.io on Android

The design contract for the phone, tablet and TV apps. Tokens, voice and the
binding tiers come from
[putio-design](https://github.com/putdotio/putio-design/blob/main/DESIGN.md);
this file owns how Android applies them. Screen layouts live in the code and
the Android design canvas, not here. Dimensions are in dp and text sizes in
sp, written as `px` because the format has no Android units.

## Overview

Tier 2: put.io tokens on stock Material 3 Expressive on the phone and Compose
for TV on the TV. Build a component from the platform and give it put.io
colour, type and icons; draw a custom one only where this file says so. Dark
only, no dynamic colour. The platform owns pressed, disabled, selected and
focused states, except TV focus below.

The app reads as a quiet file manager and player: neutral surfaces, dense rows,
mono numbers, and yellow only where it marks content or the one action that
matters.

## Colors

The app generates its colour scheme at build time from the vendored token
graph in `design/`. `./gradlew updateDesignMdColors` writes the block below
from the same graph and role map, and `verify` fails when it is stale. Colours
with no Material role come from the generated tokens, never from hand-written
values.

```yaml
colors:
  primary: "#FDCE45" # --yellow-solid
  on-primary: "#2A1E09" # --primary-foreground
  primary-container: "#FDCE45" # --yellow-solid
  on-primary-container: "#2A1E09" # --primary-foreground
  secondary: "#EDEDED" # --text
  secondary-container: "#FDCE4542" # --yellow-solid at 26%
  on-secondary-container: "#FDCE45" # --yellow-solid
  background: "#161616" # --app-bg
  on-background: "#EDEDED" # --text
  surface: "#161616" # --app-bg
  on-surface: "#EDEDED" # --text
  on-surface-variant: "#A0A0A0" # --text-secondary
  surface-container-low: "#232323" # --component-bg
  surface-container: "#232323" # --component-bg
  surface-container-high: "#282828" # --component-bg-hover
  surface-container-highest: "#2E2E2E" # --component-bg-active
  inverse-surface: "#2E2E2E" # --component-bg-active
  inverse-on-surface: "#EDEDED" # --text
  inverse-primary: "#FFD147" # --yellow-text-secondary
  outline: "#3E3E3E" # --border
  outline-variant: "#343434" # --line
  error: "#E5484D" # --red-solid
  on-error: "#161616" # --app-bg
  red-text-secondary: "#FF8E8A" # --red-text-secondary
  green-solid: "#30A46C" # --green-solid
  solid: "#707070" # --solid
```

- **Neutral** does the work: surfaces, switches, transport buttons, status
  glyphs and every button but one.
- **Content yellow** marks content: file-type glyphs, progress, the on-device
  marker, state dots, the selected tab, checkboxes and radios.
- **Action yellow** is the one primary action of a screen or dialog.
- **Red** is for destructive actions, failures and warnings (the
  inactive-account glyph, a full storage bar). Red text and glyphs
  use `red-text-secondary`, since `error` text fails contrast on the surface
  tiers; a red button keeps the `error` fill with dark text.
- **Green** marks seeding and finished transfers, and what product analytics
  would send.
- **Tints under text** (selected rows, transfer progress fills) stay light
  enough that `on-surface-variant` meta keeps 4.5:1: 9% yellow, 12% green.

## Typography

GT America for UI; Berkeley Mono with tabular figures for sizes, times,
speeds, counts, percentages and versions. Dates read as words and stay in GT
America, as do filenames and words in a value slot. Nothing goes below 12sp.

The front matter's roles map onto Material's slots on the phone; the `tv-*`
roles are the TV's scale. `mono` takes the size of the text it sits in.

## Layout

- **Phone:** Files, History, Transfers, Downloads and Account, as a bar or a
  rail following the window. Wide windows make Files list-detail. Content
  keeps a 16dp gutter.
- **TV:** Home, Files, Search, History and Account as pill tabs after the
  wordmark, inside the shared `tv.overscan` inset. Lists are list-detail: the
  focused item's detail sits beside the list.
- Each tab keeps its own back stack. Back pops within the tab, then returns to
  the first tab, then leaves the app. On TV, Back focuses the tab bar before it
  changes tab. Never intercept Back at the root.
- Every target is at least 48dp.

## Elevation & Depth

Depth is tonal: surfaces step up through the `surface-container` tiers. Sheets
and dialogs dim what is under them with a scrim. Menus and snackbars keep
Material's shadow; nothing else draws one.

## Shapes

Corners follow the front matter's `rounded` scale: extra-large for sheets and
dialogs, large for cards, menus and notices, full pills for search, buttons,
tabs and the nav indicator.

## Components

- **Rows:** one line of name over one line of meta, no dividers. A name that
  doesn't fit is cut in the middle, keeping the extension. Progress never sits
  in a file row.
- **Item actions:** a bottom sheet on the phone and a side sheet on the TV,
  showing only what the item allows. The delete action comes last, in red.
- **Irreversible actions** confirm with a red button: permanent delete, Empty
  trash, Clear history, revoking a link, removing a download and Sign out.
  Move to trash never confirms; a snackbar offers Undo.
- **Switch:** neutral and compact, a deliberate step off stock Material: an
  outlined off track, an `on-surface` on track. The whole row toggles.
- **Empty and error states:** the
  [put.io empty-state voice](https://github.com/putdotio/putio-design/blob/main/system/preview/12-empty-state.html):
  a mono kaomoji, a one-line headline, one sentence, at most one action. No
  artwork. Lists load with skeleton rows in the row's slots; a full-screen
  state may show one centred spinner.
- **Consent:** the product analytics prompt gives Allow and Don't allow equal
  tonal buttons; neither is the screen's yellow action or the default.
- **Notices:** an inactive account shows a notice card, never a dialog, and it
  never takes focus.

## Do's and Don'ts

- Do generate every colour and type value from the tokens and front matter.
- Do keep one yellow action per screen or dialog.
- Do use regular-weight Phosphor glyphs for chrome; fill marks selected or
  active states and file kinds.
- Do keep saving to put.io and downloading to this device on distinct glyphs.
- Don't port web recipes (`components.css`) or iOS idioms: Android uses bottom
  sheets for actions, long-press to select, an extended FAB, and the system
  back gesture.
- Don't let colour carry a state alone; a word or glyph goes with it.

## Motion

Material motion through its standard springs: fade through between tabs,
shared axis within a tab, and the platform's own enter and exit for sheets,
dialogs and menus. Predictive back previews every pop. Reduced motion turns
transitions into fades and holds animations still.

## Accessibility

- Text follows the system font size up to a 1.3 font scale, as the iOS app
  caps it.
- Icon-only controls carry a name that says what they change; a cut name keeps
  the full name as its description.

## Android TV

The phone contract translated to a 960x540dp screen and a D-pad: the same
tokens, type, glyphs, rows, switch, empty-state voice and copy.

- **Focus:** one style. A focused row, tab, chip, card or tonal button turns
  light with dark text, and nothing yellow sits on the focus fill. Filled
  buttons keep their fill and gain a ring. Nothing scales on focus.
- **Focus memory:** returning from the player, a sheet or another tab lands on
  what had focus before.
- **Side sheets** from the right hold every secondary surface; dialogs carry
  only confirms and notices.
- **Back** closes the topmost layer first: a sheet, then seek mode, then the
  player controls, and only then the screen.
- **Phone-only:** Transfers, Downloads, Cast, selection, rename, move, share,
  exclusive links, the viewers and audio's Now Playing.
