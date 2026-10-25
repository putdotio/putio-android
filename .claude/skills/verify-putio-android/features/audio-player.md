# Audio player

Audio plays on a background service with a media notification and lock-screen
controls. While it plays, a mini player card sits above the navigation bar on
every tab; tapping it opens Now Playing with 15-second back and 30-second
forward seeks, previous and next files in the folder, Up next, speed and a
sleep timer.

## Sub-features

- `audio-open` plays an audio file from its row and shows Now Playing.
- `audio-mini-player` keeps the card on every tab with Pause or Play and
  `Stop playback`.
- `audio-seek` seeks back 15 s and forward 30 s.
- `audio-skip` plays the previous or next audio file by name order and shows
  `Up next`.
- `audio-speed` opens the speed panel in a bottom sheet.
- `audio-sleep` sets 15, 30, 45 minutes or 1 hour, and Off.
- `audio-background` keeps playing with the app in the background.

## How to get to it (user POV)

- Tap an audio row in Files, Search or History.
- Tap the mini player card (`Open player`).
- Use the media notification or headset controls.

## Driving it with verify-putio-android

Preconditions:

- Doctor passes. The run's root holds two audio files that sort in order,
  made on the devbox with
  `ffmpeg -f lavfi -i sine=frequency=440:duration=90 -c:a aac build/verify-a-1.m4a`
  and the same for `verify-a-2.m4a`, uploaded with `fixture.sh upload`.

- **Open.** Tap `verify-a-1.m4a`. Run
  `$V/ui.sh tap $SERIAL --text "verify-a-1.m4a"` and
  `$V/ui.sh gone $SERIAL --text "Preparing audio" 60`. Now Playing shows the
  name, the folder under the title and `Up next` naming `verify-a-2.m4a`.
  Capture `audio-open`.
- **Seek.** Tap `Forward 30 seconds`, then `Back 15 seconds`. The elapsed
  time moves by those amounts.
- **Mini player.** Press Back, then tap the `Transfers` tab. Run
  `$V/ui.sh key $SERIAL BACK`, `$V/ui.sh tap $SERIAL --text "Transfers"` and
  `$V/ui.sh wait $SERIAL --desc "Open player"`. The card shows on this tab.
  Capture `audio-mini-player`.
- **Next.** Open the player and tap `Next file`. `verify-a-2.m4a` plays and
  `Previous file` is enabled.
- **Sleep timer.** Tap `Sleep timer`, pick 15 minutes. The chip reads
  `Sleep timer, <time> left`; pick `Off` to clear it.
- **Background.** Press Home. Run `$V/ui.sh key $SERIAL HOME`;
  `adb -s $SERIAL shell dumpsys media_session` lists the app's session as
  playing.
- **Stop.** Reopen the app and tap `Stop playback` on the card. The card
  leaves.

## Gotchas

- The headless emulator plays with `-no-audio`; prove playback by the moving
  elapsed time and the media session state, not by sound.
- Now Playing replaces itself when another file plays, so Back returns to the
  screen below it, not the previous file.
- Positions report to the shared account like video; restore them before
  cleanup.
- Skip buttons and Up next stay off when the folder walk fails; report that
  as a failure with the folder state, not a pass.
