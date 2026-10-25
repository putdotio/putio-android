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
  `file_type` `VIDEO`.
- Notifications are allowed, so the first download's permission prompt does
  not cover the screen:
  `adb -s $SERIAL shell pm grant io.put.putio.mobile.debug android.permission.POST_NOTIFICATIONS`.

- **Start.** Open the clip's actions and tap `Download`. Run
  `$V/ui.sh tap $SERIAL --desc "Actions for verify-clip.mp4"` and
  `$V/ui.sh tap $SERIAL --text "Download"`.
- **Progress.** Open Downloads. Run `$V/ui.sh link $SERIAL putio://downloads`
  and capture `downloads-progress`: the clip's row shows its progress under
  `Downloading`.
- **Done.** Back in the run's folder, the clip is the folder's only video, so
  its row's `On this device` glyph proves this download finished. Run
  `$V/ui.sh link $SERIAL "https://app.put.io/files/<root id>"` and
  `$V/ui.sh wait $SERIAL --desc "On this device" 180`, then capture
  `downloads-complete`. In Downloads the row now sits under
  `On this device` and shows its size and date.
- **Offline play.** Run
  `adb -s $SERIAL shell cmd connectivity airplane-mode enable`, open
  Downloads, open `Download actions for verify-clip.mp4` and tap `Play` in
  that sheet (the row's own Play is only an action label). The player shows
  the clip. Capture with `--allow-dark`.
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
- A snapshot run may restore downloads from earlier runs, and
  `On this device` is also the Downloads section heading. Prove completion in
  the run's own folder, where the clip is the only row that can carry the
  glyph.
- Strings use typographic apostrophes (`’`), and selectors match them
  exactly.
- Without POST_NOTIFICATIONS the first download opens Android's permission
  prompt over the app, and later Downloads shows
  `Download notifications are off`. The precondition grants it.
- Between runs clear only `databases/exoplayer_internal.db*`,
  `shared_prefs/io.putdotio.android.downloads.xml` and `files/downloads/`;
  never app data or the session.
- `MobileDownloadsProgressProofTest` and `MobileDownloadsQueueProofTest`
  cover progress, the queue limit and low storage over local files; they are
  synthetic. The queue lane forces low storage on the whole emulator, so run
  it only on this run's own instance.
