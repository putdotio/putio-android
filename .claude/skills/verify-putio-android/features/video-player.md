# Video player

## Drive

Preconditions:

- Doctor passes. The run's root holds the 120 s landscape MP4 made with the
  ffmpeg recipe in [Downloads and offline](./downloads-and-offline.md).
- Read its saved position first:
  `PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation files.getStartFrom --args '[<id>]' --execute --output json`.

- **Open.** Tap the clip's row. Run
  `$S/ui.sh tap $SERIAL --text "verify-clip.mp4"` and
  `$S/ui.sh gone $SERIAL --text "Preparing video" 60`. A fresh upload can
  show `Converting to MP4` first: put.io may convert the ffmpeg clip before
  it streams. Wait with `$S/ui.sh gone $SERIAL --text "Converting to MP4" 300`.
  A saved position opens `Resume from <time>?` first; tap `Start over`. The
  window turns landscape:
  `adb -s $SERIAL shell dumpsys window | grep -m1 mCurrentRotation` reads
  `ROTATION_90` or `ROTATION_270`. On a fresh AVD tap `Got it` on Android's
  `Viewing full screen` prompt. Capture `video-open --allow-dark`.
- **Controls.** Controls hide faster than `ui.sh` reads the tree, so pause
  first: tap the video
  (`adb -s $SERIAL shell input tap <x> <y>` at its centre) and run
  `$S/ui.sh tap $SERIAL --desc "Pause"`; paused, they stay. Then tap
  `Forward 10 seconds` and `Back 10 seconds`. The elapsed time moves by 10 s
  each way. Tap `Play` before **Resume**.
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

## Synthetic lanes

`MobileFullscreenVideoProofTest`, `MobilePlaybackOptionsProofTest` and
`MobilePictureInPictureProofTest` play local fixtures. The landscape test seeks
to 45 s and waits for `Rehearsal caption proof` cues, so clips run 90 s:

```bash
mkdir -p build/proof && cd build/proof
awk 'BEGIN { for (s = 0; s < 90; s += 2) printf "%d\n00:%02d:%02d,000 --> 00:%02d:%02d,900\nRehearsal caption proof\n\n", s/2+1, s/60, s%60, s/60, s%60+1 }' > captions.srt
tone() { echo "-f lavfi -i sine=frequency=$1:duration=90"; }
ffmpeg -y -f lavfi -i testsrc2=size=1280x720:rate=30:duration=90 $(tone 440) $(tone 660) -i captions.srt \
  -map 0 -map 1 -map 2 -map 3 -c:v libx264 -pix_fmt yuv420p -c:a aac -c:s srt \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=spa -metadata:s:s:0 language=eng proof-video.mkv
ffmpeg -y -f lavfi -i testsrc2=size=720x1280:rate=30:duration=90 $(tone 440) \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest proof-portrait.mp4
ffmpeg -y $(tone 440) $(tone 660) -map 0 -map 1 -c:a aac \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=spa proof-audio.mka
```

Push them as the skill's proof-lane steps say, then pass:

| Class                             | Arguments                                                                              |
| --------------------------------- | -------------------------------------------------------------------------------------- |
| `MobileFullscreenVideoProofTest`  | `putio.video.fullscreen.fixture` (mkv), `putio.video.fullscreen.portraitFixture` (mp4) |
| `MobilePlaybackOptionsProofTest`  | `putio.playback.options.video` (mkv), `putio.playback.options.audio` (mka)             |
| `MobilePictureInPictureProofTest` | `putio.pip.fixture` (mkv)                                                              |

Without `portraitFixture` the portrait test skips; report it as skipped.

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
