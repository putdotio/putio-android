# Tablet list-detail and viewers

In a window 840 dp or wider, Files shows the folder's list on the left and
the selected file's detail on the right: a preview, the full name, Play or
Resume or Open, Download, Share, facts, and an overflow to the actions sheet.
Images, PDFs and text open in an in-app viewer on a black frame, on phones
from a row and on tablets from the detail's Open.

## Sub-features

- `tablet-detail-empty` shows `No file selected.` beside the list.
- `tablet-select` selects a file into the detail; a folder still opens in
  the list.
- `tablet-facts` lists `Size`, `Added`, `Length`, `Subtitles` and `Folder`.
- `viewer-image` shows an image and swipes to the next of the same kind with
  `<n> of <total>`.
- `viewer-pdf` renders one page at a time with `Page <n> of <total>`.
- `viewer-text` shows text in mono up to 250 kB.
- `viewer-other` hands other types to `Open with another app`.

## How to get to it (user POV)

- Use a tablet or a window at least 840 dp wide; tap a file in Files.
- On a phone, tap an image, PDF or text row.
- Tap `Open` in the tablet detail.

## Driving it with verify-putio-android

Preconditions:

- Doctor passes. The run's root holds two PNGs and a text file, made on the
  devbox with
  `ffmpeg -f lavfi -i testsrc2=size=800x600 -frames:v 1 build/verify-1.png`
  (and `verify-2.png`) and uploaded with `fixture.sh upload`.
- For tablet steps, widen this run's own emulator:
  `adb -s $SERIAL shell wm size 2560x1600` and
  `adb -s $SERIAL shell wm density 320` (1280x800 dp).

- **Phone viewer.** At the default size, tap `verify-1.png`. The viewer shows
  the image and `1 of 2`. Swipe left; it reads `2 of 2`. Capture
  `viewer-image`. Press Back.
- **Text viewer.** Tap the text file. Its content shows in mono.
- **Widen.** Run the two `wm` commands, then
  `$V/ui.sh link $SERIAL putio://files` and open the run's folder. The
  detail reads `No file selected.` and `Tap a file to see it here.`
  Capture `tablet-detail-empty`.
- **Select.** Tap `verify-1.png`. The detail shows its preview, name, `Open`,
  `Size`, `Added` and `Folder`. Capture `tablet-select`.
- **Open.** Tap `Open`. The image viewer opens.
- **Restore.** Run `adb -s $SERIAL shell wm size reset` and
  `adb -s $SERIAL shell wm density reset`.

## Gotchas

- `wm size` and `wm density` change the whole emulator. Reset both before the
  next recipe, even after a failure.
- A resized phone proves layout, not the true tablet scale or status bar. For
  captures at true scale, create the AVD with `-d pixel_tablet`.
- Viewer bytes stream through the app's documents provider; no URL holds a
  token, and the logcat scan in `capture.sh` checks that.
- A PDF opens with another app from 200 MB; keep PDF fixtures small.
- Keyboard and drag-and-drop on tablets are covered synthetically by
  `MobileTabletPowerProofTest`.
