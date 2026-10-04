# Behaviour

Product behaviour the Android app implements that the harness proves. Each
section names the JVM tests that pin the rule; [Harness](./harness.md) owns the
device lanes and live proof.

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
`MobileAuthControllerTest`, `MobileAuthControllerSessionTest`, `MobileShellTest`.

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
the same application id, so its data survives the update (#244). tv-native kept
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
read, and the next read writes again. tv-native's `bufferSize` is not carried over: Android TV
has no buffer setting and always buffers as tv-native's default `medium` (see
[TV playback](#tv-playback)); the key is left untouched in `/config`.

Tests: `TvAuthControllerTest`, `AsyncStorageLegacyTvSessionTest` (a fixture
AsyncStorage database), `TvNativeConfigMigrationTest` (fixture `/config` blobs).

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
Make a copy, as web and iOS do; the shared root and each friend's folder
offer nothing, so they have no actions button. The Files move picker
chooses the destination among the viewer's own folders, root included, and
opens where Move's does (see [Move and copy target folder](#move-and-copy-target-folder)).
put.io copies in the background
(`POST /v2/sharing/clone`), so a line under the folder shows the copy until
it is dismissed and stays across folder navigation. The app checks the copy
every 1.5 s, as web does, for up to 200 checks. A finished copy reloads the
destination if it is already open in the stack. A failed copy shows put.io's
reason under the [refused-request](#refused-requests) rules, or web's copy for
the concurrency and too-many-files limits. A copy
whose start gets no clear answer (a lost or unreadable response, or a server
fault), whose check fails, or that is still running after the last check, is
reported as unconfirmed, because it may still land. Only one copy runs at a
time. Rotation keeps the copy and its checks; process death discards them, so
the line is gone and another copy can start while put.io may still run the
first. TV doesn't offer Make a copy, because tv-native and tv-vite don't.

Tests: `SdkFilesRepositoryTest`, `MobileFilesScreenTest`, `TvFilesRowActionsTest`,
`FilesCopyTest`, `MobileFilesCopyTest`, `MobilePublicLinksTest`, `MobileSharedItemsProofTest` (opt-in
synthetic device proof; see [Harness](./harness.md#shared-with-me-items-proof)).

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
account. Android TV has no Move picker.

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
`MobileSearchHistoryScreenTest`, `MobileFilesMoveTest`, `TvFilesScreenTest`,
`TvFilesPagingTest`, `TvFilesRowActionsTest`,
`FilesDeleteRecoveryUiProofTest` (opt-in synthetic device proof),
`MobileFilesTrashPagingProofTest` (opt-in device proof; see
[Harness](./harness.md#files-trash-and-paging-proof)).

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
still reads as watched with its progress bar. The duration comes from the
listing. When a video with a saved position opens without one (search, History,
product links, a row without `video_metadata`), resolution lists the file to
read it and keeps a duration it finds for the prompt and later retries. A
failed read leaves the duration unknown: mobile then offers the saved position,
and TV continues from it without asking. Audio never needs the read. The retained
controller keeps that decision across Activity recreation. Live audio attachment
and retained player-error recovery bypass the prompt. Start over starts locally
at zero; it does not immediately reset the server position.

One observer belongs to each actual player: the private video owner or the audio
service on mobile, the player screen on TV. Screens and notification controllers do not duplicate audio reporting.
The observer samples advancing playback every 15 seconds and captures positive
positions on pause, stop, end, error, item replacement and owner exit. Buffering
and same-item seek events do not send immediate writes. The
application writer deduplicates positions within the same second, permits one
request in flight and one latest pending snapshot, and times out a request after
15 seconds. A position offered while a request is in flight is written after it,
so the final one wins. Under slow requests or rapid file switches, newer
snapshots can replace intermediate queued exit positions, and reopening a file
drops its previous opening's queued snapshot. Failed writes keep their typed
cause and wait for a new position; there is no immediate retry loop or
completion reset: a finished item's real final position is written.

Reporting requires an app-issued item lease, the same signed-in session, and a
confirmed enabled resume setting. Pending/failed resume-setting writes suspend
reporting; unrelated setting writes do not. A downloaded file whose settings
never loaded this session reports under the last confirmed setting, and keeps a
position put.io cannot take ([offline resume](#downloads-and-offline-playback)). Session exit or disabled/unconfirmed
policy cancels requests and discards pending positions without flushing. The
application owns this policy subscription so task removal does not stop audio
updates. A source resolved with resume disabled receives no reporting lease.
An authoritative authentication failure from a position write expires only the
session that issued it. Rejection runs outside the cancellable reporting job,
and checks the session identity again under the authentication controller's lock.

Tests: `PlaybackReducerTest`, `PlaybackControllerTest`, `MobilePlayerScreenTest`
(the prompt, recreation and Start over), `TvPlayerResumeTest` (Back on the
prompt), `PlaybackPositionWriterTest`, `PlaybackPositionObserverTest`,
`SdkPlaybackRepositoryTest`, `MobilePlaybackReportingTest`.

## MP4 conversion

Both surfaces resolve a video that needs MP4 conversion to the same shared
conversion state. The SDK resolves a conversion only for a video the server marks
`need_convert`, and its MP4 status reads not available until a conversion is
requested ([live check](https://github.com/putdotio/putio-android/pull/222#issuecomment-5904428342):
a not-available video converted and played).
Opening such a video starts its conversion through the SDK's
`startMp4Conversion` without a tap, as putio-web, tv-native, tv-vite and iOS do,
and the interstitial says the conversion has started. Only the first conversion
status read of an opening can start one, including one that follows a Retry after
the opening's resolve failed: a first read of queued, running, completed or failed
is only read, so opening the file again while it converts requests nothing, and
after that first read, polls, Check again and retries never start one, even across
a failed read.
The SDK's consumer guide asks for the same
([putio-sdk-kotlin#59](https://github.com/putdotio/putio-sdk-kotlin/pull/59),
[#236](https://github.com/putdotio/putio-android/issues/236)).
A queued or running conversion is read again every 3 s while the app is in the
foreground, keeping the interstitial up, and plays on its own once it resolves.
Completed is read once more at once; if it stays completed, Check again waits for
the viewer, as does an unknown status. Convert again after a failed one starts it
again. A start the server accepted reads as queued once, so a status read that
has not caught up polls again; if the status still reads not available after
that, the video cannot be converted and offers only Back.

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
the stream reports its duration, using the listing's duration until then. Center, Enter or the remote's play/pause key toggles playback, and
leaving the app pauses it and shows the paused controls. If the activity is
recreated (a remote or keyboard connecting, a locale change), playback
continues paused from where it stopped.

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
in quarter steps). Only the choices scroll, so the title stays in view (#224).
A track shows its name, else its language; MP4 subtitles read `LANGUAGE - name`,
as the RN player relabelled its sidecar tracks. Speed and audio start over with
each file. Choices survive a rebuilt player.

Subtitles start from the account's settings, as on mobile: gone with
`hide_subtitles`, forced tracks only with `dont_autoselect_subtitles`,
otherwise selected automatically. Hidden means gone, as in every reference
player (#237): playback asks put.io for no subtitles (`max_subtitle_count=0`
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
them early; settings that arrive later still decide until the viewer picks
(#229). Once the viewer picks, the pick decides (#45), short of hiding: a
picked track is found again after track changes, and Off keeps the text type
disabled and draws nothing, whatever cues the renderer last delivered, across
seeks and track changes. Cues, bitmap (PGS) ones included, draw with the system
caption style through the same overlay as mobile.

Back dismisses the topmost layer (#9): an open picker first (no change; focus
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
uses (#248): the folder's videos in name order, stopping after the last without
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
one Add (put.io's multi-add limit). One link uses `/transfers/add`, so put.io's
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

Tests: `MobileShareIntentsTest`, `MobileTransferDraftTest`,
`MainActivityShareTest`, `MobileTransfersAddSheetTest`, `TransfersReducerTest`,
`SdkTransfersRepositoryTest`, `FilesMoveDestinationControllerTest`.

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
option for videos (`utm_campaign=party`) is not ported. TV has no public links.

Tests: `PublicLinksControllerTest`, `SdkPublicLinksRepositoryTest`,
`MobilePublicLinksTest`, `MobilePublicLinksProofTest` (opt-in synthetic device
proof; see [Harness](./harness.md#public-links-proof)).

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
`putio://downloads/<id>`, which a download notification opens to show that
row's actions. `putio://auth`
stays with the OAuth receiver. A link is consumed once per Activity intent, any
put.io URI is removed from the retained intent, and routing waits for sign-in;
an unrouted link survives process death as its token-free `putio://` form. A file
id resolves through the item resolver, opens as under
[History opens](#timestamps-and-history-opens) with Back staying in Files, and
reuses the navigation-failure dialog.

Tests: `MobileDeepLinksTest`, `MainActivityDeepLinkTest`.

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
reach its listing.
Back from it returns to Search (mobile shows History there too), History on TV,
or Transfers, whose file opens the same way without playing, with TV focus back
on the chosen row; after a product link it returns to the Files location. A later outside open replaces the earlier
one rather than stacking, and Files refuses it while a move or deletion settles.

Tests: `PutioTimestampTest`, `MobileSearchHistoryViewModelTest`,
`MobileSearchHistoryScreenTest`, `MobileFilesScreenTest`, `TvSessionViewModelTest`,
`FilesBrowserReducerTest`, `FilesRevealReducerTest`, `FilesBrowserControllerTest`,
`MobileShellTest` (`MobileShellExternalOpenTest`), `TvPickReturnFocusTest`.

## History events

Mobile gives each event iOS's copy (`HistoryTableViewCell`): shared files and
completed transfers show their name and kind, uploads their name; transfer
errors, RSS deletions and paused RSS filters read as a sentence naming the
transfer, file or filter. An event with no name, or of a type iOS has no copy
for, reads No title, never its raw API type. TV lists only shared files and
completed transfers, as tv-native does, including a share whose file is gone; a
page with neither reads on to the next, so dropped events neither end the list
nor leave it empty while older events wait.
With history off, both surfaces name the Keep account history switch in Account.

Tests: `SdkHistoryRepositoryTest`, `MobileSearchHistoryScreenTest`,
`TvSessionViewModelTest`, `TvHistoryScreenTest`.

## Storage quota

The account header labels the quota as web and iOS do: X of Y free when the
account's `show_optimistic_usage` is on, X of Y used otherwise. The bar fills
with what is used either way.

Tests: `PutioAuthSessionGatewayTest`, `PutioTvSessionGatewayTest`,
`MobileAccountScreenTest`, `TvAccountScreenTest`.

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
rule (#237). Media3 owns bytes, resume and the foreground notification, which
shows a count and progress only. The app's index in private SharedPreferences
holds file id, name, type, rendition, status, queue time, a pending-delete mark,
the confirmed subtitle setting, the listing's saved position and whether the
row was rebuilt per user; it never holds a URL.

Requests carry the token-free API URL and the file's name, and a resolving
data source adds the session header for `api.put.io` hosts. The app builds that URL itself: the
SDK's URL builders always embed `oauth_token`, which Media3 would persist; it
takes the subtitle count from the SDK's `HLS_ALL_SUBTITLES`. Playlist bodies from the server embed
`oauth_token` in their child URLs; the cache key factory strips that query and
prefixes the owning user id, so the Media3 index stays token-free and two
accounts never share cached bytes. There is one `DownloadManager`; request ids
are `userId:fileId`, each request downloads under its owner's keys, and a
sign-out parks that user's transfers with a stop reason until the owner signs in
again. Playback reads through the same cache with a null write sink, so
streaming never fills the download directory. Offline playback replays the URL
Media3 recorded for the request, so a download keeps playing after the app
changes the URL it builds for new ones. Closing the engine cancels its start-up
reconcile, so a sign-out right after sign-in cannot un-park the transfers.

**Queue.** Media3 runs up to the device-wide concurrency limit at once, 1 to 4,
default 3 (iOS main's choices), and starts the rest oldest first by its own
start time, which it persists. The Downloads screen shows the limit and lets the
viewer change it; a lower limit stops the extra transfers, keeping their bytes,
and they rejoin the line. Its Queue section lists unfinished rows in Media3's
start order with each waiting row's place (`Queued · #1 in line`), then Needs
attention (failed and missing rows), then On this device. A retry or Download
again joins the back of the line. A row shows a percentage only when Media3
reports one over a known denominator, the original's content length or the HLS
segment count once the playlists are read; otherwise it shows bytes and an
indeterminate bar. Only transitions reach the index; while the Downloads screen
is started, the controller reads live bytes once a second into memory.

**States and recovery.** A row is queued, downloading, paused (waiting for a
network, or for the system to stop reporting low storage; both resume by
themselves), failed (retryable), on this device, missing, or deleting. Transfers
that fail with `ENOSPC` read as not enough storage, with Retry. On start the
engine reconciles Media3's index into the rows, so a transfer that finished,
failed or paused while the UI was dead reads that way after relaunch. A row
Media3 has no record of is never dropped silently: a finished one reads missing,
a failed one keeps its reason, and an unfinished one goes back in the queue. A
finished row whose bytes are gone from the cache, found at reconcile or when
offline playback opens it, reads missing: it stops counting as on this device
in Files and Downloads, streams instead, and offers Download again. The
reverse, a download of this user that Media3 lists but the index does not,
comes back as a row: video for an HLS request, audio for an original, the name
the request carried (requests carry the file name from #53 on) or "Downloaded
file", the subtitle setting its URL asked for, and Media3's state. It plays and
deletes like any row. The index reads rows one by one: a row this build cannot
read, such as a newer build's, costs only that row and is written back
untouched, and a document that is not a list is copied aside under
`<key>.unreadable` before the next write. Bytes leave the device only through a
confirmed delete.

**Removal.** Deleting removes only local copies; Downloads has no path to the
put.io originals. One row's sheet or a multi-select asks first, saying the
files stay in the account. Select opens an empty selection and Delete waits for
a row; a long press opens one with its row; Select all takes every row. Confirmed
rows are marked before Media3 deletes their bytes, stop counting as available
at once, and leave once Media3 confirms; a process death in between finishes
the delete on the next start.

**Notifications.** When a download finishes or fails, with or without the app
open, a notification names the file and opens its row in Downloads; the lock
screen sees the outcome without the name. Each file has one slot, cleared by a
retry or a delete. Only the account signed in now is notified, so one account's
outcome never appears while another is signed in. Nothing is posted while the
app's notifications or the Finished downloads channel are off, or, on Android
13 and later, while POST_NOTIFICATIONS is not granted. The first download asks for that permission
once; afterwards Downloads shows a notice whose Turn on asks again while the
system allows it and otherwise opens the app's notification settings.

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
copies, concurrency, progress polling while shown), `MobileDownloadsScreenTest`
(queue order and places, the limit, the notification notice, selection,
shown and hidden events), `MobileShellDownloadsTest` (bulk delete never reaches
put.io, notification links, the subtitle setting a Files download carries),
`MobileDownloadStoreTest` (unreadable rows kept), `MobileDownloadEngineTest` (queue order and limit
across process recreation, recovery of every row state, rows rebuilt for
downloads an unreadable index lost, low-storage pause, reconcile, sign-out parking, account
isolation, close before reconcile, in-memory progress and its denominator, the
recorded request URL and the local-copy check against a real Media3 manager and
cache), `MobileDownloadNotificationsTest` (granted, denied and disabled states,
signed-in account only), `OfflinePlaybackPositionsTest` (sync rules, the
player's write racing a pass, refused files), `MobilePlaybackReportingTest`
(cold offline start), `UserScopedCacheKeysTest` (token-free, user-scoped cache keys),
`OfflinePlaybackRepositoryTest`, `MobileDeepLinksTest`.

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
and bottom (4% and 2% in `@putdotio/design` 3.3.0). The fractions are generated
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
`TvPlaybackStatesTest`, `MobileRefusedRequestProofTest` (opt-in device proof;
see [Harness](./harness.md#refused-request-proof)).

## Transfer failures and retry

Mobile only; TV has no Transfers screen. A transfer row shows put.io's
`error_message` as the API sends it, trimmed; a failed row without one shows
"This transfer needs attention." Retry runs at once without a confirmation.
The result arrives as a snackbar, "Retrying transfer" or "Couldn’t retry
transfer." with the reason, once per request, replacing any visible snackbar;
a 403 reads as nothing to retry. A failed retry leaves no blocking error
dialog, and a session rejection still signs out.

Tests: `SdkTransfersRepositoryTest`, `TransfersReducerTest`,
`MobileTransfersScreenTest`, `TransfersSessionFailureTest`,
`MobileTransferRetryProofTest` (opt-in device proof; see
[Harness](./harness.md#transfer-retry-proof)).

## Transfers polling

While Transfers is shown, rows that are still running, or completed without a
file yet, refresh every 5 s; polling pauses while the screen is hidden. Up to 10
such rows are read by id, so a poll costs one small request per row however
deep the history is. More rows are read from 1,000-row list pages until all are
found. A row the API no longer has (404 by id, or absent from the list) leaves
the list. Any other failure keeps the rows for the next poll; only a rejected
session surfaces.

CPU per poll, emulator numbers: `TransfersPollingCpuBenchmark` replayed the
same fake 10k and 50k histories on the `putio-phone` emulator (API 37,
arm64-v8a, debuggable build) and took the median thread CPU time of 15 polls
after 5 warmup polls. Before is the list walk this path replaced.

| History, polled rows                          | Before, CPU ms        | After, CPU ms |
| --------------------------------------------- | --------------------- | ------------- |
| 10k, 1 recent running                         | 27.3                  | 0.37          |
| 10k, 1 old running (position 9,500)           | 239                   | 0.36          |
| 10k, 1 deleted                                | 223                   | 0.65          |
| 10k, 3 recent + 1 old                         | 236                   | 0.59          |
| 10k, 11 recent (list walk both)               | 24.1                  | 31.2          |
| 50k, 1 recent running                         | 25.1                  | 0.38          |
| 50k, 1 running past the cap (position 20,000) | 228, reported missing | 0.33          |
| 50k, 1 deleted                                | 239                   | 0.68          |

The host was shared with other emulators, so absolute times vary by about 2x
between runs; a second run measured the 11-row list walk at 16.6 ms before and
16.5 ms after. At 12 polls a minute, one old running row cost about 2.9 s of
CPU per minute before and about 4 ms after. A physical phone measurement waits
on [#51](https://github.com/putdotio/putio-android/issues/51).

Tests: `SdkTransfersRepositoryTest`, `TransfersRefreshCostTest` (request and row
counts per poll against 10k and 50k histories), `TransfersControllerTest`,
`TransfersReducerTest` and `TransfersRefreshReducerTest` (failed polls keep rows;
only a rejected session surfaces),
`TransfersPollingCpuBenchmark` (opt-in device CPU time; see
[Harness](./harness.md#transfers-polling-cpu-benchmark)).
