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
`MobileAuthControllerTest`, `MobileShellTest`.

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
`MobileAuthControllerTest`, `TvAuthControllerTest`.

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

Tests: `TrashActionTest`, `TrashRestoreTest`, `TrashRepeatedRestoreTest`,
`FilesRestoreInvalidationTest`.

## Resume and position reporting

Fresh audio/video resolution with `use_start_from` enabled and a positive saved
position offers Resume or Start over before preparing the player. The retained
controller keeps that decision across Activity recreation. Live audio attachment
and retained player-error recovery bypass the prompt. Start over starts locally
at zero; it does not immediately reset the server position.

One observer belongs to each actual player: the private video owner or the audio
service. Screens and notification controllers do not duplicate audio reporting.
The observer samples advancing playback every 15 seconds and captures positive
positions on pause, stop, end, error, item replacement and owner exit. Buffering
and same-item seek events do not send immediate writes. The application writer
deduplicates positions within the same second, permits one request in flight and
one latest pending snapshot, and times out a request after 15 seconds. Under slow
requests or rapid file switches, newer snapshots can replace intermediate queued
exit positions. Failed writes keep their typed cause and wait for a new position;
there is no immediate retry loop or completion-percentage reset rule.

Reporting requires an app-issued item lease, the same signed-in session, and a
confirmed enabled resume setting. Pending/failed resume-setting writes suspend
reporting; unrelated setting writes do not. Session exit or disabled/unconfirmed
policy cancels requests and discards pending positions without flushing. The
application owns this policy subscription so task removal does not stop audio
updates. A source resolved with resume disabled receives no reporting lease.
An authoritative authentication failure from a position write expires only the
session that issued it. Rejection runs outside the cancellable reporting job,
and checks the session identity again under the authentication controller's lock.

Tests: `PlaybackPositionWriterTest`, `MobilePlayerPositionObserverTest`,
`MobilePlaybackReportingTest`, `MobileResumePlaybackDialogTest`.

## Share-in

The mobile launcher accepts `ACTION_SEND` with `text/plain`. A URL or magnet
opens an editable Add transfer sheet after sign-in; only Add submits it. Text
with one unambiguous supported link prefills that link. Ambiguous prose and
multiple links remain editable with guidance to choose one. A new share cannot
overwrite an existing draft without confirmation or interrupt a running
mutation. Input is limited to 16 KiB of UTF-8; oversized shares are rejected
without truncation. A rejected oversized edit keeps the previous draft but
blocks Add until the user edits it. After a successful Add, Transfers shows a
"Transfer added" snackbar once per request.

Share payloads and add-transfer drafts stay in Activity-owned memory. Rotation
retains them; process death discards them. Saved state contains only consumed
request metadata, and received share extras and ClipData are removed from the
retained Activity intent.

Tests: `MobileShareIntentsTest`, `MobileTransferDraftTest`,
`MainActivityShareTest`.

## Share-out

Share file (Files and Downloads action sheets, non-folder items only) starts a
foreground `dataSync` service that fetches the original file through the API
download endpoint with the session header, stores it under private
`files/shares/<fileId>/<name>`, and opens the system chooser with a
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

Tests: `MobileFileShareServiceTest` (payload shape, ready notification, service
stop rules, name sanitizer).

## Deep links

`https://{app.put.io,put.io,www.put.io}/{files,files/<id>,transfers,search,history,trash}`
(`autoVerify`; the `assetlinks.json` publication is a release-owner task, so
unverified installs still open through the app chooser or an explicit package)
and `putio://{files,transfers,search,history,trash,downloads}`. `putio://auth`
stays with the OAuth receiver. A link is consumed once per Activity intent, any
put.io URI is removed from the retained intent, and routing waits for sign-in;
an unrouted link survives process death as its token-free `putio://` form. A file
id resolves through the item resolver and reuses the navigation-failure dialog.

Tests: `MobileDeepLinksTest`, `MainActivityDeepLinkTest`.

## Timestamps and History opens

The API sends UTC datetimes without an offset (`2026-09-09T15:25:32`). Mobile
and TV read them, and offset stamps, through one parser, so History groups by
local day and Files rows keep their date; a stamp it cannot read shows raw.
Choosing a History row resolves its file in the signed-in session, and only the
latest choice opens: a second tap while the first resolves opens one folder.

Tests: `PutioTimestampTest`, `MobileSearchHistoryViewModelTest`,
`MobileSearchHistoryScreenTest`, `MobileFilesScreenTest`, `TvSessionViewModelTest`.

## Downloads and offline playback

Downloads use Media3's `DownloadService` and `SimpleCache` under the app's
internal files directory (`files/downloads/`), never external storage: cached
playlist bodies carry the server's token. A video download stores the HLS
rendition the player streams, subtitle renditions included; an audio download
stores the original file. Media3 owns bytes, resume and the foreground
notification, which shows a count and progress only. The app's index in private
SharedPreferences holds file id, name, type, rendition and status per user; it
never holds a URL.

Requests carry the token-free API URL, and a resolving data source adds the
session header for `api.put.io` hosts. Playlist bodies from the server embed
`oauth_token` in their child URLs; the cache key factory strips that query and
prefixes the owning user id, so the Media3 index stays token-free and two
accounts never share cached bytes. There is one `DownloadManager`; request ids
are `userId:fileId`, each request downloads under its owner's keys, and a
sign-out parks that user's transfers with a stop reason until the owner signs in
again. Playback reads through the same cache with a null write sink, so
streaming never fills the download directory. On start the engine reconciles
Media3's own index into the app's rows, so a transfer that completed while the
UI was dead reads On this device after relaunch. Closing the engine cancels that
reconcile, so a sign-out right after sign-in cannot un-park the transfers.
Media3 reports only state transitions to the app; while the Downloads screen is
started, the controller reads live bytes once a second into memory. Only
transitions reach the index.

Tests: `DownloadsControllerTest` (intents, progress polling while shown),
`MobileDownloadsScreenTest` (shown and hidden events), `MobileDownloadStoreTest`,
`MobileDownloadEngineTest` (reconcile, sign-out parking, account isolation,
close before reconcile, in-memory progress against a real Media3 manager),
`UserScopedCacheKeysTest` (token-free, user-scoped cache keys),
`OfflinePlaybackRepositoryTest`.

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

| History, polled rows | Before, CPU ms | After, CPU ms |
| --- | --- | --- |
| 10k, 1 recent running | 27.3 | 0.37 |
| 10k, 1 old running (position 9,500) | 239 | 0.36 |
| 10k, 1 deleted | 223 | 0.65 |
| 10k, 3 recent + 1 old | 236 | 0.59 |
| 10k, 11 recent (list walk both) | 24.1 | 31.2 |
| 50k, 1 recent running | 25.1 | 0.38 |
| 50k, 1 running past the cap (position 20,000) | 228, reported missing | 0.33 |
| 50k, 1 deleted | 239 | 0.68 |

The host was shared with other emulators, so absolute times vary by about 2x
between runs; a second run measured the 11-row list walk at 16.6 ms before and
16.5 ms after. At 12 polls a minute, one old running row cost about 2.9 s of
CPU per minute before and about 4 ms after. A physical phone measurement waits
on [#51](https://github.com/putdotio/putio-android/issues/51).

Tests: `SdkTransfersRepositoryTest`, `TransfersRefreshCostTest` (request and row
counts per poll against 10k and 50k histories), `TransfersControllerTest`,
`TransfersPollingCpuBenchmark` (opt-in device CPU time; see
[Harness](./harness.md#transfers-polling-cpu-benchmark)).
