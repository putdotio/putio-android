# Behaviour

Product rules the Android app implements. Each section names the tests that
pin the rule; [Harness](./harness.md) owns the device lanes and live proof.

## Stored session recovery

The access token is AES-GCM ciphertext under a Keystore key. A record that can
never decrypt (missing key, failed tag, malformed ciphertext or plaintext) is
wiped with its key and read as no session: mobile lands on the normal sign-in
screen and TV on a fresh code. Any other storage failure may be transient and
keeps the record. Mobile then shows Secure storage is unavailable with Reset and
sign in, which clears the token and pending attempt as far as it can and starts
OAuth; the new token overwrites anything left, and a store that still fails
returns to the same screen. A failed clear during logout or session expiry
lands there too, so no storage state blocks sign-in.

Tests: `KeystoreAuthTokenStoreTest`, `MobileAuthStorageRecoveryTest`,
`MobileAuthControllerTest`, `MobileAuthControllerSessionTest`, `MobileWelcomeScreenTest`.

## Sign-out revocation

Sign out is local and immediate on both surfaces; put.io revokes the token in
the background. Before the session is cleared, the token is written to its own
Keystore-encrypted record, separate from the session record, and revoked with a
dedicated client that never touches the live session. A failed revocation
retries after 15 s, 1 min, 5 min and 15 min, then waits for the next app start
or sign-out. Success or a 401/403 clears the record; a storage failure keeps
retrying in memory for the life of the process. One record: a newer sign-out
replaces an older unconfirmed token. put.io can hand the same token back on the
next sign-in, so persisting a token equal to the pending one cancels its
revocation. That sign-in waits for a revocation request already in flight; if
put.io revoked the token, mobile signs out as expired and TV starts a new link.
A record loaded at app start that matches the stored session is dropped instead
of revoked, and an unreadable record or session defers the attempt. Sign-out
revokes the token the live session holds, so an unreadable session record still
gets revoked. Nothing in the path logs the token.

Tests: `PendingTokenRevocationsTest`, `KeystoreAuthTokenStoreTest`,
`MobileAuthControllerSessionTest`, `TvAuthControllerTest`.

## Upgrade from tv-native

Android TV replaces the React Native TV app (putio-web `apps/tv-native`) under
the same application id, so its data survives the update. tv-native kept
the token as a raw string under `@putio:auth_token` in React Native AsyncStorage
1.23.1, whose Android backend is the SQLite database `RKStorage`, table
`catalystLocalStorage`. On a start with no Keystore session, TV reads that token
and validates it with put.io before anything is stored. Accepted, it goes into
the Keystore record and the shell opens without a code; a Keystore that cannot
hold it shows storage unavailable and keeps the database for the next launch.
Rejected (a false verdict or a 401/403), it is dropped and TV offers a fresh
code. An accepted token a sign-out left awaiting revocation follows
[Sign-out revocation](#sign-out-revocation) like a linked one. A stored or
rejected token deletes the whole `RKStorage` database,
journals included; its only other key is tv-native's update notice.
Without a verdict (network error, timeout, 5xx) the database stays, nothing is
stored, and TV shows Can't reach put.io with Retry; Retry and the next launch
try the import again. A start cancelled mid-validation also keeps it. A start
with a Keystore session never reads it and deletes any copy a failed cleanup
left. The database opens read-write so SQLite can recover a journal tv-native
left mid-write. Nothing logs it.

`/config` is per user and OAuth app, and TV links as tv-native's clients, so a
tv-native viewer's `playbackType` is already there. A TV read of `/config` with
no `video_playback_type` key maps `playbackType` `hls` or `mp4` to it, writes it
and plays that type; an existing `video_playback_type`, even one the app cannot
parse, is never overwritten. A 401 on that write fails the read and expires the
session like any `/config` 401; any other failed write still applies to that
read, and the next read writes again. tv-native's `bufferSize` is not carried
over: Android TV has no buffer setting and always buffers as tv-native's default
`medium` (see [TV playback](#tv-playback)); the key is left untouched in
`/config`.

Tests: `TvAuthControllerTest`, `AsyncStorageLegacyTvSessionTest` (a fixture
AsyncStorage database), `TvNativeConfigMigrationTest` (fixture `/config` blobs).

## TV sign-in QR code

Beside the activation code, TV sign-in draws a QR code of
`https://app.put.io/link?code=<code>`, put.io's approval page with the code
filled in, so a phone camera opens it ready to approve. That is the payload of
put.io's own QR image for the code: the SDK's `qrCodeUrl` is that PNG
(`/v2/oauth2/oob/qr/<code>`), not the page, so the app encodes the page itself
on the device (ZXing's encoder, error correction M) instead of downloading the
image. Dark modules sit on the light surface inside a four-module quiet zone,
snapped to whole pixels. The code takes no focus: Get new code stays the only
focus target, and a new code redraws it.

Tests: `TvLinkQrTest` (decodes the drawn pixels with ZXing's reader).

## TV Watch Next

Videos played on this TV appear in the launcher's Watch Next row (Play Next on
Android TV, Continue watching on Google TV) while they are in progress: put.io
holds a position above 0 and below 95 % of the duration, and more than
10 s before the end, which the player would start over (see
[Resume](#resume-and-position-reporting)). The card follows put.io's saved
position: it is published whenever a playback position write succeeds (every
15 s while playing, and on pause, stop and exit) or the watched toggle settles,
so a finished video, or one marked watched or unwatched, leaves the row. Audio,
and a video without a known duration, change nothing. Each card is a
movie-type Continue program with the file name, put.io's screenshot as 16:9
poster art (a token-free URL), the position and duration, and an intent naming
`MainActivity` with `putio://continue/<id>`. Opening it resolves the file and
plays from the saved position without the resume prompt; autoplay's next video
asks as usual.

A file put.io no longer has (a 404, which includes Trash) leaves the row when
its card opens it. Once per app process and user, when the app's screen first
opens signed in as that user (a cold start or a sign-in; the system search
provider alone never triggers it), the app reads each of that user's cards'
files: gone files leave, a position saved on another device moves the card or
removes a finished one, other failures keep it, and a 401 rejects the session.
One user keeps at most 20 cards, so that pass reads at most 20 files; the least
recently watched card makes room. A card the viewer removed from the row is
updated in place and stays hidden.

Cards belong to the signed-in user who played them (the provider's internal id
is `<userId>:<fileId>`). Sign-out, a rejected session, and a session put.io
rejected with no screen (see [TV system search](#tv-system-search)) remove
every card; signing in removes any other user's, and a write from a session
that is no longer signed in is dropped. The TV provider requires
`com.android.providers.tv.permission.WRITE_EPG_DATA`, a normal permission
granted at install; a device without the provider (Fire TV) ignores Watch Next.
The row lists only what this TV played: an account-wide list of in-progress
files would need an API the SDK does not expose.

Tests: `TvWatchNextRulesTest`, `TvWatchNextTest`, `TvProviderWatchNextStoreTest`
(a stand-in provider), `TvSessionViewModelTest`, `TvLaunchRequestTest`.

## TV system search

Android TV's system search lists put.io files. `searchable.xml` registers a
suggestions provider at `<applicationId>.search` for global search, and the
system search app queries it as the viewer types. Each query runs the
account's file search, the Search pane's first page, and returns at most the
system's limit (20 by default) rows with the name, put.io's screenshot when it
has one, and the content type and duration for media. Only callers holding
`GLOBAL_SEARCH`, the system search app, can use the provider at all. Signed out
it answers nothing. In a process the app has not started, it restores a stored
session once, without importing a tv-native token or requesting a code, and
leaves a session put.io cannot confirm right now to the app's own start.

Nobody sees that process, so a session put.io rejects there, at that restore or
with a 401 to a search, ends quietly: the token leaves the gateway and storage,
Watch Next empties, and the state returns to the one every start begins in,
without requesting or polling a code. The app's own start then offers a code,
and a screen already open in that process restores again and offers one.

A chosen row opens `putio://files/<id>`, which resolves the file and opens it
as a product link does on mobile: media plays with the resume prompt, a folder
opens in Files, any other file opens its folder focused on it, and a failure is
explained in Files. `ACTION_SEARCH` opens Search with the query submitted.
`putio://files/<id>`, `putio://continue/<id>` and a search received signed out
wait for sign-in and survive process death as their `putio://` form; recents
replaying the launch intent opens nothing.

Fire TV's Alexa and Video Skill Kit would need Amazon catalog integration
outside the app. Results shown in Google TV's system UI are unverified: Google
TV surfaces app content through Google's own catalog, and the emulator's
Assistant answers nothing without a Google account
([Harness](./harness.md#tv-platform-integration-proof)).

Tests: `TvSearchSuggestionsTest`, `TvLaunchRequestTest`, `TvAuthControllerTest`,
`TvWatchNextTest`, `TvSessionViewModelTest`, `TvGlobalSearchProofTest` (opt-in
live device proof).

## Inactive account

An account whose `account_status` is `inactive` shows a persistent notice on
every signed-in screen, with putio-web's states (`AccountStatusNotification`).
A family plan member (`is_sub_account`) reads Your family plan is no longer
active. Anyone else reads Your account has been deactivated 😢 and the days
until `files_will_be_deleted_at` (whole calendar days in the device's zone, as
web counts them; no message without a date). Unlike web, the count uses
Android plurals, so one day reads "1 day". Web's notices for active accounts
(payment warnings) and `stranger` accounts are not ported, so those show
nothing. The status is read with the account at sign-in and session restore.

Google Play's payments policy rules out web's billing and family links and its
calls to pay, renew or subscribe, so the notice has no action on either surface:
facts only, taken from web's copy (`strings.xml` cites each source key). Mobile
puts it under the top bar with nothing clickable; TV puts it above the pane as
plain text with no focus target, so D-pad entry into the pane is unchanged.

Tests: `InactiveAccountNoticeTest`, `PutioAuthSessionGatewayTest`,
`PutioTvSessionGatewayTest`, `MobileInactiveAccountNoticeTest`,
`MobileShellTest`, `TvShellTest`; on device, `TvSafeAreaProofTest`.

## Shared-with-me items

Friends' shared files (`is_shared`) and the shared folders (`SHARED_ROOT`,
`SHARED_FRIEND`) accept no owner mutations, so neither surface offers Rename,
Move, Move to trash/Delete or Mark as watched/unwatched on them, and mobile
doesn't offer [Exclusive access](#public-links). Download and
Share file (which downloads the original, then opens the share sheet) stay on
shared files, as web and iOS keep Download. On TV, a shared media file offers
only Open in VLC, as tv-native does, and Menu on any other shared item opens
nothing.

On mobile, a friend's shared file or folder, and anything inside one, offers
Make a copy, as web and iOS do; the shared root and each friend's folder offer
nothing, so they have no actions button. The Files move picker chooses the
destination among the viewer's own folders, root included, and opens where
Move's does (see [Move and copy target folder](#move-and-copy-target-folder)).
put.io copies in the background (`POST /v2/sharing/clone`), so a line under the
folder shows the copy until it is dismissed and stays across folder navigation.
The app checks the copy every 1.5 s, as web does, for up to 200 checks. A
finished copy reloads the destination if it is already open in the stack. A
failed copy shows put.io's reason under the [refused-request](#refused-requests)
rules, or web's copy for the concurrency and too-many-files limits. A copy whose
start gets no clear answer (a lost or unreadable response, or a server fault),
whose check fails, or that is still running after the last check, is reported as
unconfirmed, because it may still land. Only one copy runs at a time. Rotation
keeps the copy and its checks; process death discards them, so the line is gone
and another copy can start while put.io may still run the first. TV doesn't
offer Make a copy, because tv-native and tv-vite don't.

Tests: `SdkFilesRepositoryTest`, `MobileFilesScreenTest`,
`TvFilesRowActionsTest`, `FilesCopyTest`, `MobileFilesCopyTest`,
`MobilePublicLinksTest`.

## Move and copy target folder

On mobile, the Move and Make a copy picker shows web's "Remember target
folder" toggle, off by default. Off, the picker opens at root. On, Move here or
Copy here records the chosen folder and its path, and the next Move or Make a
copy opens there; Back walks that path up to root, reading each folder as it
is reached. Turning the toggle off keeps the last folder, as web does, and the
toggle takes effect from the next picker. A remembered folder that can't be
read opens the picker at root, unless put.io rejected the session, which signs
out as anywhere else. Each remembered folder is checked when it is first read:
a renamed one shows its new name, and one moved since keeps only root above it.
A move never opens inside the item it moves: a remembered path through that
item opens at root, as does a remembered folder whose parent is now that item.
A remembered folder nested deeper inside that item still opens, because the app
can't read its ancestry. put.io refuses a move into the item itself or any
folder inside it with a bare 403, so the item stays where it was and the
outcome says a folder can't be moved into itself or a folder inside it, in
place of the access copy other 403s get.

Web keeps both values in its own `/config`, which Android can't read, so
Android keeps them on the device, per signed-in account, in private
SharedPreferences. Sign-out and an expired session clear them for every
account.

Tests: `FilesMoveTargetMemoryTest`, `FilesMoveDestinationControllerTest`,
`MobileMoveTargetStoreTest`, `MobileAuthControllerSessionTest`, `MobileFilesMoveTest`,
`MobileFilesCopyTest`, `MobileMoveTargetProofTest` (opt-in synthetic device
proof; see [Harness](./harness.md#move-and-copy-target-folder-proof)).

## Files delete and paging

With the account's `trash_enabled` confirmed on, Move to trash runs from the
actions sheet without a confirmation on mobile and TV, as on web and iOS; only
Delete permanently confirms. Either verifies the item with an exact-ID read
before the folder reloads. On mobile, a Trash request that succeeded and that
the read confirmed shows a "Moved to Trash" snackbar with View Trash, once per
request; it has no Undo, because single-item Restore is queued server-side and
needs its own check ([Trash](#trash)). Every other outcome, including one a
later page corrects, stays as a line under the folder. The app never sends
`partial_delete`, so put.io refuses a folder with too many items for Trash
(`FileDeleteChildrenLimitError`) and changes nothing. On mobile and TV that
line shows web's "We couldn't send these files to trash" with Delete
permanently, which opens web's confirmation naming the folder; only its
Delete sends a new `skip_trash` request. Nothing falls back to permanent
deletion on its own, and the action leaves once a refresh no longer lists
the folder.

Mobile Files, Search results, History and the Move picker ask for the next
page once a row within 25 of the loaded end is on screen, as web does, so a
short page continues on its own; an empty page with a next one, in Files and
Search, asks for it at once. Each page is asked for once. A failed page waits
for its Retry, and in Files a running folder operation holds paging until it
settles. Android TV keeps its Load more button: it is the D-pad focus anchor
at the end of the list, one node that keeps focus from Load more through
loading to Retry.

Tests: `FilesDeleteReducerTest`, `SdkFilesDeleteRepositoryTest`,
`MobileFilesDeleteTest`, `MobileFilesListTest`,
`MobileSearchScreensTest`, `MobileFilesMoveTest`, `TvFilesScreenTest`,
`TvFilesPagingTest`, `TvFilesRowActionsTest`.

## Files root and folders

Mobile only. A file row is 64dp tall at the default text size and grows with
larger text. It marks a played file with one eye glyph, started or
finished alike (`start_from` above 0), and a finished download with the
on-device glyph; it shows no progress. Its name is cut in the middle to fit
one line, keeping the last 7 characters before the extension and the
extension; screen readers get the full name. Sizes use decimal units with one
decimal below 100 ("3.2 GB", "19.4 GB", "31 MB").

A folder's first page asks put.io for the total, so the line under its bar
gives the whole folder's item count and size (the folder's own `size`), not
the loaded rows'. Later pages keep that count; a refresh or a reload after a
change reads it again. Search from a folder's bar sends the query as
`<term> in:<folderId>`, so it covers the whole folder; recent searches keep
the bare term. Leaving that scope, or entering another, clears the query. The
root's search pill searches the whole account.

New folder, in the bar's overflow and the empty-folder state, creates the
folder with the trimmed name through the SDK's `createFolder`, then reloads the
folder it was made in, or marks it stale while another operation holds it.
put.io's refusal (a duplicate, a slash) shows under the field, and the dialog
stays open. Shared folders, which take no new items, don't offer it.

**Continue watching.** put.io keeps a position per file but has no list of
started videos, so the root's row lists videos played on this device. A video
joins once the player knows its duration and put.io accepts a position for
it, and leaves when put.io accepts 0 or a position within 5 % or 10 s of the
end, the bounds TV's Watch Next uses. The row keeps the 10 most recent, per
user, in private storage that sign-out clears. Once per session, the app
reads each entry's file: a 404 removes it, a position saved elsewhere moves
or removes it, other failures keep it, and a 401 rejects the session. A
delete Files confirms removes the entry too. A card plays the video, which
offers to resume.

**Offline.** When a folder can't load because the network is down, it shows
`(x_x)` "Can't reach put.io." with Try now. It retries by itself 30 s after
each failure, and at once when a missing or unvalidated network validates. At
the root, finished downloads list above it under "Offline. Showing what's on
this device." and play from the device; they stay while a retry runs. A
refresh or a page that fails after the folder loaded keeps the rows and offers
Retry on its line.

Tests: `FilesBrowserReducerTest`, `SdkFilesRepositoryTest`,
`SearchControllerTest`, `MobileContinueWatchingTest`,
`MobileContinueWatchingStoreTest`, `MobileMiddleCutTest`, `MobileFileSizeTest`,
`MobileFilesScreenTest`, `MobileFilesBarsTest`, `MobileShellTest`.

## Trash

Every Trash action confirms, submits exactly once, then verifies with one fresh
first-page Trash read. Delete permanently is done when the item is absent from
a complete first page, or from any first page after an acknowledged request; an
uncertain request whose item is absent from a partial page stays inconclusive,
and a complete page that still lists it settles the outcome as not done. Empty
Trash verifies only a known-empty Trash; a page with items settles it as not
done. Restore all submits the initial snapshot cursor when the server issued
one, so every ID of that listing is covered, otherwise the loaded IDs, and marks
every cached Files folder stale because restored items can land anywhere. It
verifies against that snapshot: a complete page holding none of the submitted
IDs, and with a cursor only rows deleted after the newest loaded one, counts as
done even when newer items have since arrived. While an action is inconclusive
or its read failed, Check Trash repeats only the read; no other mutation is
offered. After a failed refresh the loaded snapshot may be stale, so Restore
all and Empty Trash wait for a successful read.

Single-item Restore is queued server-side. An exact-ID Files GET must return
the selected kind and a valid current parent before the app says the item is
available again; restored names and parents can change. Pending recovery
survives tab navigation and activity recreation, and Check status repeats only
the read. Persistence across process death is not claimed.

Both surfaces state web's 14-day retention above the listing and in the empty
state.

Tests: `TrashActionTest`, `TrashRestoreTest`, `TrashRepeatedRestoreTest`,
`FilesRestoreInvalidationTest`, `MobileTrashViewModelTest`, `MobileTrashScreenTest`,
`TvTrashScreenTest`.

## Resume and position reporting

Fresh video resolution with `use_start_from` enabled and a positive saved
position offers Resume or Start over before preparing the player; Back or
dismissing it leaves playback on both surfaces, as tv-native's prompt did. Audio
continues from its saved position without asking, as iOS does. A saved position
within 10 seconds of the known duration counts as finished (iOS main's
threshold): the video or audio starts from the beginning without asking, on
both surfaces and for autoplay. The server keeps that position, so the file
still reads as watched. The duration comes from the
listing. When a video with a saved position opens without one (search, History,
product links, a row without `video_metadata`), resolution lists the file to
read it and keeps a duration it finds for the prompt and later retries. A
failed read leaves the duration unknown: mobile then offers the saved position,
and TV continues from it without asking. Audio never needs the read. The retained
controller keeps that decision across Activity recreation. Live audio attachment
and retained player-error recovery bypass the prompt. Start over starts locally
at zero; it does not immediately reset the server position.

One observer belongs to each actual player: the private video owner or the audio
service on mobile, the player screen on TV. Screens and notification controllers
do not duplicate audio reporting. The observer samples advancing playback every
15 seconds and captures positive positions on pause, stop, end, error, item
replacement and owner exit. Buffering and same-item seek events do not send
immediate writes. The application writer deduplicates positions within the same
second, permits one request in flight and one latest pending snapshot, and times
out a request after 15 seconds. A position offered while a request is in flight
is written after it, so the final one wins. Under slow requests or rapid file
switches, newer snapshots can replace intermediate queued exit positions, and
reopening a file drops its previous opening's queued snapshot. Failed writes
keep their typed cause and wait for a new position; there is no immediate retry
loop or completion reset: a finished item's real final position is written.

Reporting requires an app-issued item lease, the same signed-in session, and a
confirmed enabled resume setting. Pending/failed resume-setting writes suspend
reporting; unrelated setting writes do not. A downloaded file whose settings
never loaded this session reports under the last confirmed setting, and keeps a
position put.io cannot take ([offline resume](#downloads-and-offline-playback)).
Session exit or disabled/unconfirmed policy cancels requests and discards
pending positions without flushing. The application owns this policy
subscription so task removal does not stop audio updates. A source resolved with
resume disabled receives no reporting lease. An authoritative authentication
failure from a position write expires only the session that issued it. Rejection
runs outside the cancellable reporting job, and checks the session identity
again under the authentication controller's lock.

Tests: `PlaybackReducerTest`, `PlaybackControllerTest`, `MobilePlayerScreenTest`
(the prompt, recreation and Start over), `TvPlayerResumeTest` (Back on the
prompt), `PlaybackPositionWriterTest`, `PlaybackPositionObserverTest`,
`SdkPlaybackRepositoryTest`, `MobilePlaybackReportingTest`.

## Mobile video player

Video opens in immersive fullscreen. On phones, a landscape video turns the
window to sensor landscape; a portrait video keeps the window's orientation.
Tablets and multi-window use the available window. Back restores the shell's
orientation and system bars. Controls auto-hide during playback; a tap reveals
them, and an edge swipe still reaches system navigation. TalkBack and keyboard
navigation keep the controls visible.

The top bar has back, the file name cut in the middle, and picture-in-picture
where the device has it. A 72dp play/pause sits between 56dp ten-second seek
buttons. Under the elapsed and remaining times and the scrubber, chips name the
current audio track, subtitle and speed; each opens a 340dp side sheet from the
right, drawn inside the player's window so the system bars stay hidden.
Back or a tap on the dimmed video closes it. Controls keep 48dp touch targets.

- Audio lists the supported tracks by language in its own words, with channels,
  codec and commentary; the check marks the picked or playing track.
- Subtitles lists Off, then the tracks in the file, then the ones put.io found
  (in the folder, then OpenSubtitles), by the subtitle list's `source`. A track
  the API does not list, such as one embedded in the stream, counts as in the
  file. Subtitles are offered unless the account hides them (`hide_subtitles`).
- Size and timing scales Android's caption size by S, M, L or XL, kept on the
  device for every video; a size other than M also overrides sizes a styled
  subtitle file sets. It shifts this video's subtitles in 0.1 s steps up to
  10 s either way. Sooner subtitles come from the text renderer reading ahead in
  the buffered samples; a change to that lead seeks a millisecond on so the cues
  are read again. Later subtitles replay what showed that long ago, so right
  after a seek a delay leaves its first stretch without subtitles. While a sheet is open the
  subtitles stay centred in the uncovered video, at full size; while the
  controls show, they rise above them. A stream that does not name a subtitle's
  source, as HLS does not, lists it under the file.
- Hiding subtitles on the account closes an open Subtitles or Size and timing
  sheet.
- Speed steps through 0.5×, 0.75×, 1×, 1.25×, 1.5×, 2×, 2.5× and 3× with −
  and +, a stepped track and quick choices; every control snaps to a preset.
  Audio keeps its 0.75× to 2× choices.

With the account's `next_episode` on, a playing video asks for the next video
in its folder once, by autoplay's own walk of the folder in name order, and a
Next episode chip plays it at once, even one this opening already played. Speed, audio, subtitle choices and the
timing survive player recreation.

A video that needs another player shows "Can’t play this here." with no action:
a download keeps the same HLS rendition, so it would not help. Failures a retry
can fix offer Try again.

Tests: `MobileVideoWindowTest`, `MobilePlayerScreenTest`,
`MobilePlaybackOptionsTest`, `MobileSubtitleControlsTest`,
`PlaybackSubtitleTimingTest`, `PlaybackReducerTest` (Next episode), device proof
`MobileFullscreenVideoProofTest`
([Harness](./harness.md#immersive-landscape-video-proof)).

## Picture-in-picture

Mobile video moves into a picture-in-picture window when the viewer leaves the
app while it plays: Android 12 and later enter on their own, Android 8 to 11 on
the leave hint. The top bar's picture-in-picture button enters the window at
once, paused video included, on devices that have the window. The window is offered only while the video holds the screen on
(playing, not ended, idle or suppressed), so never for paused, finished or
failed video, the resume prompt, the conversion interstitial or audio, which
keeps playing through its background service. The window takes the video's
display shape, held between 1:2.38 and 2.38:1 within the platform's default
limits, and animates from the letterboxed video rather than the whole screen. A
device that refuses the shape or the window, and an Activity whose manifest does
not declare `supportsPictureInPicture`, give the video no window instead of
failing.

The window shows only the video, its subtitles and a thin yellow line of how far
it has played: the controls, the back button and seek feedback leave
composition, which also closes an open side sheet. Its menu carries 10 seconds
back, Pause or Play, and 10 seconds forward; they drive the screen's own player
through Media3's play and pause handling and the screen's own seek. Entering or leaving the window never
recreates the Activity or its player ([Multi-window](#multi-window)). Expanding
returns a landscape video straight to landscape: the phone's landscape lock
ignores picture-in-picture's own multi-window mode.

Entering the window pauses the Activity, not the video. On the API 37 emulator
the Activity's own picture-in-picture flag is already set when the pause
arrives, before mode listeners hear it; where a pause still beats the flag, the
window resumes what it stopped. In the window the started Activity counts as the
foreground: a pause or play there is what Expand keeps, and autoplay next starts
the next video playing in the window. When autoplay finds nothing more, the
window goes blank and the route returns only once the window closes or expands,
so the window never shows the shell. The screen-on hold follows playback as it
does full screen, and position reporting is unchanged: the observer samples
every 15 seconds while the window plays.

Expand returns to the full-screen player where the window was. Closing the
window stops the Activity, which releases the player and writes its position as
leaving the app does; whichever order the stop and the mode change arrive in,
the next visit finds the video paused at that position instead of playing on.

Tests: `MobilePictureInPictureTest`,
`MobileShellPlaybackTest.terminalAutoplayInPictureInPictureWaitsForTheWindowToClose`;
device proof `MobilePictureInPictureProofTest`
([Harness](./harness.md#picture-in-picture-proof)).

## MP4 conversion

Both surfaces resolve a video that needs MP4 conversion to the same shared
conversion state. The SDK resolves a conversion only for a video the server
marks `need_convert`, and its MP4 status reads not available until a conversion
is requested
([live check](https://github.com/putdotio/putio-android/pull/222#issuecomment-5904428342):
a not-available video converted and played). Opening such a video starts its
conversion through the SDK's `startMp4Conversion` without a tap, as putio-web,
tv-native, tv-vite and iOS do, and the interstitial says the conversion has
started. Only the first conversion status read of an opening can start one,
including one that follows a Retry after the opening's resolve failed: a first
read of queued, running, completed or failed is only read, so opening the file
again while it converts requests nothing, and after that first read, polls,
Check again and retries never start one, even across a failed read. A queued or
running conversion is read again every 3 s while the app is in the foreground,
keeping the interstitial up, and plays on its own once it resolves. Completed is
read once more at once; if it stays completed, Check again waits for the viewer,
as does an unknown status. Convert again after a failed one starts it again. A
start the server accepted reads as queued once, so a status read that has not
caught up polls again; if the status still reads not available after that, the
video cannot be converted and offers only Back.

Tests: `PlaybackConversionReducerTest`, `PlaybackReducerTest`, `PlaybackControllerTest`,
`SdkPlaybackRepositoryTest`, `MobilePlayerScreenTest`, `TvPlaybackStatesTest`,
`OfflinePlaybackRepositoryTest`.

## TV playback

Center on a Files media row resolves it through the shared
`PlaybackController` and `SdkPlaybackRepository`, which asks for HLS or MP4
per the account's confirmed video playback type (HLS until it loads), and
plays it full-screen on a Media3 ExoPlayer the screen owns and releases. The
player replaces the signed-in shell instead of covering it, so no shell control
can take D-pad focus; the shell's saved state is kept, and Back returns focus to
the row that was playing. Playback belongs to the session: sign-out ends it.

The overlay shows the raw file name, the option buttons, a seek bar, position
and duration. Any key reveals it for three seconds of playback; it stays while
paused, scrubbing or picking. The seek bar shows where playback starts before
the stream reports its duration, using the listing's duration until then.
Center, Enter or the remote's play/pause key toggles playback, and leaving the
app pauses it and shows the paused controls. If the activity is recreated (a
remote or keyboard connecting, a locale change), playback continues paused from
where it stopped.

Left, Right, rewind and fast-forward scrub, as the RN player did: the first
press pauses and the seek bar shows a pending target. Each press moves it
15 s times the press count, which grows while presses come within 500 ms
and restarts at one after a gap. A held key counts one press every 300 ms.
The target stays within the media. Center, Enter or play/pause seeks there and
plays; the dedicated play or pause key drops the target instead.

Above the seek bar sit the RN player's option buttons (putio-web `apps/tv-native`
`VideoPlayer.android.tsx`): Language only with more than one audio track,
Subtitles only with a subtitle track, and Speed always. Up from the seek bar
reaches the first button, Left and Right walk them, Down returns, and a
button's name shows above it while focused. Left and Right scrub only on the
seek bar; rewind and fast-forward scrub from anywhere and pull focus back to it.
Center opens the button's centred picker with focus on the current choice:
Audio tracks, Subtitles (Off, then the tracks) or Playback speed (0.25× to 2×
in quarter steps). Only the choices scroll, so the title stays in view.
A track shows its name, else its language; MP4 subtitles read `LANGUAGE - name`,
as the RN player relabelled its sidecar tracks. Speed and audio start over with
each file. Choices survive a rebuilt player.

Subtitles start from the account's settings, as on mobile: gone with
`hide_subtitles`, forced tracks only with `dont_autoselect_subtitles`,
otherwise selected automatically. Hidden means gone, as in every reference
player: playback asks put.io for no subtitles (`max_subtitle_count=0`
for HLS, no subtitle list for MP4 and original sources), and neither surface
shows its subtitle picker. Playback resolved for such an account says so, so
the picker stays out even before the settings load; hiding also outranks a pick
made earlier, turns that subtitle off, and closes a Subtitles picker left open.
Automatic selection starts on put.io's default subtitle, the first one the
account's subtitle languages rank: the HLS master marks it `DEFAULT=YES`, and
the MP4 subtitle list names it `default`. It outranks the system caption
language; without a default, Media3 picks by the system caption setting as
before. Until the settings load, and after a failed load, subtitles start off
whatever the system caption setting, so a `hide_subtitles` account never sees
them early; settings that arrive later still decide until the viewer picks.
Once the viewer picks, the pick decides, short of hiding: a
picked track is found again after track changes, and Off keeps the text type
disabled and draws nothing, whatever cues the renderer last delivered, across
seeks and track changes. Cues, bitmap (PGS) ones included, draw with the system
caption style through the same overlay as mobile.

Back dismisses the topmost layer: an open picker first (no change; focus
back on its button), then seek mode (no seek; playback resumes only if the
scrub paused it), then the controls (pause state untouched; they come back on
the seek bar), and only then leaves playback, once. A held Back is one press.

Resume follows the shared rule above (`use_start_from` on, a positive saved
position, a fresh resolution) and asks before the player exists, as the RN
player did: a centred dialog with the raw file name, a progress bar and
stacked Continue playing from `mm:ss` and Start from the beginning buttons.
Continue takes focus; the bar previews where the focused choice starts. Back
leaves playback, as the RN prompt and mobile's do. The dialog needs the
listing's duration, or the one resolution read for a target without it;
without either the saved position is continued without asking, as the RN
player did.

With Account's Autoplay next video on (the confirmed `autoplay_next_video`), a
finished video plays the next one in its folder by the shared rules mobile
uses: the folder's videos in name order, stopping after the last without
wrapping or crossing folders, never a video already played in that run, and
never after audio. The finished video's end is reported to the shared
position writer under its own lease, as on mobile. The next video resolves
fresh, so a saved position asks as above; the folder read supplies its
duration. Back while it is found or loads leaves playback. With the setting
off, or after the folder's last video, playback leaves as before. Leaving
after autoplay moved on focuses the row of the video it moved to last, even
when Back came while that video loaded or asked where to start. When that row
is on a page Files has not loaded, Files reads on to it as for an outside open
([Timestamps and History opens](#timestamps-and-history-opens)).

TV writes positions back through the same writer and observer as mobile,
owned by the signed-in session: a 15 s sample while playing plus pause, stop,
end, error and leaving playback, never a write per progress tick. Writes need
the session to still be the signed-in one and the confirmed resume setting on;
a source resolved with it off gets no lease. A player rebuilt for the same
playback (activity recreation) keeps its lease, so the old player's exit write
still lands. A saved position updates the Files row. A 401 from a write rejects
the session that issued it; sign-out discards pending writes.

The player is published as a Media3 media session while it shows, so the
system's media controls, Now Playing and remote media keys the screen does not
take itself reach it; the session is released before the player. A pause from
the session shows the paused controls; during a scrub it keeps the target but
Back no longer resumes. A play or a seek from the session drops a pending scrub,
and a play while the screen is stopped is ignored rather than run hidden. The
player buffers as the RN player's default `medium` size did: 8 s to 30 s
ahead, starting after 1.5 s, or 3 s after a stall.

A file that needs MP4 conversion shows the RN player's conversion interstitial:
the file name, why it cannot play yet, and its conversion status (starting, in
queue, a percentage, completed, failed, not available, or the server's own value), with
the actions under [MP4 conversion](#mp4-conversion).

Failures say what happened: no network, an expired playback link, too many
requests, put.io unavailable, no access, a request put.io refused (in put.io's
words when it gives a reason; see [Refused requests](#refused-requests)), an expired
session (the shell then signs out), or a format this device cannot play. Try
again shows only where it can succeed; it resolves the file again, which also
replaces an expired link, and a player error keeps its position for it.
Unsupported file types get a plain status screen as on mobile.

Tests: `TvPlayerOverlayTest`, `TvPlayerScreenTest`, `TvPlayerMediaSessionTest`,
`TvPlayerConversionTest`, `TvPlayerResumeTest`, `TvPlayerAutoplayTest`,
`TvPlaybackLayerTest`, `TvPlaybackStatesTest`, `TvPlayerOptionsTest`,
`TvChoiceDialogTest`, `TvPlayerTracksTest`,
`TvPlaybackReportingTest`, `TvSessionViewModelTest`, `PlaybackReducerTest`,
`PlaybackControllerTest`, `SdkPlaybackRepositoryTest`, `PlaybackFailureTest`,
`PlaybackPositionObserverTest`, `PlaybackSubtitleSelectionTest`,
`PlaybackAudioSelectionTest`.

## Share-in

The mobile launcher accepts `ACTION_SEND` with `text/plain`, `ACTION_VIEW` of a
`magnet:` link, and `ACTION_VIEW` or `ACTION_SEND` of `application/x-bittorrent`
content. Each opens an editable Add transfer sheet after sign-in; only Add
submits it, so a tapped magnet link never starts a transfer on its own (web's
magnet handler adds immediately). Intent payloads are untrusted: a magnet link
must parse with an `xt` parameter or it stays visible but invalid, and a
`.torrent` is read off the main thread from another app's content URI only
(never this app's own providers), capped at 16 MiB, and must look like a
bencoded dictionary with an `info` key. Provider failures mark it invalid; a
newer share or an account change cancels a read still running, and only one
read runs at a time. Its name is
stripped of paths and control characters and always ends in `.torrent`, because
put.io starts a transfer only for that extension; the upload also sends
`torrent=true`, so put.io refuses non-torrent content instead of saving it as a
file.

The sheet takes several links separated by spaces or new lines; each must be a
complete HTTP(S) URL or magnet link, duplicates collapse, and at most 100 go in
one Add (put.io's multi-add limit). Each line is checked on its own: a line with
anything on it that isn't a link stays in the field, dimmed, with a one-line
reason naming its number, and Add counts and sends only the good links
("Add 2"). Lines that never went stay in the sheet after Add. Paste appends
the clipboard's text on a new line; **.torrent file** opens the system file
picker, and the picked file follows the `.torrent` rules below, taking the
field's place without asking (the typed links come back when it is removed). One link uses `/transfers/add`, so put.io's
rejection is the failure shown; several use `/transfers/add-multi`, and links
put.io refuses reopen the sheet with "put.io couldn’t add these links" while the
rest start. Shared text with complete, unambiguous links prefills them one per
line; ambiguous prose stays editable with guidance. A shared `.torrent` replaces
the text field with its name and a remove button.

Save to defaults to the account's default download folder: the app omits
`save_parent_id` (`parent_id` for uploads) and put.io applies the default.
Change opens the Files move picker without a source item, where every folder,
root included, is a destination; the choice lasts for the session, as on web,
and resets when the account changes. put.io saves an uploaded torrent's
transfer to the default folder when its parent is root.

A new share cannot overwrite an existing draft without confirmation or
interrupt a running mutation. Text input is limited to 16 KiB of UTF-8;
oversized shares are rejected without truncation. A rejected oversized edit
keeps the previous draft but blocks Add until the user edits it. After a
successful Add, Transfers announces the number of transfers put.io started once
per request ("1 transfer added").

Share payloads, torrent bytes and add-transfer drafts stay in Activity-owned
memory. Rotation retains them; process death discards them. Saved state
contains only consumed request metadata, and received share extras, data URIs
and ClipData are removed from the retained Activity intent.

Tests: `MobileShareIntentsTest`, `MobileTransferDraftTest` (line checks and
what stays after Add), `MainActivityShareTest`, `MobileTransfersAddSheetTest`,
`TransfersReducerTest`, `SdkTransfersRepositoryTest`,
`FilesMoveDestinationControllerTest`.

## Share-out

Share file (Files and Downloads action sheets, non-folder items only, in both
the phone and the tablet rail layout) starts a
foreground `dataSync` service that fetches the original file through the API
download endpoint with the session header, stores it under private
`files/shares/<process>/<session>/<fileId>/<name>`, and opens the system chooser with a
`FileProvider` content URI (`${applicationId}.share`) carrying a read grant.
The payload is the stream only: no text, subject or URL, so no token reaches the
chooser. Progress and failure use the foreground notification, which the drawer
hides while the app holds no notification permission. A service cannot start an
Activity from the background, so the chooser opens from the resumed Activity:
immediately when one exists, otherwise a "ready" notification brings the app
back and the next resume opens it. After ten minutes without a resume the
export and notification are dropped. Each export removes every earlier export
first; a recipient still reading one keeps its open descriptor. Launch removes
exports older than a day.

An export belongs to the session that started it. Leaving that session, by
sign-out or an authoritative rejection, cancels a running export, deletes the
share folder and removes the notification; delivery checks the session again
before opening the chooser, so a resume that races a sign-out shares nothing.
The process-wide auth runtime owns this cleanup, so it also runs with no UI,
and a launch whose restore ends signed out deletes exports an earlier process
left. Session ids restart in every process, so exports sit under a per-process
folder, and every session exit also deletes the folders earlier processes left:
signing out of a restored session removes the previous process's exports too.
That cleanup can run after the next session has already started and exported,
so within this process it deletes only the departed session's folder. Cancelling an
export cancels its download at once, even mid-read.
The stored name keeps the original readable but drops path separators, control
and bidi formatting characters (which could disguise the extension), and is cut
to 200 UTF-8 bytes on a code-point boundary, keeping a short extension.

Tests: `MobileFileShareServiceTest` (payload shape, ready notification, service
stop rules, session exit, ready timeout, prior-export wipe, failure notification,
name sanitizer, cancelling a download blocked on the network),
`MobileOAuthRuntimeTest` (session-exit cleanup that lags the next sign-in keeps
the next session's export; signing out of a restored session deletes an earlier
process's export), `MobileShellTest` (Share on file rows in the phone and rail
layouts).

## Public links

Mobile only. A file or folder the viewer owns offers Exclusive access, web's
name for put.io's public links, in its action sheet; items shared with the
viewer don't (see [Shared-with-me items](#shared-with-me-items)), as web hides
it for `is_shared` files. The sheet shows web's warning (IP and data limits,
gone after 72 hours), the item's existing links with their expiry, and Create
link. put.io issues a new link on every create (two creates on one file returned
two links on the live API) and counts each against daily and weekly limits, so
Create link waits until the account's links have loaded: the viewer sees what
exists first, and a failed read offers Try again before anything else. A new
link joins the list, newest first. Each link offers Copy link, which puts only
the address on the clipboard, flagged sensitive on Android 13 and later so the
clipboard preview hides it; Share, a plain-text chooser with the address and the
file's name; and Revoke, which confirms first and drops the link once put.io
confirms. A failed revoke reads the links again, so a link already revoked
elsewhere or expired leaves the list; if that read fails too, the links stay as
they were. Account's Exclusive access lists every link the account holds with
its item, type and expiry and the same actions, as web's Sharing page does. One
request runs at a time, so opening the sheet or the list reads the links again
only when no other request is running; otherwise it shows that request's result.

The address is web's `https://app.put.io/exclusive-access/<token>`. The token is
the share's own, not the session's, and the app never logs or prints it. put.io
refuses a new link with a 403 and an error type; mobile shows web's copy for the
plan, file type, link count, daily and weekly limits, and a folder's size and
file count, without put.io's numbers, which the SDK doesn't expose. Web's copy
for `PUBLIC_SHARE_INSUFFICIENT_DATA` asks to fill out fields of a form the app
doesn't have, so it isn't ported. That refusal and any other failure follow
[Refused requests](#refused-requests), and a 401 signs out. Web's Watch together
option for videos (`utm_campaign=party`) is not ported.

Tests: `PublicLinksControllerTest`, `SdkPublicLinksRepositoryTest`,
`MobilePublicLinksTest`, `MobilePublicLinksProofTest` (opt-in synthetic device
proof; see [Harness](./harness.md#public-links-proof)).

## Documents provider

While signed in, Android's system file picker (`ACTION_OPEN_DOCUMENT`,
`ACTION_GET_CONTENT`) lists put.io as a root titled with the app's name and
the account's username. Signed out there is no root, as the platform's guide
asks. The auth runtime notifies the picker's roots when a session starts or
ends, so the root appears and goes without reopening the picker. A picker that
starts the app's process restores the stored session first, which reads the
account as the app's own start does; a document query waits up to 15 s for it,
and a session put.io can't validate (no connection) reads as signed out.

Read-only for v1: no create, rename, move, delete or write, a write-mode open
is refused before the document is looked up, and there are no recents, tree
access or thumbnails. Search runs through the Search domain's paging with the
term the picker sends.

Folders and searches load through the app's repositories, 50 rows a page. The
first query returns nothing with `EXTRA_LOADING`; each landed page notifies
the listing, and the picker's next query returns every row so far and asks for
the next page. Pages keep arriving while the folder is open and stop once the
viewer leaves it; each is asked for once. A picker listens for changes only
after its query returns, so a landed page is announced again every 0.5 s, up
to three times, until a query reads it. A failed page shows "Couldn’t load from
put.io. Open it again to retry." (`EXTRA_ERROR`) with the rows already loaded;
the next query asks for it again, so a failing page cannot loop the picker. A
listing idle for more than 30 s reads put.io again from its first page and
shows its old rows until that page replaces them. The provider has no refresh
hook, because the picker calls one before every load, which would drop each
page as it lands; a pull to refresh therefore re-reads only once the listing
is past those 30 s. The picker sorts rows itself.

A document id is `<userId>:<fileId>`, the root being file 0. Rows carry the
name, a MIME type from the extension (`application/octet-stream` when unknown,
since the SDK does not expose the API's `content_type`), the size of files,
the last-modified time from `updated_at` (else `created_at`) and no flags;
icons are the picker's own, by MIME type.

Opening a document returns a seekable proxy descriptor with its own thread. A
completed audio download holds the original file, so it is read from the
download cache without the network once the app's index and Media3's both read
it complete and every byte is on disk. A video download holds the HLS
rendition, not the original, so a video always streams. Streaming uses the
share-out request: the API download endpoint with the session in the header,
from the read's offset with a `Range` header; a server that ignores the range
sends the whole file, which is skipped to the offset, and a partial answer
that starts anywhere else fails the read. Reading on, or a jump ahead of up to
256 KiB, keeps the request; any other offset starts a new one. Opening adds no
download. An open waits up to 15 s for the session and up to 15 s for put.io to
return the file; a caller that cancels stops both waits at once.

The account in the document id keeps a URI from resolving under another
account. Every listing, single-file read and open descriptor belongs to the
session that made it; leaving that session (sign-out, an expired session, an
account switch) drops them, and reads on a descriptor another app still holds
fail and release its stream. A 401 on a listing, a single-file read or a
stream signs the session out as anywhere else. The auth runtime then revokes
every URI grant on the provider, persisted ones included, as it does when a
cold start's restore ends signed out. No token reaches a document URI, column,
cursor extra or log.

Tests: `MobileDocumentsProviderTest`, `OfflineOriginalsTest`,
`MobileOAuthRuntimeTest` (the picker hears each session start and end),
`SdkFilesRepositoryTest` (`updated_at`), `MobileDocumentsPickerProofTest`
(opt-in synthetic device proof; see
[Harness](./harness.md#documents-provider-proof)).

## Navigation

Mobile only; TV keeps its drawer ([TV Back](#tv-back)). Five tabs, in order:
Files, History, Transfers, Downloads, Account. A window narrower than 600 dp
gets a navigation bar and a wider one the rail, from the window's size
([Multi-window](#multi-window)). When a label would not fit its share of the
bar or the rail, at a large font scale or in a short window, the tabs move into
a scrollable modal drawer that a Menu button in the top bar opens.

Each tab keeps its own back stack: leaving Account on Trash and coming back
returns to Trash. Back pops within the tab, then returns to Files, then leaves
the app; nothing takes Back at the Files root. Files keeps its folders and
Search in its own state rather than in the back stack, so they survive a tab
switch too. Search opens over Files from the top bar's search button, Ctrl+F or
a link, and stays open until Back, the top bar's back or a Files link closes
it; it keeps its results and scroll while a folder it opened is on screen. Back
closes Search before it leaves a folder.

History, Transfers, Downloads and Account open on a tab title. Transfers' summary
counts, over the transfers loaded so far, the active ones (preparing,
downloading or completing; queued ones wait) and the failed ones, and adds the
active ones' download speed; Downloads' gives the space finished downloads use and what the app's
storage volume has free. Numbers in a summary set in mono, and a part with
nothing to say is left out.

Motion follows [Design](../DESIGN.md#motion): tabs fade through, a push within
a tab moves along the shared axis, and the player grows out of the row that
opened it. Every Back gesture previews its pop: a route shows the screen
beneath, and a folder or Search shrinks toward the gesture's edge. A Back from
a key or button acts at once. With animations off in the system settings, each
transition is a plain fade with no movement or preview, and the system's zero
animation scale makes that fade instant.

Tests: `MobileTabNavigationTest` (tab order, per-tab back stacks, Back to Files
then out, Search over Files, cancelled and committed Back gestures), `MobileMotionTest`, `MobileTabSummaryTest`,
`MobileLargeTextNavigationTest` and `MobileOrdinaryNavigationTest` (in
`MobileAdaptiveNavigationTest.kt`), `MobileShellTest`, `MobileShellLinkActionsTest`
(Search and Files links).

## Keyboard and shortcuts

Mobile only; TV keeps its D-pad rules. With a hardware keyboard, Tab and the
arrow keys walk focus through Files, which outlines the focused row, and Enter
opens the row as a tap would: a folder opens, media plays, any other file opens
its actions. A folder reached from the keyboard puts focus on the row last
focused there, such as the video that just played, else on its first visible
row. When the row that last held focus leaves the listing (moved to Trash,
say) and no row holds focus, focus goes to the row now in its place; focus the
viewer moved elsewhere, to the navigation rail say, stays there.

Shortcuts act only on keys no focused control used, so a text field keeps
Backspace and the arrows, and a focused button keeps Enter and Space:

- Backspace or Escape goes up a folder or back, through the same Back as the
  system gesture. With nothing left to close they do nothing, rather than
  leave the app.
- Ctrl+F opens Search over Files with the cursor in its field, from any screen
  but the player.
- F5 or Ctrl+R refreshes Files as pull-to-refresh does; neither runs while a
  sheet is open or an operation is under way.
- Delete acts on the focused row as its sheet's delete row would: Move to
  trash at once when the account's Trash is on, Delete permanently after its
  confirmation when it is off. While the setting is unknown it opens the
  sheet, which says why it cannot delete yet. Items shared with the viewer
  have no delete, so the key does nothing on them.
- The player takes focus when it opens. Space plays or pauses, Left and Right
  seek 10 s as the on-screen buttons do (a held arrow keeps seeking), and
  Escape or Backspace leaves.

Only bare keys and Ctrl are taken: Android's own shortcuts use Meta or Alt,
and Shift combinations are left alone. The system Keyboard Shortcuts Helper
(Meta + /) lists them under Files and Player. On Android 8 and 8.1, which lack
the platform's unhandled-key callback, androidx's `KeyEventDispatcher` delivers
them; `ComponentActivity`, which both apps' activities extend, routes keys
through it.

Tests: `MobileKeyboardShortcutsTest` (keys through the window's real input
stages, on API 35 and API 26), `FilesKeyboardFocusTest`,
`MobilePlayerKeyboardTest`, device proof `MobileTabletPowerProofTest`
([Harness](./harness.md#tablet-proof)).

## Drag and drop

Mobile only. A file row, not a folder, drags out to another app: with a mouse
or touchpad as soon as the pressed pointer moves, with touch or a stylus after a
long press. The long press still opens the actions sheet; moving on starts the
drag and closes it. The drop receives one
`content://<application id>.drag/<random key>/<safe name>` URI, labelled with
the file's name and typed from its extension: no token, account id or API URL.
The drag grants global read only (`DRAG_FLAG_GLOBAL` and
`DRAG_FLAG_GLOBAL_URI_READ`; no write, persistable or prefix grant), so the
other app reads it only through the drop's `DragAndDropPermissions` and not
after releasing them. The provider is not exported.

Nothing is downloaded or copied for a drag. Opening the URI hands out a
read-only proxy descriptor at once, and reads stream the original from put.io
with ranged requests, as the [system file picker](#documents-provider) does:
the API's download endpoint with the session in the header, never in the URL.
Every read checks that the session that started the drag is still the signed-in
one; leaving it stops reads in progress and forgets its drags. A drag nobody
took is forgotten when it ends; a dropped file stays readable by the app it
landed in for as long as the drop's grant lasts and the app keeps its 32 latest
drags.

Links, magnet links and `.torrent` files dragged in from another app open the
Add transfer sheet through [Share-in](#share-in)'s intake and its rules: nothing
is added until Add, a new drop asks before replacing an unfinished draft, and a
`.torrent` is read off the main thread under the drop's grant, released once
the read is over or a newer intake cancelled it, and refused from this app's
own providers. While such a drag is
under way the signed-in screen shows "Drop to add a transfer" across its pane.
Item text and `http`, `https` and `magnet` URIs are read as links; a content
URI counts as a torrent only when the drag labels it `application/x-bittorrent`
or `application/octet-stream`, and is never read as text. Other files, images
for example, do not light the target, because the app has no file upload. An
open sheet or dialog is its own window and takes no drops, and nothing is
taken during playback.

Dragging an item onto a folder inside the app does not move it; Move stays in
the actions sheet.

Tests: `MobileDragProviderTest` (payload and grant flags; opening fetches
nothing; ranged reads; a session change or end stops reads),
`MobileTransferDropTest`, `MobileTransferDraftTest` (a cancelled read still
releases the grant), device proof `MobileTabletPowerProofTest`
([Harness](./harness.md#tablet-proof)).

## Multi-window

MainActivity handles orientation, screen size, layout and keyboard changes
itself, so split screen, freeform resizing, rotation, picture-in-picture and a
keyboard being attached reach Compose without recreating the Activity:
navigation, open sheets, scroll positions and the playing video's player carry
on. Changes that still recreate it (density, font scale, locale) restore
navigation and the player from saved state as before. Layout follows the
window, not the display: the bar, rail or drawer and Account's device class
come from the window's size, so a pane 600 dp or wider gets the rail when it is
tall enough. A paused split-screen or freeform window is still on screen, so
video keeps playing until the window stops; a pause or play made while it is
paused, from a headset say, counts as the viewer's, so resuming the window keeps
it. The video window requests no orientation in multi-window mode.

Tests: `MobileMultiWindowTest` (resizing, rotation and a keyboard keep the
Activity; a density change recreates it and keeps its intake; the shell keeps
its destination across resizing and recreation), `MobilePlayerKeyboardTest`
(split-screen pause), `MobileLargeTextNavigationTest` and
`MobileOrdinaryNavigationTest` (in `MobileAdaptiveNavigationTest.kt`), device
proof `MobileTabletPowerProofTest` ([Harness](./harness.md#tablet-proof)).

## Launcher entry

`MainActivity` answers each launcher's `ACTION_MAIN` query: `LAUNCHER` on both
surfaces, and `LEANBACK_LAUNCHER` on TV, so Android TV home lists the app. The
manifest merger keeps a flavor filter apart from main's, so the TV filter
carries its own `MAIN` action. The harness launches by explicit component and
cannot catch a missing entry.

Tests: `VerifyLauncherManifestTest`, plus `verify<Variant>LauncherManifest`
against each merged manifest in `check`.

## Deep links

`https://{app.put.io,put.io,www.put.io}/{files,files/<id>,transfers,search,history,trash}`
(`autoVerify`; the `assetlinks.json` publication is a release-owner task, so
unverified installs still open through the app chooser or an explicit package)
and `putio://{files,transfers,search,history,trash,downloads}`, plus
`putio://transfers/add`, which opens Transfers with an empty Add transfer sheet,
`putio://downloads/<id>`, which a download notification opens to show that row's
actions, and `putio://downloads/<id>/play`, which plays that row's copy on this
device over Downloads or, without one, opens the row. Links a download
notification builds add `?user=<userId>`: under any other account the shell
drops them unopened, and a `user` that is not an account id voids the link. No
other query is read. `putio://auth` stays with the OAuth receiver. A link is
consumed once per Activity intent, any put.io URI is removed from the retained
intent, and routing waits for sign-in; an unrouted link survives process death
as its token-free `putio://` form, `user` included. A file id resolves through
the item resolver, opens as under [History opens](#timestamps-and-history-opens)
with Back staying in Files, and reuses the navigation-failure dialog.

Tests: `MobileDeepLinksTest`, `MainActivityDeepLinkTest`, `MobileShellLinkActionsTest`.

## App shortcuts

Long-pressing the mobile launcher icon offers three static shortcuts: Search
(`putio://search`), Add transfer (`putio://transfers/add`) and Downloads
(`putio://downloads`). Each is an explicit `ACTION_VIEW` intent for this
variant's `MainActivity`, whose package the build generates from the variant's
application id, so production, nightly and debug installs never open each
other's. Each routes as the product link it carries under
[Deep links](#deep-links): signed out, the app shows sign-in and opens the
target once signed in. Add transfer opens an empty sheet and adds nothing until
the viewer taps Add; no shortcut changes anything on its own. The shortcuts
need no session, so the launcher lists them from install. Their icons are
adaptive: a Phosphor glyph on the brand yellow, in the launcher's own shape.

Tests: `MobileShortcutsTest` (reads `shortcuts.xml` with the platform's own
intent parser), `MobileShellLinkActionsTest`, `MobileDeepLinksTest`.

## Timestamps and History opens

The API sends UTC datetimes without an offset (`2026-09-09T15:25:32`). Mobile
and TV read them, and offset stamps, through one parser, so History groups by
local day and Files rows keep their date; a stamp it cannot read shows raw.
Choosing a History row resolves its file in the signed-in session, and only the
latest choice opens: a second tap while the first resolves opens one item.

A Search result, a History row or a product link opens the item itself, as
putio-web, tv-native and iOS do. Video and audio play at once over the screen
they were chosen on, with the resume prompt above; Back returns there, and on
TV to the row that was chosen. TV's
prompt needs a duration, which search results and single-file reads lack, so
the session first lists the file itself, which put.io answers with the file as
the parent and its `video_metadata`; if that read fails, playback continues
without asking. A folder opens in Files under its name. Any other file opens
its parent folder, titled from that folder's listing, with the file's row
selected on mobile and focused on TV, and scrolled to. When the file is not on
the pages read so far, the folder shows as loading while it reads on, one page
at a time, until the file appears or the folder ends, then opens once at it;
leaving the folder cancels the reads. It reads at most 10 pages (500 rows).
Past that, or when the folder ends without the file, the folder opens at its
top with the rows read and Load more (TV) or paging (mobile) for the rest, and
TV focuses the first row. A failed page shows the rows read so far with Retry.

That folder sits on top of the viewer's Files location instead of replacing it;
the kept location reloads when Back returns to it, since changes made above can
reach its listing. Back from it returns to Search, History, or Transfers, whose file opens the same way without
playing, with TV focus back on the chosen row; after a product link it returns
to the Files location. A later outside open replaces the earlier one rather than
stacking, and Files refuses it while a move or deletion settles.

Tests: `PutioTimestampTest`, `MobileSearchHistoryViewModelTest`,
`MobileSearchScreensTest`, `MobileFilesScreenTest`, `TvSessionViewModelTest`,
`FilesBrowserReducerTest`, `FilesRevealReducerTest`, `FilesBrowserControllerTest`,
`MobileShellTest` (`MobileShellExternalOpenTest`), `TvPickReturnFocusTest`.

## History events

Mobile names the file, transfer or filter an event concerns, cut in the
middle on one line, over what happened and its time: Shared with you, Transfer
completed, Uploaded, Transfer failed, Deleted to free space, RSS filter paused,
RSS transfer failed or Callback failed. Date headers read Today, Yesterday,
then the date, with the year only when it is not this one. An event with no
name, or of a type with no copy, reads No title, never its raw API type; one of
a type with no copy gives its time alone. TV lists only shared files and
completed transfers, as tv-native does, including a share whose file is gone; a
page with neither reads on to the next, so dropped events neither end the list
nor leave it empty while older events wait.
With history off, both surfaces name the Keep account history switch in Account.

Tests: `SdkHistoryRepositoryTest`, `MobileSearchScreensTest`,
`MobileSearchScreensLayoutTest` (the middle cut), `TvSessionViewModelTest`,
`TvHistoryScreenTest`.

## Storage quota

The account header labels the quota as web and iOS do: X of Y free when the
account's `show_optimistic_usage` is on, X of Y used otherwise. The bar fills
with what is used either way.

Tests: `PutioAuthSessionGatewayTest`, `PutioTvSessionGatewayTest`,
`MobileAccountScreenTest`, `TvAccountScreenTest`.

## Privacy controls

Mobile has two account-wide privacy controls, shared with every put.io app:
Diagnostics (`diagnostics_enabled`, on by default) and Product analytics
(`product_analytics_enabled`, opt-in and off by default). Account shows them as
one Privacy controls row with their current state ("Diagnostics on · Product
analytics off"), which opens a Privacy controls page. The page discloses
Strictly necessary processing as Always on, with no toggle, then the two
toggles, a footer that says neither sends filenames, media titles, URLs, search
text, or tokens, and links to the Privacy policy and Services we use. Each row's
description is part of that row's accessibility node, so TalkBack reads it with
the control. The copy is the legal-approved set from
[support#135](https://github.com/putdotio/support/issues/135). The app reads
and writes no support-widget setting.

Tests: `SdkAccountSettingsRepositoryTest`, `MobileAccountScreenTest`,
`MobilePrivacyControlsScreenTest`, `MobileAccountLargeTextTest`.

## Diagnostics

Mobile and TV report crashes, ANRs and player failures to Sentry only while
the account's Diagnostics control is on, and only from a release build made
with the `putioSentryDsn` Gradle property (or `PUTIO_SENTRY_DSN` in the
environment). Debug builds, unit tests and every build without it never start
Sentry; leaving the DSN out is the kill switch that needs no code change.

- Signed out, or before any account answered, counts as on. The device keeps
  the last account's answer, so a cold launch honours an opt-out before the
  session restores. Restoring, validating, signing out, and a secure store that
  cannot be read or cleared keep it, since the token may still be on the
  device; signed out, diagnostics are on again.
- The answer read at sign-in applies first; each settings read and the mobile
  Privacy toggle supersede it in the running app. An opt-out stops capture at
  once, before put.io confirms it; an opt-in waits for the confirmation.
- Sentry starts in `Application.onCreate`, before any screen or service;
  content providers start earlier and are not covered. Turning diagnostics off,
  or launching with it off, closes the SDK and deletes everything waiting for
  upload. While off, a transport gate holds every upload, and an SDK still
  closing never reopens it. A start after an opt-in drops events stamped before
  it, such as an ANR from the opt-out period that Android reports later; if the
  app dies before that start reads Android's exit history, the next launch can
  still report the ANR. So can the first launch of a build with a DSN after
  one without, since a build without a DSN never reads that history.
- Events post through `relay.put.io`, which forwards only allowlisted Sentry
  projects; the app swaps the DSN's host for it. The `surface` tag says
  `mobile` or `tv`, the environment is the channel flavor, and the release is
  `<application id>@<version name>+<version code>`.
- Every event, crashes and ANRs included, leaves as a new one built from an
  allowlist in `core/telemetry`: exception types and stack frames, level,
  release metadata, the R8 mapping id, the installation id, the OS version, the
  device model, and the `surface` and player-failure tags. Exception messages,
  request data, extras, threads, attachments, and any field a Sentry upgrade
  adds stay on the device; the SDK keeps no breadcrumbs. Player failures are reported
  as putio-web's `playback_failure` codes (failure mode, Media3 error code,
  source kind, HTTP status, file id), never as error text: video from the
  player screen, audio from the background audio service.
- The SDK is `sentry-android-core` alone: JVM crashes, ANRs and release-health
  sessions, with no NDK, replay, tracing, logs or metrics. Builds from
  [Google Play delivery](distribution.md#signing) upload their R8 mapping
  under the id their events carry; other release builds upload none, so their
  stack traces stay obfuscated.

Tests: `SentryBoundaryTest`, `PutioDiagnosticsTest`, `SentryConfigurationTest`,
`DebugSentryDsnTest`, `AccountSettingsDiagnosticsTest`, `PlaybackTelemetryTest`,
`MobileDiagnosticsTest`, `TvDiagnosticsTest`, `PutioAuthSessionGatewayTest`,
`PutioTvSessionGatewayTest`.

## Recent searches

Recent searches are the account's `/config` `searchHistory` list, newest first
and capped at five, with `searchHistoryEnabled` turning them off; tv-native
reads and writes the same keys in the same format. Mobile and TV keep a term
when it is submitted (Search on the keyboard, or a chip replayed) and when a
result is opened, the term that result came from, as tv-native does. A search
that runs because typing paused is not kept, so D-pad typing on TV does not
fill the list with prefixes.

On TV, Settings to the right of the search field offers tv-native's search
settings: Disable search history clears the list and then turns the setting
off, Show search history turns it back on, and Clear search history empties
the list while it has terms. Each setting change rereads `/config` first, so
it clears or keeps what other clients stored since the screen loaded. While the
setting is off nothing is kept and no chips show.

Tests: `SearchControllerTest`, `AppConfigRecentSearchStoreTest`,
`TvSearchScreenTest`.

## Downloads and offline playback

Downloads use Media3's `DownloadService` and `SimpleCache` under the app's
internal files directory (`files/downloads/`), never external storage: cached
playlist bodies carry the server's token. A video download stores the HLS
rendition the player streams; an audio download stores the original file. HLS
asks for every subtitle rendition, or for none when the account's confirmed
`hide_subtitles` is on, as streaming does. The confirmed setting is stored with
the row, so a cold offline start, which cannot read settings, still hides the
Captions picker for a download made without subtitles; a row started before
settings confirmed keeps the old all-subtitles request and the player's usual
rule. Media3 owns bytes, resume and the foreground notification, which
shows a count and progress only. The app's index in private SharedPreferences
holds file id, name, type, rendition, status, queue time, a pending-delete mark,
the confirmed subtitle setting, the listing's saved position, the viewer's
pause and whether the row was rebuilt per user; it never holds a URL.

Requests carry the token-free API URL and the file's name, and a resolving data
source adds the session header for `api.put.io` hosts. The app builds that URL
itself: the SDK's URL builders always embed `oauth_token`, which Media3 would
persist; it takes the subtitle count from the SDK's `HLS_ALL_SUBTITLES`.
Playlist bodies from the server embed `oauth_token` in their child URLs; the
cache key factory strips that query and prefixes the owning user id, so the
Media3 index stays token-free and two accounts never share cached bytes. There
is one `DownloadManager`; request ids are `userId:fileId`, each request
downloads under its owner's keys, and a sign-out parks that user's transfers
with a stop reason until the owner signs in again. Playback reads through the
same cache with a null write sink, so streaming never fills the download
directory. Offline playback replays the URL Media3 recorded for the request, so
a download keeps playing after the app changes the URL it builds for new ones.
Closing the engine cancels its start-up reconcile, so a sign-out right after
sign-in cannot un-park the transfers.

**Queue.** Media3 runs up to the device-wide concurrency limit at once, 1 to 4,
default 3 (iOS main's choices), and starts the rest oldest first by its own
start time, which it persists. The tab's overflow shows the limit and lets the
viewer change it; a lower limit stops the extra transfers, keeping their bytes,
and they rejoin the line. Its Downloading section lists unfinished rows in
Media3's start order with each waiting row's place (`Queued · #1 in line`), then
Needs attention (failed and missing rows), then On this device. A retry or
Download again joins the back of the line. A row's ring fills only when Media3
reports a percentage over a known denominator, the original's content length or
the HLS segment count once the playlists are read; otherwise it spins. Its meta
reads bytes of the whole when Media3 knows the content length, which an HLS
rendition rarely has, else the bytes and any percentage, then the speed. Only
transitions reach the index; while the Downloads screen is started, the
controller reads live bytes once a second into memory and measures each running
row's speed between two reads at least half a second apart, starting over after
a gap of more than 5 s.

**Pause and resume.** A row's ring pauses a running or waiting download,
resumes one the viewer paused, and cancels a queued one without asking, since a
queued row holds nothing playable. Selection's Pause and Resume act on the
selected rows that allow them. The index keeps a pause and Media3 holds it as a
stop reason; every start and report holds Media3 to the index, so a pause
outlasts process death, sign-out parking, another account's sign-in and a
request Media3 heard of only after the pause. A resumed download keeps its
place in line; a wait for the network or storage still resumes by itself.

**States and recovery.** A row is queued, downloading, waiting (for a
network, or for the system to stop reporting low storage; both resume by
themselves), paused by the viewer, failed (retryable), on this device, missing,
or deleting. Transfers
that fail with `ENOSPC` read as not enough storage, with Retry. On start the
engine reconciles Media3's index into the rows, so a transfer that finished,
failed or paused while the UI was dead reads that way after relaunch. A row
Media3 has no record of is never dropped silently: a finished one reads missing,
a failed one keeps its reason, and an unfinished one goes back in the queue. A
finished row whose bytes are gone from the cache, found at reconcile or when
offline playback opens it, reads missing: it stops counting as on this device in
Files and Downloads, streams instead, and offers Download again. The reverse, a
download of this user that Media3 lists but the index does not, comes back as a
row: video for an HLS request, audio for an original, the name the request
carried or, for a request made before requests carried names, "Downloaded file",
the subtitle setting its URL asked for, and Media3's state. It plays and deletes
like any row. The index reads rows one by one: a row this build cannot read,
such as a newer build's, costs only that row and is written back untouched, and
a document that is not a list is copied aside under `<key>.unreadable` before
the next write. Bytes leave the device only through a confirmed delete.

**Removal.** Deleting removes only local copies; Downloads has no path to the
put.io originals. One row's sheet or a multi-select asks first, saying the
files stay in the account. Select, in the tab's overflow, opens an empty
selection and Remove waits for a row; a long press opens one with its row;
Select all takes every row. Confirmed
rows are marked before Media3 deletes their bytes, stop counting as available
at once, and leave once Media3 confirms; a process death in between finishes
the delete on the next start.

**Notifications.** When a download finishes or fails, with or without the app
open, a notification names the file and opens its row in Downloads; the lock
screen shows the outcome without the name. A finished download offers Play
(the copy on this device over Downloads, or its row if the copy is gone) and
Open in Downloads; a failed one offers Try again and Open in Downloads. Play,
Open and the tap are explicit, immutable intents for MainActivity carrying
`putio://downloads/<id>[/play]?user=<userId>`, so under another account they
open nothing (see [Deep links](#deep-links)).

Try again is an explicit, immutable broadcast to an unexported receiver, so no
other app can send it. It restores a stored session first, for up to 5 s
(Android lets an app start its download service for about 10 s after a
notification tap), then hands the download back to Media3 without opening the
app, only for the owning account and only while the row is still failed or
missing; the row then follows Media3's state. Under another account or none,
or for a row that is gone, it removes the notification instead. While the
session cannot be confirmed, or when the system refuses to start the service,
it does nothing and the notification stays; it goes only once the service has
the request, and a second tap finds the session restored.

Each file has one notification slot, cleared by a retry, a delete or the end of
its account's session; when a session ends, every outcome of an account other
than the one then signed in is removed. Only the signed-in account is notified,
so one account's outcome never appears under another. Nothing is posted while
the app's notifications or the Finished downloads channel are off, or, on
Android 13 and later, while POST_NOTIFICATIONS is not granted. The first
download asks for that permission once; afterwards Downloads shows a notice
whose Turn on asks again while the system allows it and otherwise opens the
app's notification settings. Transfers post no notifications.

**Offline resume.** Offline playback resumes like streaming: with the resume
setting confirmed this session, else the one this device last confirmed, else
off. It starts from a position this device played but put.io has not taken,
else what put.io reports within 3 s, else the last known one, else the
listing's position at download time. A downloaded file reports positions under
the same rules as streaming; when settings never loaded this session, as on a
cold start offline, it reports under the last confirmed setting. A position
put.io cannot take for a transient reason waits on the device. Once a validated
network returns, on sign-in, and after an accepted write, a sync pass for the
signed-in user follows iOS main's `OfflineVideoPlaybackPositionSync`: it drops
every waiting position if the account has resume off; a position another
device saved since this one went offline wins; otherwise this device's newest
position is written, and a write that landed without a reply is recognised. A
position the online player gets accepted while a pass runs is newer: the pass
checks again right before it writes and never sends, records or publishes the
older one over it. A file put.io refuses for good (deleted, or access lost)
drops its waiting position instead of being read on every pass.

Tests: `DownloadsControllerTest` (intents, bulk removal, retry order, missing
copies, concurrency, progress polling while shown, pause and resume for the rows
that allow them, cancelling a queued row, speed), `MobileDownloadsScreenTest`
(queue order and places, progress meta, the limit, each ring's action, stuck
rows, the notification notice, selection and its toolbar, shown and hidden
events), `MobileShellDownloadsTest` (bulk delete never reaches
put.io, notification links, the subtitle setting a Files download carries),
`MobileDownloadStoreTest` (unreadable rows kept),
`MobileDownloadActionReceiverTest` (Try again for the owner only, a refused
start keeps the notification), `BroadcastHoldTest`, `MobileDownloadEngineTest`
(queue order and limit across process recreation, recovery of every row state,
rows rebuilt for downloads an unreadable index lost, low-storage pause,
reconcile, sign-out parking, a pause held through sign-out, another account,
process death, parking that overwrote it and a late Add until resumed, account
isolation, close before reconcile,
in-memory progress and its denominator, the recorded request URL and the
local-copy check against a real Media3 manager and cache),
`MobileDownloadNotificationsTest` (granted, denied and disabled states,
signed-in account only, actions and their intents, other accounts' outcomes at a
session's end), `OfflinePlaybackPositionsTest` (sync rules, the player's write
racing a pass, refused files), `MobilePlaybackReportingTest` (cold offline
start), `UserScopedCacheKeysTest` (token-free, user-scoped cache keys),
`OfflinePlaybackRepositoryTest`, `MobileDeepLinksTest`.

## Transfers widget

Mobile offers a resizable four-by-three Transfers home-screen widget. Signed in,
it lists up to three transfers still running (anything but completed or
failed, seeding included) from the first page of the account's transfers,
newest first. Each row shows the status in the Transfers screen's words, a
percentage when put.io reports one and a progress bar, indeterminate without
one; a seeding row's bar is green, and full without a percentage. A footer
says when the rows were read and how many more are running. With
none it reads No active transfers. A tap opens Transfers through
`putio://transfers`, and a refresh button reads again. Signed out it reads Sign
in to put.io to see your transfers, with no refresh button, and a tap opens
sign-in, then Transfers. On Android 12 and later the widget picker previews it
with sample rows.

It reads the transfers through the SDK in the signed-in session, never with a
token of its own: when it is placed, every 30 minutes (`updatePeriodMillis`, the
platform's shortest period), on sign-in, on its refresh button, and whenever the
Transfers screen loads rows, which it shows without a request of its own. With
no widget placed nothing is read. A refresh replaces one still running and gives
up after 25 s. In a process the app has not started it restores the stored
session first, for up to 15 s, as the download service does. The broadcast that
asked for it ends after 8 s at the latest, within the 10 s a foreground-queue
broadcast gets, and a refresh still running then goes on best-effort; the next
update tries again if the process dies first. A 401 signs the session out as
anywhere else. A failed read keeps rows already shown, with the time they were
read; with none shown, or when the session cannot be confirmed, it reads
Couldn't reach put.io.

Rows belong to the session that read them. A read is shown only while that
session is still signed in, checked under one lock with sign-out, so a read
that finishes after sign-out or an account switch is dropped. A session's end
(sign-out, a rejected session, a cold start whose restore ends signed out)
replaces every placed widget with the signed-out state at once and empties
each row's text too, because launchers reapply new views onto the old ones.
Nothing is stored: the launcher holds the only copy of the names, and the
widget's intents carry token-free product links. The refresh button is an
explicit, immutable broadcast to an unexported receiver. Its colors are the
generated design tokens' XML colors (`@color/putio_*`).

Tests: `MobileTransfersWidgetTest` (each auth state against Robolectric's
widget host, which reapplies views as launchers do: sign-out, a read that
outlives its session, an account switch, a 401, failed reads, no widget, the
Transfers screen's rows, intents), `BroadcastHoldTest` (the broadcast ends
by 8 s, once).

## TV Back

Files is the TV home, as Home is in tv-native and tv-vite. Back with focus on
the drawer closes it and returns focus to the pane's last-focused row, on every
destination, without applying any pane rule (an empty History returns to
Clear); a pane with nothing to focus takes its own step at once. In the pane,
its own layer closes first (Trash under Account, the unsupported-file screen).
Then Back on Search, History or Account returns to Files, focused on the row
that last held focus there, with any folder stack kept; Back on Files leaves a
folder, and on the Files root it falls through to the system and leaves the app
without a confirmation, per Google's TV navigation guidance.

Tests: `TvShellBackTest`, `TvShellFocusTest`, and on an emulator
`TvExternalOpenProofTest#backOnTheDrawerReturnsToThePaneThenThePaneRulesApply`.

## TV overscan safe area

The signed-in TV shell paints its background to the screen edges and keeps
the drawer and pane inside the overscan safe area: `tv.overscan.x` of the
viewport width on the left and right, `tv.overscan.y` of its height on the top
and bottom. The fractions are generated
from the token graph and applied to whatever viewport the shell fills, so 720p,
1080p and 4K panels keep the same proportion clear. The pane adds 16dp from the
drawer and the safe edges. The player's control scrim also reaches the screen
edges, with its controls inset by the same safe area plus 16dp.

Tests: `TvSafeAreaTest` (collapsed and expanded drawer; 960x540dp and
1280x720dp viewports; a 4K xxxhdpi panel), `TvPlayerSafeAreaTest` (player
controls), `DesignTokenCodegenTest` (overscan ratios, axis and presence checks).

## Refused requests

When put.io refuses a request with a 4xx and its own `error_message`, both
surfaces show that message, as web does, in place of the app's generic copy:
Files, Search, History, Transfers, Trash, the move picker, Account settings,
the player and public links. 401, 403, 408 and 429 keep the app's own copy, and more
specific app copy (an unavailable move
destination, an incomplete Trash restore, a transfer with nothing to retry)
still wins. 5xx, network and unreadable responses keep the existing copy, and
copy that never named the failure, such as paging and refresh footers, stays
as it is. A message that is a bare error code, longer than 300
characters, mentions a URL or a credential, or carries the SDK's redaction
marker is not shown; the copy applies instead. The message comes from the
SDK's redacted `PutioApiException.errorMessage`.

Tests: `ApiRejectionReasonTest`, `ApiReasonAcrossSurfacesTest`,
`MobileFilesScreenTest`, `MobileFilesMoveTest`, `MobileTrashScreenTest`,
`TvPlaybackStatesTest`.

## Transfer failures and retry

Mobile only; TV has no Transfers screen. A failed card shows "Failed" and
put.io's `error_message` as the API sends it, trimmed; one without a message
shows "This transfer needs attention." A running card shows a message put.io
sends with it ("Tracker did not respond.") after its size. Retry, on the card
or in Transfer actions, runs at once without a confirmation.
The result arrives as a snackbar, "Retrying transfer" or "Couldn’t retry
transfer." with the reason, once per request, replacing any visible snackbar;
a 403 reads as nothing to retry. A failed retry leaves no blocking error
dialog, and a session rejection still signs out.

Tests: `SdkTransfersRepositoryTest`, `TransfersReducerTest`,
`MobileTransfersScreenTest`, `TransfersSessionFailureTest`,
`MobileTransferRetryProofTest` (opt-in device proof; see
[Harness](./harness.md#transfer-retry-proof)).

## Transfer actions

Mobile only. Transfers groups its cards into Active, Needs attention (failed)
and Completed (seeding and finished), each in put.io's order. A finished or
seeding card opens its file on tap, through the same resolution as before;
tapping any other card, or long-pressing any card, opens Transfer actions:

- **Open folder** resolves the transfer's `save_parent_id` and opens that
  folder in Files; 0 opens the root.
- **Copy source link** shows only when put.io returns a `source` that is an
  HTTP(S) or magnet link. The clip is marked sensitive, since a source can
  carry a tracker passkey or a signed URL, and the app never prints or logs it.
- **Stop transfer**, for anything put.io can still cancel, confirms first.
- **Retry**, for a failed transfer, as on its card.
- **Open file** and **Remove from list**, for a finished one. Remove doesn't
  confirm: it asks put.io to clean that one transfer, and the card leaves only
  when put.io reports it deleted. The file stays in put.io.

The tab's overflow holds Refresh once the list has loaded, empty included, and
**Clear completed**, which confirms and then refreshes, while transfers are
listed. Transfers posts no notifications.

Tests: `TransfersReducerTest`, `TransfersControllerTest`,
`SdkTransfersRepositoryTest`, `MobileTransfersScreenTest`,
`MobileTransfersLayoutTest`.

## Transfers polling

While Transfers is shown, rows that are still running, or completed without a
file yet, refresh every 5 s; polling pauses while the screen is hidden. Up to 10
such rows are read by id, so a poll costs one small request per row however deep
the history is. More rows are read from 1,000-row list pages until all are
found. A row the API no longer has (404 by id, or absent from the list) leaves
the list. Any other failure keeps the rows for the next poll; only a rejected
session surfaces. Measured CPU cost per poll:
[Android Transfers polling CPU benchmark](https://app.notion.com/p/3fdd546ba2f581fbaaa4ef35b3deb1c7)
(put.io Notion).

Tests: `SdkTransfersRepositoryTest`, `TransfersRefreshCostTest` (request and row
counts per poll against 10k and 50k histories), `TransfersControllerTest`,
`TransfersReducerTest` and `TransfersRefreshReducerTest` (failed polls keep rows;
only a rejected session surfaces),
`TransfersPollingCpuBenchmark` (opt-in device CPU time; see
[Harness](./harness.md#transfers-polling-cpu-benchmark)).
