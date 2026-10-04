package io.putdotio.android.tv.watchnext

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.isNotFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one signed-in session tells the Watch Next row. */
internal interface TvWatchNextRecorder {
    /** put.io saved [positionSeconds] for [media], from playback or the watched toggle. */
    fun positionSaved(media: TvWatchNextMedia, positionSeconds: Double)

    /** put.io no longer has the file. */
    fun fileGone(fileId: Long)

    /**
     * Reads each of the user's cards' files again, once per process and user: a file put.io no
     * longer has leaves, and a position saved on another device moves the card or, when
     * finished, removes it. Returns the 401 that ended the pass, which is the session's verdict;
     * other failures keep the card.
     */
    suspend fun reconcile(
        resolve: suspend (FilesItemId) -> PutioResult<FilesItem>,
    ): PutioFailure.AuthenticationRequired?

    companion object {
        val None: TvWatchNextRecorder = object : TvWatchNextRecorder {
            override fun positionSaved(media: TvWatchNextMedia, positionSeconds: Double) = Unit

            override fun fileGone(fileId: Long) = Unit

            override suspend fun reconcile(resolve: suspend (FilesItemId) -> PutioResult<FilesItem>) = null
        }
    }
}

/**
 * The app's cards in the launcher's Watch Next row, process-wide. A card belongs to the signed-in
 * user who played the video on this TV; a write from a session that is no longer signed in is
 * dropped, signing out, a rejected session and a quiet sign-out ([quietSignOuts]) remove every
 * card, and signing in removes any other user's. Provider work runs one operation at a time, in
 * order.
 */
internal class TvWatchNext(
    private val store: TvWatchNextStore,
    private val authState: StateFlow<TvAuthState>,
    /** [io.putdotio.android.tv.auth.TvAuthController.quietSignOuts]. */
    quietSignOuts: Flow<Int>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /** Users whose cards this process has reconciled; guarded by [lock]. */
    private val reconciledUsers = mutableSetOf<Long>()

    init {
        scope.launch {
            authState.mapNotNull(::ownerOf).distinctUntilChanged().collect { owner ->
                lock.withLock { removeAllExcept(owner.userId) }
            }
        }
        // A quiet sign-out lands in Initializing, where every process also starts.
        scope.launch {
            quietSignOuts.filter { it > 0 }.collect { lock.withLock { removeAllExcept(null) } }
        }
    }

    fun recorder(userId: Long, sessionId: TvAuthSessionId): TvWatchNextRecorder = SessionRecorder(userId, sessionId)

    private inner class SessionRecorder(
        private val userId: Long,
        private val sessionId: TvAuthSessionId,
    ) : TvWatchNextRecorder {
        override fun positionSaved(media: TvWatchNextMedia, positionSeconds: Double) {
            val change = watchNextChange(media, positionSeconds)
            if (change == TvWatchNextChange.None) return
            scope.launch { whileCurrent { apply(change, engagedAtMillis = clock()) } }
        }

        override fun fileGone(fileId: Long) {
            scope.launch { whileCurrent { apply(TvWatchNextChange.Remove(fileId), engagedAtMillis = null) } }
        }

        override suspend fun reconcile(
            resolve: suspend (FilesItemId) -> PutioResult<FilesItem>,
        ): PutioFailure.AuthenticationRequired? {
            val cards = whileCurrent {
                if (reconciledUsers.add(userId)) store.programs().filter { it.owner?.userId == userId } else null
            }.orEmpty()
            for (card in cards) {
                val verdict = reconcile(card, resolve(FilesItemId(card.program.fileId)))
                if (verdict != null) return verdict
            }
            return null
        }

        private suspend fun reconcile(
            card: TvStoredWatchNextProgram,
            result: PutioResult<FilesItem>,
        ): PutioFailure.AuthenticationRequired? {
            val change = when (result) {
                is PutioResult.Success ->
                    watchNextChange(card.media(result.value), result.value.playback?.startFromSeconds ?: 0.0)
                        // Moves only a card whose position changed elsewhere; when the viewer
                        // engaged with it stays as it was.
                        .takeUnless { it is TvWatchNextChange.Publish && it.program == card.program }
                is PutioResult.Failure -> when {
                    result.failure is PutioFailure.AuthenticationRequired ->
                        return result.failure as PutioFailure.AuthenticationRequired
                    result.failure.isNotFound -> TvWatchNextChange.Remove(card.program.fileId)
                    else -> null
                }
            }
            change?.let { whileCurrent { apply(it, engagedAtMillis = card.engagedAtMillis) } }
            return null
        }

        private suspend fun <T> whileCurrent(block: suspend () -> T): T? =
            lock.withLock {
                val current = authState.value as? TvAuthState.SignedIn
                if (current?.sessionId == sessionId && current.account.userId == userId) block() else null
            }

        private suspend fun apply(change: TvWatchNextChange, engagedAtMillis: Long?) {
            when (change) {
                is TvWatchNextChange.Publish -> publish(change.program, engagedAtMillis ?: clock())
                is TvWatchNextChange.Remove -> store.programs()
                    .filter { it.owner == TvWatchNextOwner(userId, change.fileId) }
                    .forEach { store.delete(it.rowId) }
                TvWatchNextChange.None -> Unit
            }
        }

        private suspend fun publish(program: TvWatchNextProgram, engagedAtMillis: Long) {
            val owner = TvWatchNextOwner(userId, program.fileId)
            val cards = store.programs()
            val same = cards.filter { it.owner == owner }
            same.drop(1).forEach { store.delete(it.rowId) }
            val existing = same.firstOrNull()
            if (existing != null) {
                store.update(existing.rowId, owner, program, engagedAtMillis)
                return
            }
            // The least recently watched cards make room, so reconciling stays a bounded read.
            cards.filter { it.owner?.userId == userId }
                .sortedBy { it.engagedAtMillis }
                .let { mine -> mine.take((mine.size - MAX_CARDS + 1).coerceAtLeast(0)) }
                .forEach { store.delete(it.rowId) }
            store.insert(owner, program, engagedAtMillis)
        }
    }

    private suspend fun removeAllExcept(userId: Long?) {
        store.programs().filter { userId == null || it.owner?.userId != userId }.forEach { store.delete(it.rowId) }
    }

    private data class Owner(val userId: Long?)

    private fun ownerOf(state: TvAuthState): Owner? =
        when (state) {
            is TvAuthState.SignedIn -> Owner(state.account.userId)
            is TvAuthState.Linking, TvAuthState.SigningOut -> Owner(null)
            // Restoring or offline: whose session this is is not known yet.
            TvAuthState.Initializing,
            TvAuthState.RestoringSession,
            is TvAuthState.ValidatingSession,
            is TvAuthState.ValidationUnavailable,
            -> null
        }
}

private fun TvStoredWatchNextProgram.media(file: FilesItem): TvWatchNextMedia =
    TvWatchNextMedia(
        fileId = file.id.value,
        title = file.name,
        // The card keeps the duration playback knew; a single-file read carries none.
        durationSeconds = file.playback?.durationSeconds
            ?: program.durationMillis.takeIf { it > 0L }?.let { it / MILLIS_PER_SECOND },
        posterUrl = file.screenshotUrl ?: program.posterUrl,
        isVideo = true,
    )

/** Bounds the cards one user keeps, so each sign-in's reconcile reads at most this many files. */
private const val MAX_CARDS = 20
private const val MILLIS_PER_SECOND = 1_000.0
