package io.putdotio.android.search

import io.putdotio.android.PutioFailure
import io.putdotio.android.toPutioFailure
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.config.AppConfigUpdate
import io.putdotio.sdk.errors.PutioException
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

public interface RecentSearchStoreOwner : RecentSearchStore, Closeable {
    public val failure: StateFlow<PutioFailure?>

    public fun retry()
}

public class AppConfigRecentSearchStore(
    private val loadConfig: suspend () -> RecentSearchConfig,
    saveTerms: suspend (List<String>) -> Unit,
    saveEnabled: suspend (Boolean) -> Unit,
    parentScope: CoroutineScope,
) : RecentSearchStoreOwner {
    public constructor(client: PutioClient, parentScope: CoroutineScope) : this(
        loadConfig = { client.appConfig.get().toRecentSearchConfig() },
        saveTerms = { client.appConfig.save(recentSearchConfigUpdate(it)) },
        saveEnabled = { client.appConfig.save(recentSearchEnabledConfigUpdate(it)) },
        parentScope = parentScope,
    )

    private val storeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + storeJob)
    private val commands = Channel<RecentSearchCommand>(Channel.UNLIMITED)
    private val writes = RecentSearchWrites(saveTerms, saveEnabled)

    override val terms: StateFlow<List<SearchTerm>> = writes.terms.asStateFlow()
    override val enabled: StateFlow<Boolean?> = writes.enabled.asStateFlow()
    override val failure: StateFlow<PutioFailure?> = writes.failure.asStateFlow()

    init {
        scope.launch {
            var config = loadConfigOrNull()
            config?.let(writes::load)
            val pendingEdits = ArrayDeque<RecentSearchMutation>()
            for (command in commands) {
                if (command is RecentSearchCommand.Edit) {
                    pendingEdits.addLast(command.mutation)
                }
                if (config == null) {
                    config = loadConfigOrNull()
                    config?.let(writes::load)
                }
                if (config == null) continue
                while (pendingEdits.isNotEmpty() && apply(pendingEdits.first())) {
                    pendingEdits.removeFirst()
                }
            }
        }
    }

    override fun record(term: SearchTerm) {
        commands.trySend(RecentSearchCommand.Edit(RecentSearchMutation.Record(term)))
    }

    override fun remove(term: SearchTerm) {
        commands.trySend(RecentSearchCommand.Edit(RecentSearchMutation.Remove(term)))
    }

    override fun clear() {
        commands.trySend(RecentSearchCommand.Edit(RecentSearchMutation.Clear))
    }

    override fun setEnabled(enabled: Boolean) {
        commands.trySend(RecentSearchCommand.Edit(RecentSearchMutation.SetEnabled(enabled)))
    }

    override fun retry() {
        commands.trySend(RecentSearchCommand.Retry)
    }

    override fun close() {
        commands.close()
        scope.cancel()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadConfigOrNull(): RecentSearchConfig? =
        try {
            loadConfig().also { writes.failure.value = null }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            writes.failure.value = error.toPutioFailure()
            null
        } catch (unexpected: Exception) {
            writes.failure.value = PutioFailure.Unexpected(unexpected)
            null
        }

    private suspend fun reload(): Boolean = loadConfigOrNull()?.also(writes::load) != null

    private suspend fun apply(edit: RecentSearchMutation): Boolean {
        val terms = writes.storedTerms
        return when (edit) {
            // Another client may have changed the history since it loaded; turning it off
            // must clear what the server holds now, and turning it on must keep it.
            is RecentSearchMutation.SetEnabled -> reload() && writes.writeEnabled(edit.enabled)
            is RecentSearchMutation.Record ->
                writes.editTerms((listOf(edit.term) + terms.filterNot { it == edit.term }).take(MAX_RECENT_SEARCHES))
            is RecentSearchMutation.Remove -> writes.editTerms(terms.filterNot { it == edit.term })
            RecentSearchMutation.Clear -> writes.editTerms(emptyList())
        }
    }
}

/**
 * The account's recent searches as far as the store knows, published to its flows, and the
 * writes that change them, each rolled back if the server refuses it. Only the store's
 * command loop calls in.
 */
private class RecentSearchWrites(
    private val saveTerms: suspend (List<String>) -> Unit,
    private val saveEnabled: suspend (Boolean) -> Unit,
) {
    private var stored = StoredRecentSearches(enabled = true, terms = emptyList())
    val terms = MutableStateFlow<List<SearchTerm>>(emptyList())
    val enabled = MutableStateFlow<Boolean?>(null)
    val failure = MutableStateFlow<PutioFailure?>(null)
    val storedTerms: List<SearchTerm> get() = stored.terms

    fun load(config: RecentSearchConfig) {
        val loaded =
            config.terms
                .mapNotNull { value -> value.trim().takeIf(String::isNotBlank)?.let(::SearchTerm) }
                .distinct()
                .take(MAX_RECENT_SEARCHES)
        publish(StoredRecentSearches(config.enabled, loaded))
    }

    /** Nothing is recorded or edited while the account has history turned off. */
    suspend fun editTerms(terms: List<SearchTerm>): Boolean = !stored.enabled || writeTerms(terms)

    suspend fun writeEnabled(enabled: Boolean): Boolean =
        when {
            enabled == stored.enabled -> true
            // Turning history off clears the stored terms first, as tv-native does.
            !enabled && !writeTerms(emptyList()) -> false
            else -> write(stored.copy(enabled = enabled)) { saveEnabled(enabled) }
        }

    private suspend fun writeTerms(terms: List<SearchTerm>): Boolean =
        terms == stored.terms || write(stored.copy(terms = terms)) { saveTerms(terms.map(SearchTerm::value)) }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun write(
        next: StoredRecentSearches,
        save: suspend () -> Unit,
    ): Boolean {
        val previous = stored
        publish(next)
        return try {
            save()
            failure.value = null
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            publish(previous)
            failure.value = error.toPutioFailure()
            false
        } catch (unexpected: Exception) {
            publish(previous)
            failure.value = PutioFailure.Unexpected(unexpected)
            false
        }
    }

    private fun publish(next: StoredRecentSearches) {
        stored = next
        // A disabled history keeps whatever the server holds out of sight.
        terms.value = if (next.enabled) next.terms else emptyList()
        enabled.value = next.enabled
    }
}

public data class RecentSearchConfig(
    val enabled: Boolean,
    val terms: List<String>,
)

internal fun AppConfig.toRecentSearchConfig(): RecentSearchConfig =
    RecentSearchConfig(
        enabled =
            (this[SEARCH_HISTORY_ENABLED_KEY] as? JsonPrimitive)
                ?.takeUnless(JsonPrimitive::isString)
                ?.booleanOrNull
                ?: true,
        terms =
            (this[SEARCH_HISTORY_KEY] as? JsonArray)
                ?.mapNotNull { value ->
                    (value as? JsonPrimitive)
                        ?.takeIf(JsonPrimitive::isString)
                        ?.contentOrNull
                }
                .orEmpty(),
    )

internal fun recentSearchConfigUpdate(terms: List<String>): AppConfigUpdate =
    AppConfigUpdate(
        key = SEARCH_HISTORY_KEY,
        value = JsonArray(terms.map(::JsonPrimitive)),
    )

internal fun recentSearchEnabledConfigUpdate(enabled: Boolean): AppConfigUpdate =
    AppConfigUpdate(key = SEARCH_HISTORY_ENABLED_KEY, value = JsonPrimitive(enabled))

private data class StoredRecentSearches(
    val enabled: Boolean,
    val terms: List<SearchTerm>,
)

private sealed interface RecentSearchMutation {
    data class Record(val term: SearchTerm) : RecentSearchMutation
    data class Remove(val term: SearchTerm) : RecentSearchMutation
    data object Clear : RecentSearchMutation
    data class SetEnabled(val enabled: Boolean) : RecentSearchMutation
}

private sealed interface RecentSearchCommand {
    data class Edit(val mutation: RecentSearchMutation) : RecentSearchCommand

    data object Retry : RecentSearchCommand
}

public const val SEARCH_HISTORY_KEY: String = "searchHistory"
public const val SEARCH_HISTORY_ENABLED_KEY: String = "searchHistoryEnabled"
internal const val MAX_RECENT_SEARCHES = 5
