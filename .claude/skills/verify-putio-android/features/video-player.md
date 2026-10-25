# Video player

## Drive

Preconditions:

- Doctor passes. The run's root holds the 120 s landscape MP4 made with the
  ffmpeg recipe in [Downloads and offline](./downloads-and-offline.md).
- Read its saved position first:
  `PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation files.getStartFrom --args '[<id>]' --execute --output json`.

- **Open.** Tap the clip's row. Run
  `$S/ui.sh tap $SERIAL --text "verify-clip.mp4"` and
  `$S/ui.sh gone $SERIAL --text "Preparing video" 60`. The window turns
  landscape: `adb -s $SERIAL shell dumpsys window | grep -m1 mCurrentRotation`
  reads `ROTATION_90` or `ROTATION_270`. Capture `video-open --allow-dark`.
- **Controls.** Tap the video, then `Back 10 seconds` and
  `Forward 10 seconds`. Run `$S/ui.sh tap $SERIAL --desc "Forward 10 seconds"`.
  The elapsed time moves.
- **Speed.** Tap the speed chip (`Playback speed 1×`), then the step whose
  description is `Faster, 1.25×`. Run
  `$S/ui.sh tap $SERIAL --match "^Playback speed "` and
  `$S/ui.sh tap $SERIAL --match "^Faster, "`. The chip reads `1.25×`. Put it
  back to `1×` with `--match "^Slower, "`. The sheet stays open: press Back
  and `$S/ui.sh gone $SERIAL --match "^Faster, "`.
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
