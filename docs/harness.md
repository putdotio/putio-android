# Harness

Emulators, launch proof, device lanes, evidence, and live API proof. The
[Agent Guide](../AGENTS.md) owns setup, build, verify, and the proof map;
[Behaviour](./behavior.md) owns the product rules these lanes exercise.

## Emulators

Bootstrap creates the `putio-phone` (API 37) and `putio-tv` (API 36) AVDs, plus
the opt-in `putio-google-tv` for the Google TV launcher and Play surfaces.
[scripts/lib.sh](../scripts/lib.sh) owns their device profiles and images.

```bash
./scripts/emulator.sh boot phone --headless        # prints the serial; reuses a running one
./scripts/emulator.sh status
./scripts/emulator.sh stop phone                   # or stop emulator-5554
./scripts/emulator.sh create phone --name <name>   # an extra AVD you own; boot/stop/delete take --name too
./scripts/bootstrap.sh --google-tv                 # adds the Google TV image and AVD
./scripts/emulator.sh boot google-tv --headless
```

- A fresh `putio-google-tv` boots into Google TV setup; finish or cancel it
  before launcher proof. `scripts/prove.sh tv` targets `putio-tv`; install on
  Google TV with `adb -s <serial> install` for manual proof.
- A flow stops only the serial it booted, on success, failure, SIGINT and
  SIGTERM, and deletes only AVDs it created with `--ephemeral`.
  `scripts/test-lifecycle.sh` pins this; failed runs keep logs under
  `.evidence/logs/lifecycle.*`.
- Every phone boot, reuse included, requires API 37 and the bundled Chrome's
  AndroidX Auth Tab service, or fails before install. OAuth needs that
  transport.
- Bootstrap never replaces a mismatched AVD. It fails with the command to run;
  stop and delete that exact profile yourself, then rerun. AVDs the harness
  does not own are left alone, even unloadable ones.
- `--headless` renders with `swiftshader_indirect`: host-GPU headless rendering
  sometimes composites app windows black and fails the pixel check.
- Cold headless boots often ANR the emulator's own System UI, so boots hide
  error dialogs. App crashes and ANRs still fail instrumented proof.
- macOS needs Hypervisor.framework; Linux needs KVM (`emulator -accel-check`).
  The scripts scan console ports 5554–5584; check `status` before assuming one
  is free.

## Launch proof

```bash
./scripts/prove.sh mobile            # build → boot → install → launch → verify → screenshot → teardown
./scripts/prove.sh tv --record       # also captures a screen recording
```

The [prove.sh header](../scripts/prove.sh) owns flags, exit codes and stdout
markers. Verification runs only `LaunchSmokeTest` (`:<app>:verifyLaunchProof`):
RESUMED state, a 3 s stability window, and real composited pixels. A missing or
skipped XML result fails it even when instrumentation exits 0. One retry covers
the cold-boot black-window flake. `--record` force-stops and relaunches the app
under capture, so the clip shows a real cold launch.

The connected suite uninstalls the tested app and its session. Authenticate
after launch proof, and never run `connectedAndroidTest` or `prove.sh` between
authenticated steps.

`domain/auth` and `domain/transfers` keep their device tests in their own test
APKs, so `ANDROID_SERIAL=<serial> ./gradlew :domain:auth:connectedDebugAndroidTest`
is safe beside an authenticated app.

## Device lanes

Opt-in lanes never use `connectedAndroidTest` or `prove.sh`, so an installed
session survives. Each test class reads its arguments and parses its fixture;
the class is the reference for both.

1. Boot the emulator (API 37 phone or `putio-tv`), then install the app and its
   instrumentation APK. For TV, replace `mobile` with `tv` in the task and both
   paths:

   ```bash
   ./gradlew :mobile:assembleProductionDebug :mobile:assembleProductionDebugAndroidTest
   adb -s emulator-5554 install -r mobile/build/outputs/apk/production/debug/mobile-production-debug.apk
   adb -s emulator-5554 install -r mobile/build/outputs/apk/androidTest/production/debug/mobile-production-debug-androidTest.apk
   ```

2. Push media fixtures to the app's external files directory
   (`/sdcard/Android/data/<app id>/files/`). On a fresh install that directory
   exists only after the app creates it: run the lane once, let it fail on the
   missing fixture, then push. Never create it with `adb shell mkdir`; the app
   cannot read a shell-owned directory. Lanes that copy from `/data/local/tmp`
   take their fixture there instead.

3. Run the class or `Class#method` with `<prefix>.enabled=true`, a fresh
   `<prefix>.runId`, and the lane's fixture arguments, under your own timeout.
   Require `OK (n tests)`.

   ```bash
   adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.MobileTransferRetryProofTest \
     -e putio.transfers.enabled true -e putio.transfers.runId "$(uuidgen | tr A-Z a-z)" \
     io.put.putio.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner
   ```

   | App    | App id                      | Runner package                   | Test package                 |
   | ------ | --------------------------- | -------------------------------- | ---------------------------- |
   | mobile | `io.put.putio.mobile.debug` | `io.put.putio.mobile.debug.test` | `io.putdotio.android[.*]`    |
   | tv     | `io.put.putio.debug`        | `io.put.putio.debug.test`        | `io.putdotio.android.tv[.*]` |

4. Screenshots land in `<lane>-proof-<UUID>/` under the app's external files
   directory; pull them into `.evidence/` and inspect them. To record, start
   `scripts/evidence.sh record` (with `--allow-dark` for playback) just before
   the instrumentation.

5. Remove only the fixtures and run directories you created, restore every
   device setting the lane changed, and stop only an emulator you booted.

Report a lane that makes no API calls as synthetic proof, not live proof. A
lane that drives Home, the launcher or system settings acts on the whole
device; run it on an emulator you booted with no other proof running.

Fixture recipes:

```bash
# Landscape MP4 with one audio track; change size or duration as a lane asks.
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=90 -f lavfi -i sine=frequency=440:duration=90 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest fixture.mp4
# SubRip captions, one cue every 3 s; mux with -c:s mov_text for embedded captions.
python3 -c 'for i in range(30): t = lambda v: f"00:{v // 60:02d}:{v % 60:02d},000"; print(f"{i + 1}\n{t(i * 3)} --> {t(i * 3 + 3)}\nProof caption {i + 1}\n")' > captions.srt
```

Captures of a fixture cut from the Blender Foundation's
[Sintel trailer](https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4)
need the attribution “© copyright Blender Foundation |
durian.blender.org” under its [CC BY 3.0 terms](https://durian.blender.org/sharing/).

### Capture and publish

Captures land in the ignored `.evidence/` as `<UTC timestamp>-<label>.png|mp4`.

```bash
./scripts/evidence.sh screenshot --label files-screen
./scripts/evidence.sh record --seconds 15 --label playback --allow-dark
./scripts/evidence.sh validate-recording --input <file>
```

Corrupt output is quarantined as `*.corrupt`, near-black captures as
`*.black.*` unless `--allow-dark`, and fully static recordings as `*.mp4.idle`.
Never upload a quarantined file. Inspect each capture, then attach it with
`gh pr create --attach .evidence/<file>.png` or
`gh pr comment <n> --attach .evidence/<file>.mp4` (gh 2.99+).

## Live API proof (putio CLI)

Fixtures and live state checks use the `putio` CLI with the shared `devs-auto`
profile. The CLI keeps its tokens in `~/.config/putio/`.

```bash
putio auth login --profile devs-auto   # device-code flow; approve at the printed URL
putio auth status --profile devs-auto  # verify before relying on it
PUTIO_CLI_PROFILE=devs-auto putio sdk call --operation account.getSettings --execute --output json
```

Every live lane follows the same fixture rules:

- Create a uniquely named root container per run. Before each write, record
  every ID, name, parent, kind and mutation outcome in an ignored ledger.
  Remove only those IDs.
- Wait until instrumentation is idle before cleanup. Delete leaves before
  parents, check identity and contents first, and confirm an exact
  `GET /files/<id>` 404. If instrumentation does not go idle, stop only an
  emulator you booted; on a reused emulator leave the fixtures.
- An unknown mutation outcome keeps the ledger for read-only reconciliation;
  never repeat the mutation. Never empty Trash or use ID zero. A trashed item
  needs its own cleanup: removing its parent leaves the Trash entry.
- `POST /files/delete?skip_trash=false` reads `skip_trash` from the query only;
  a form value is ignored, and the file goes permanently when Trash is off.
- Read shared account settings before a proof and restore what you change.

Debug builds read `putioMobileOAuthClientIdDebugOverride` from
`local.properties` to replace the mobile OAuth client id; release builds ignore
it. Existing sessions belong to the old client, so `adb shell pm clear` the
debug app after changing it, and name the client in the evidence.

## Mobile lanes

Prefixes are `putio.<prefix>`; screenshots go to `<dir>-proof-<UUID>/`.

| Class                                                               | Prefix               | Kind      | Behaviour                                                                |
| ------------------------------------------------------------------- | -------------------- | --------- | ------------------------------------------------------------------------ |
| `MobileAccessibilityProofTest`, `MobileShellAccessibilityProofTest` | `accessibility`      | synthetic | [Accessibility](#accessibility)                                          |
| `MobileKeyboardNavigationProofTest`                                 | `accessibility`      | synthetic | [Keyboard and shortcuts](./behavior.md#keyboard-and-shortcuts)           |
| `MobileTalkBack*ProofTest`                                          | `accessibility`      | synthetic | [TalkBack](#talkback)                                                    |
| `MobilePlaybackOptionsProofTest`                                    | `playback.options`   | synthetic | [Mobile video player](./behavior.md#mobile-video-player)                 |
| `MobileFullscreenVideoProofTest`                                    | `video.fullscreen`   | synthetic | [Mobile video player](./behavior.md#mobile-video-player)                 |
| `MobilePictureInPictureProofTest`                                   | `pip`                | synthetic | [Picture-in-picture](./behavior.md#picture-in-picture)                   |
| `MobileLiveAudioAttachmentProofTest`                                | `audio.attach`       | synthetic | [Resume](./behavior.md#resume-and-position-reporting)                    |
| `AuthenticatedFilesRenameTest` via `proveAuthenticatedRename`       | Gradle `-P` flags    | live      | [Item actions](./behavior.md#item-actions)                               |
| `AuthenticatedFilesDeleteTest`                                      | `delete`             | live      | [Files delete and paging](./behavior.md#files-delete-and-paging)         |
| `AuthenticatedFilesMoveTest`, `FilesMoveNavigationUiProofTest`      | `move`, `move.ui`    | live      | [Move and copy target folder](./behavior.md#move-and-copy-target-folder) |
| `AuthenticatedTrashRestoreTest`, `TrashRestoreUiProofTest`          | `trash.restore[.ui]` | live      | [Trash](./behavior.md#trash)                                             |
| `MobileMoveTargetProofTest`                                         | `movetarget`         | synthetic | [Move and copy target folder](./behavior.md#move-and-copy-target-folder) |
| `MobilePublicLinksProofTest`                                        | `publiclinks`        | synthetic | [Public links](./behavior.md#public-links)                               |
| `MobileTransferRetryProofTest`                                      | `transfers`          | synthetic | [Transfer failures and retry](./behavior.md#transfer-failures-and-retry) |
| `MobileShareSessionProofTest`                                       | `share.session`      | synthetic | [Share-out](./behavior.md#share-out)                                     |
| `MobileDocumentsPickerProofTest`                                    | `documents`          | synthetic | [Documents provider](./behavior.md#documents-provider)                   |
| `MobileDownloadsProgressProofTest`, `MobileDownloadsQueueProofTest` | `downloads.*`        | synthetic | [Downloads](./behavior.md#downloads-and-offline-playback)                |
| `MobileHomeScreenProofTest`                                         | `homescreen`         | synthetic | [Transfers widget](./behavior.md#transfers-widget)                       |
| `MobileTabletPowerProofTest`                                        | `tablet`             | synthetic | [Tablet](#tablet)                                                        |

Lane notes:

- **Authenticated lanes** need the app signed in as `devs-auto` and a base64
  JSON fixture; the matching `*ProofFixture.kt` or `TrashRestoreFixture.kt`
  parser defines its keys and rejects anything ambiguous. Run its
  `*FixtureTest` first. Trash/Delete never changes the account's Trash
  setting, so a run covers only the mode its fixture records.
- **Rename** runs through Gradle, which installs without uninstalling,
  validates the CLI account and fixture, and prints
  `PROOF PASS authenticated-rename` only on success. Interrupts can drop
  `CLEANUP FAIL` from the client output; read the daemon log under
  `~/.gradle/daemon/<version>/`.

  ```bash
  ./gradlew --no-daemon :mobile:proveAuthenticatedRename -PputioRenameEnabled=true \
    -PputioRenameSerial=emulator-5554 -PputioRenameFixture=/absolute/path/to/fixture.json
  ```

- **Trash bulk actions:** Restore all and Empty Trash act on the whole shared
  account, so only synthetic proof covers them. Live proof covers Delete
  permanently on an owned, trashed fixture.
- **Resume, live:** set a position on owned audio and video with `devs-auto`,
  exercise Resume, Start over, pause, Back and background audio, then read
  positions back through the CLI. Restore the initial positions. Local
  test-player proof does not prove live write-back.
- **Playback fixtures** are 180 s media with two labelled audio tracks and
  embedded `eng` mov_text captions; portrait lanes use `size=720x1280`.
- **Picture-in-picture** force-stops the app and drives Home and the system
  menu, so it acts on the whole device.
- **Share-in:** a magnet link needs no account:
  `adb shell am start -a android.intent.action.VIEW -d 'magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Harbor%20film' -p io.put.putio.mobile.debug`.
  Never use real credentials in share artifacts.
- **Share-out:** in `dumpsys activity activities`, the chooser's `clip=` must
  hold only a `content://` URI and no `oauth_token`.
- **Downloads, live:** `databases/exoplayer_internal.db` must hold no
  `oauth_token`, and every `ExoPlayerCacheIndex*` key needs a `u<userId>|`
  prefix. Force low storage with
  `adb shell cmd devicestoragemonitor force-low -f` and undo it with `reset`.
  Between runs clear only `databases/exoplayer_internal.db*`,
  `shared_prefs/io.putdotio.android.downloads.xml` and `files/downloads/`.
  The queue lane forces low storage and grants POST_NOTIFICATIONS; run it on an
  emulator you booted.
- **Shortcuts and widget:** `adb shell dumpsys shortcut` must list `search`,
  `add-transfer` and `downloads` with `cmp=<package>/io.putdotio.android.MainActivity`.
  On API 37 `input swipe` does not open the launcher menu; use
  `input motionevent DOWN`, a 1.5 s sleep, then `UP`. `am force-stop` cancels
  the widget's pending intents until its next update.

### Accessibility

Run on the API 37 phone with `font_scale` `2.0` and the three global animation
scales at `0`; record and restore their prior values, absent keys included.
Use normal-size captures as the primary UI evidence. Compose semantics do not
prove TalkBack speech.

With zero animation scales, every sheet, menu, dialog, the player and pull to
refresh must reach its final state on the first frame. The app defines no
animation of its own, so a non-zero scale here is a regression.

### TalkBack

Drive these lanes by hand while TalkBack runs, with Developer settings →
Display speech output and VERBOSE logging on. Capture the walkthrough and the
filtered `SpeechControllerImpl` log; state assertions alone do not prove speech.
Each lane writes its current stage to a `talkback-*stage.txt` file and requires
`finished`.

On API 37, `input swipe` and `UiAutomation` bypass TalkBack's gesture input,
and a second accessibility service breaks its player traversal. Send one gesture
at a time through the helper, with the screenshot's physical size and rotation:

```bash
python3 -B scripts/talkback-input.py --adb "$ADB" --serial emulator-5554 \
  --action swipe-right --width 1080 --height 2400 --rotation 0   # swipe-left, swipe-right, double-tap
```

### Tablet

The proof needs a phone AVD you created with a hardware keyboard and a
tablet-size display. Restore with `wm size reset` and `wm density reset`:

```bash
sed -i '' 's/^hw.keyboard=no$/hw.keyboard=yes/' ~/.android/avd/<name>.avd/config.ini   # before booting
adb -s <serial> shell wm size 2560x1600 && adb -s <serial> shell wm density 320          # 1280x800 dp
```

That resized phone proves keyboard, drag and window behaviour only. Capture the
tablet layout on an AVD created with `-d pixel_tablet`.

## TV lanes

TV classes live in `io.putdotio.android.tv` (the player's in `.tv.player`);
arguments use `putio.tv.<lane>`. All mount fake repositories and make no API
calls, except `TvGlobalSearchProofTest`.

| Class                      | Prefix          | Behaviour                                                                                       |
| -------------------------- | --------------- | ----------------------------------------------------------------------------------------------- |
| `TvPlayerProofTest`        | `player`        | [TV playback](./behavior.md#tv-playback)                                                        |
| `TvAutoplayProofTest`      | `autoplay`      | [TV playback](./behavior.md#tv-playback)                                                        |
| `TvExternalOpenProofTest`  | `open`          | [History opens](./behavior.md#timestamps-and-history-opens), [TV Back](./behavior.md#tv-back)   |
| `TvSearchHistoryProofTest` | `searchHistory` | [Recent searches](./behavior.md#recent-searches)                                                |
| `TvSafeAreaProofTest`      | `safearea`      | [TV overscan safe area](./behavior.md#tv-overscan-safe-area)                                    |
| `TvCopyProofTest`          | `copy`          | [History events](./behavior.md#history-events), [Storage quota](./behavior.md#storage-quota)    |
| `TvGlobalSearchProofTest`  | `globalSearch`  | [TV system search](./behavior.md#tv-system-search); live, takes `query` and `fileId`, no opt-in |

- `TvAutoplayProofTest` and `TvExternalOpenProofTest` take an MP4 from
  `/data/local/tmp` and copy it into the app's directory; remove both copies
  afterwards.
- `putio-tv` does not apply 3840x2160, so the JVM test covers 4K. Elsewhere,
  check that `wm size` reports the requested `Override size` before calling a
  run 4K.

### TV sign-in

Approve the TV code with the shared identity instead of a browser:

```bash
./scripts/emulator.sh boot tv --headless
adb -s emulator-5554 install -r tv/build/outputs/apk/production/debug/tv-production-debug.apk
adb -s emulator-5554 shell am start -n io.put.putio.debug/io.putdotio.android.MainActivity
code=$(adb -s emulator-5554 exec-out uiautomator dump /dev/tty | grep -oE 'Activation code [A-Z0-9]+' | awk '{print $3}')
PUTIO_CLI_PROFILE=devs-auto putio auth approve "$code"
```

The emulator has no Fire TV feature flag, so it links as the Android TV client;
Fire TV needs a physical device
([#51](https://github.com/putdotio/putio-android/issues/51)).

### TV platform integration

- **Watch Next:** the shell user cannot read another package's Watch Next
  rows, so check the launcher (`uiautomator dump`), not `content query`.
  `putio files start-from set <id> <s>` places a position for a repeat.
- **System search:** the provider refuses the shell, so
  `TvGlobalSearchProofTest` queries it from the app's process. The `putio-tv`
  search UI is Google Assistant, which answers nothing without a Google
  account; a result row in the system UI stays unproven.

### TV player fixture

`TvPlayerProofTest` needs HLS with two audio renditions and a WebVTT subtitle
rendition, a German-default subtitle variant, and a 90 s AAC file. An `.m3u8`
plays as HLS, anything else as the original file.

```bash
python3 -c 'for i in range(30): t = lambda v: f"00:{v // 60:02d}:{v % 60:02d},000"; print(f"{i + 1}\n{t(i * 3)} --> {t(i * 3 + 3)}\nTV proof subtitle {i + 1}\n")' > captions.srt
rm -rf hls && ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=90 -f lavfi -i sine=frequency=440:duration=90 \
  -f lavfi -i sine=frequency=880:duration=90 -i captions.srt -map 0:v -map 1:a -map 2:a -map 3:s \
  -c:v libx264 -pix_fmt yuv420p -g 60 -c:a aac -c:s webvtt -f hls -hls_time 4 -hls_playlist_type vod \
  -master_pl_name index.m3u8 -var_stream_map "v:0,s:0,agroup:aud,sgroup:subs,name:video \
  a:0,agroup:aud,language:en,name:English,default:yes a:1,agroup:aud,language:de,name:Deutsch" \
  -hls_segment_filename 'hls/%v/seg%03d.ts' hls/%v/media.m3u8
sed -i.bak -e 's/NAME="audio_1"/NAME="English"/' -e 's/NAME="audio_2"/NAME="Deutsch"/' \
  -e 's/NAME="subtitle_0",DEFAULT=NO/NAME="English",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="en"/' hls/index.m3u8
rm hls/index.m3u8.bak
mkdir hls/subs-de && cp hls/video/media_vtt.m3u8 hls/subs-de/
for f in hls/video/media*.vtt; do sed 's/TV proof subtitle/Account default subtitle/' "$f" > "hls/subs-de/${f##*/}"; done
sed -e 's|^#EXT-X-MEDIA:TYPE=SUBTITLES,.*|#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Deutsch",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="de",URI="subs-de/media_vtt.m3u8"\
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=NO,AUTOSELECT=NO,LANGUAGE="en",URI="video/media_vtt.m3u8"|' \
  hls/index.m3u8 > hls/default-subtitle.m3u8
ffmpeg -f lavfi -i sine=frequency=440:duration=90 -c:a aac hls/audio.m4a
adb -s emulator-5554 push hls/. /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/
adb -s emulator-5554 shell am instrument -w -r -e class io.putdotio.android.tv.player.TvPlayerProofTest \
  -e putio.tv.player.enabled true -e putio.tv.player.runId "$(uuidgen)" \
  -e putio.tv.player.fixture /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/index.m3u8 \
  -e putio.tv.player.audioFixture /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/audio.m4a \
  -e putio.tv.player.defaultSubtitleFixture /sdcard/Android/data/io.put.putio.debug/files/tv-player-fixture/default-subtitle.m3u8 \
  io.put.putio.debug.test/androidx.test.runner.AndroidJUnitRunner
```
