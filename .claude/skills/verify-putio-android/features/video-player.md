# Video player

Tapping a video plays it in immersive fullscreen: a landscape video turns a
phone to landscape, controls hide during playback and come back on a tap, and
chips under the scrubber name the audio track, subtitles and speed, each
opening a side sheet. A saved position offers Resume or Start over; Back
restores the shell.

## Sub-features

- `video-open` plays a video from its row, fullscreen, with the system bars
  hidden.
- `video-controls` shows play/pause between ten-second seeks, times and the
  scrubber; a tap toggles them.
- `video-resume` asks `Resume from <time>?` with `Resume` and `Start over`.
- `video-sheets` opens the Audio, Subtitles and Speed side sheets.
- `video-speed` steps 0.5× to 3×.
- `video-next-episode` offers `Next episode` when autoplay is on.
- `video-pip` enters picture-in-picture where the device has it.
- `video-back` restores the shell's orientation and bars.

## How to get to it (user POV)

- Tap a video row in Files, Search or History.
- Tap `Play` or `Resume` in a video's actions sheet or the tablet detail.
- Tap a Continue watching card at the Files root.

## Driving it with verify-putio-android

Preconditions:

- Doctor passes. The run's root holds the 120 s landscape MP4 made with the
  ffmpeg recipe in [Downloads and offline](./downloads-and-offline.md).
- Read its saved position first:
  `PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation files.getStartFrom --args '[<id>]' --execute --output json`.

- **Open.** Tap the clip's row. Run
  `$V/ui.sh tap $SERIAL --text "verify-clip.mp4"` and
  `$V/ui.sh gone $SERIAL --text "Preparing video" 60`. The window turns
  landscape: `adb -s $SERIAL shell dumpsys window | grep -m1 mCurrentRotation`
  reads `ROTATION_90` or `ROTATION_270`. Capture `video-open --allow-dark`.
- **Controls.** Tap the video, then `Back 10 seconds` and
  `Forward 10 seconds`. Run `$V/ui.sh tap $SERIAL --desc "Forward 10 seconds"`.
  The elapsed time moves.
- **Speed.** Tap the speed chip (`Playback speed 1×`), then the step whose
  description is `Faster, 1.25×`. Run
  `$V/ui.sh tap $SERIAL --match "^Playback speed "` and
  `$V/ui.sh tap $SERIAL --match "^Faster, "`. The chip reads `1.25×`. Put it
  back to `1×` with `--match "^Slower, "`. The sheet stays open: press Back
  and `$V/ui.sh gone $SERIAL --match "^Faster, "`.
- **Subtitles.** Tap the subtitles chip; the sheet lists `Off` first. Press
  Back to close it.
- **Resume.** Play to about 40 s (well clear of the last 10 s, where no
  Resume is offered), press Back, reopen the clip. The dialog reads
  `Resume from <time>?`; `Resume` continues there. Read the position back
  with `files.getStartFrom`.
- **Back.** Press Back. The shell returns in portrait with its bars.
- **Restore.** Put the position back to the value read first:
  `putio files start-from set <id> <seconds>`, or
  `putio files start-from reset <id>` when it was unset.

## Gotchas

- Captures of playback are dark: pass `--allow-dark` to `capture.sh`.
- Headless emulators decode video in software; the first frames can take a
  few seconds. Wait for `Preparing video` to go, not a fixed time.
- Controls auto-hide: tap the video before looking for a control, and look
  again right after.
- The account's `use_start_from` decides whether Resume shows; read it with
  `account.getSettings` before judging a missing dialog.
- Positions are saved to the shared account. Restore the clip's start-from
  value before cleanup.
- Tracks, captions and fullscreen rotation are proven deeper by the
  synthetic `MobileFullscreenVideoProofTest` and
  `MobilePlaybackOptionsProofTest`.
