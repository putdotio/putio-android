# Audio player

## Drive

Preconditions:

- Doctor passes. The run's root holds two audio files that sort in order,
  made with
  `ffmpeg -f lavfi -i sine=frequency=440:duration=90 -c:a aac build/verify-a-1.m4a`
  and the same for `verify-a-2.m4a`, uploaded with `fixture.sh upload`.

- **Open.** Tap `verify-a-1.m4a`. Run
  `$S/ui.sh tap $SERIAL --text "verify-a-1.m4a"` and
  `$S/ui.sh gone $SERIAL --text "Preparing audio" 60`. Now Playing shows the
  name, the folder under the title and an `Up next` chip. Capture
  `audio-open`.
- **Up next.** Tap the chip. Run `$S/ui.sh tap $SERIAL --text "Up next"`. Its
  sheet names `verify-a-2.m4a`. Press Back to close it.
- **Seek.** Tap `Forward 30 seconds`, then `Back 15 seconds`. The elapsed
  time moves by those amounts.
- **Mini player.** Press Back, then tap the `Transfers` tab. Run
  `$S/ui.sh key $SERIAL BACK`, `$S/ui.sh tap $SERIAL --text "Transfers"` and
  `$S/ui.sh wait $SERIAL --desc "Stop playback"`. The card shows the file
  name on this tab. Capture `audio-mini-player`. Tap the name to open Now
  Playing again: `$S/ui.sh tap $SERIAL --text "verify-a-1.m4a"`.
- **Next.** In the player, tap `Next file`. `verify-a-2.m4a` plays and
  `Previous file` is enabled.
- **Sleep timer.** Tap `Sleep timer`, pick 15 minutes. The chip reads
  `Sleep timer, <time> left`; pick `Off` to clear it.
- **Background.** Press Home. Run `$S/ui.sh key $SERIAL HOME`;
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
