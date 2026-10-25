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
- `downloads-offline-root` lists copies under "Offline. Showing what's on
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
  `ffmpeg -f lavfi -i testsrc2=size=640x360:rate=30:duration=20 -f lavfi -i sine=frequency=440:duration=20 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest build/verify-clip.mp4`.
- put.io has finished processing the video (its row offers Play).

- **Start.** Open the clip's actions and tap `Download`. Run
  `$V/ui.sh tap $SERIAL --desc "Actions for verify-clip.mp4"` and
  `$V/ui.sh tap $SERIAL --text "Download"`.
- **Progress.** Open Downloads. Run
  `$V/ui.sh link $SERIAL putio://downloads`, capture `downloads-progress`,
  then `$V/ui.sh wait $SERIAL --match "^On this device" 120`. The row reads
  `On this device · <size>`.
- **Files mark.** Back in the run's folder, the clip's row carries
  `On this device`.
- **Offline play.** Run
  `adb -s $SERIAL shell cmd connectivity airplane-mode enable`, open
  Downloads and tap the row's Play. The player shows the clip. Capture with
  `--allow-dark`.
- **Offline root.** Open Files. The root reads `Can't reach put.io.` and
  lists the clip under `Offline. Showing what's on this device.`
- **Back online.** Run `adb -s $SERIAL shell cmd connectivity airplane-mode disable`.
  Files loads without a tap.
- **Remove.** Open the row's `Download actions for verify-clip.mp4`, tap
  `Remove from this device`, confirm `Remove`. The row leaves;
  `putio files list --parent-id <root id>` still lists the clip.

## Gotchas

- Airplane mode acts on the whole emulator: turn it off before any other
  recipe.
- A freshly uploaded video needs put.io's processing before it plays or
  downloads; wait for Play in its actions instead of retrying Download.
- POST_NOTIFICATIONS is not granted on a fresh AVD, so no notification posts
  and Downloads shows `Download notifications are off`. Grant it with
  `adb -s $SERIAL shell pm grant io.put.putio.mobile.debug android.permission.POST_NOTIFICATIONS`
  for the notification path.
- Between runs clear only `databases/exoplayer_internal.db*`,
  `shared_prefs/io.putdotio.android.downloads.xml` and `files/downloads/`;
  never app data or the session.
- The storage-limit, queue and progress lanes in
  [Harness](../../../../docs/harness.md#downloads-and-offline-playback-proof)
  cover what this recipe does not; the synthetic ones make no API calls.
