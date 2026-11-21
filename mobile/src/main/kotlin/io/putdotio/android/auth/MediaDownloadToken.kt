package io.putdotio.android.auth

import io.putdotio.android.files.loadMediaAccount
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountDownloadToken
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The signed-in account's download token, the only credential media requests carry. It is loaded once per
 * session and kept in memory only; [reset] forgets it when the session changes, [refuse] when put.io rejects it.
 * [load] returns null without a session and throws the SDK's failure when the account cannot be read.
 */
internal class MediaDownloadToken(private val load: suspend () -> AccountDownloadToken?) {
    private val mutex = Mutex()
    private val session = AtomicLong()

    @Volatile
    private var cached: AccountDownloadToken? = null

    suspend fun get(): AccountDownloadToken? = mutex.withLock {
        cached ?: session.get().let { loadedFor -> load()?.also { if (session.get() == loadedFor) cached = it } }
    }

    fun reset() {
        session.incrementAndGet()
        cached = null
    }

    fun refuse(token: AccountDownloadToken) {
        if (cached == token) reset()
    }

    companion object {
        fun from(client: PutioClient): MediaDownloadToken =
            MediaDownloadToken { client.config.accessToken?.let { client.loadMediaAccount().downloadToken } }
    }
}
