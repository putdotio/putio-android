# Downloads and offline

A media file's Download keeps a copy on the device. Downloads lists rows in
Downloading, Needs attention and On this device sections with a summary of
space used and free; rows pause, resume, retry and remove. Finished copies
play with no network, and Files lists them at the root while offline.

## Sub-features

- `downloads-start` starts a download from the actions sheet.
- `downloads-progress` advances the row about once a second to
  `On this device · <size>`.
- `downloads-files-mark` shows the on-device glyph on the Files row.
- `downloads-play-offline` plays the copy in airplane mode.
- `downloads-offline-root` lists copies under "Offline. Showing what’s on
  this device."
- `downloads-waiting-network` reads `Waiting for network` and resumes.
- `downloads-remove` removes the local copy and keeps the put.io file.
- `downloads-notification` posts Download finished and opens its row.

## How to get to it (user POV)

- Open a video or audio file's actions and tap `Download`.
- Tap the `Downloads` tab, or open `putio://downloads`.
- Tap a download notification (`putio://downloads/<id>`).

## Driving it with verify-putio-android

Preconditions:

- Doctor passes. The run's root holds a short MP4 uploaded with
  `fixture.sh upload`, made on the devbox with
  `ffmpeg -f lavfi -i testsrc2=size=640x360:rate=30:duration=120 -f lavfi -i sine=frequency=440:duration=120 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest build/verify-clip.mp4`.
- `putio files list --parent-id <root id>` lists the clip with
  `file_type` `VIDEO`. Note its size as the app prints it (decimal units,
  one decimal below 100, such as `2.9 MB`).

- **Start.** Open the clip's actions and tap `Download`. Run
  `$V/ui.sh tap $SERIAL --desc "Actions for verify-clip.mp4"` and
  `$V/ui.sh tap $SERIAL --text "Download"`.
- **Progress.** Open Downloads. Run
  `$V/ui.sh link $SERIAL putio://downloads`, capture `downloads-progress`,
  then wait for the progress line to go:
  `$V/ui.sh gone $SERIAL --match "^Downloading · " 120`. Open the row's
  actions: `$V/ui.sh tap $SERIAL --desc "Download actions for verify-clip.mp4"`.
  The sheet reads `On this device · <size>`:
  `$V/ui.sh wait $SERIAL --match "^On this device · "`. Capture
  `downloads-complete`, then press Back.
- **Files mark.** Back in the run's folder, the clip's row carries
  `On this device`.
- **Offline play.** Run
  `adb -s $SERIAL shell cmd connectivity airplane-mode enable`, open
  Downloads and tap the row's Play. The player shows the clip. Capture with
  `--allow-dark`.
- **Offline root.** Open Files. The root reads `Can’t reach put.io.` and
  lists the clip under `Offline. Showing what’s on this device.`
- **Back online.** Run `adb -s $SERIAL shell cmd connectivity airplane-mode disable`.
  Files loads without a tap.
- **Remove.** Changes only the device, so no ledger intent. Open the row's `Download actions for verify-clip.mp4`, tap
  `Remove from this device`, confirm `Remove`. The row leaves;
  `putio files list --parent-id <root id>` still lists the clip.

## Gotchas

- Airplane mode acts on the whole emulator: turn it off before any other
  recipe.
- A snapshot run may restore downloads from earlier runs. Check this clip's
  state in its own actions sheet; a finished row shows only its size and
  date, and `On this device` alone is also the section heading.
- Strings use typographic apostrophes (`’`), and selectors match them
  exactly.
- POST_NOTIFICATIONS is not granted on a fresh AVD, so no notification posts
  and Downloads shows `Download notifications are off`. Grant it with
  `adb -s $SERIAL shell pm grant io.put.putio.mobile.debug android.permission.POST_NOTIFICATIONS`
  for the notification path.
- Between runs clear only `databases/exoplayer_internal.db*`,
  `shared_prefs/io.putdotio.android.downloads.xml` and `files/downloads/`;
  never app data or the session.
- `MobileDownloadsProgressProofTest` and `MobileDownloadsQueueProofTest`
  cover progress, the queue limit and low storage over local files; they are
  synthetic. The queue lane forces low storage on the whole emulator, so run
  it only on this run's own instance.
