# Behaviour

Product behaviour the Android app implements that the harness proves. Each
section names the JVM tests that pin the rule; [Harness](./harness.md) owns the
device lanes and live proof.

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
UI was dead reads On this device after relaunch.

Tests: `DownloadsControllerTest`, `MobileDownloadStoreTest`,
`OfflinePlaybackRepositoryTest`.
