package io.putdotio.android.search

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.toFilesFailure
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

internal interface RecentSearchStoreOwner : RecentSearchStore, Closeable {
    val failure: StateFlow<FilesFailure?>

    fun retry()
}

internal class AppConfigRecentSearchStore internal constructor(
    private val loadConfig: suspend () -> RecentSearchConfig,
    private val saveTerms: suspend (List<String>) -> Unit,
    parentScope: CoroutineScope,
) : RecentSearchStoreOwner {
    constructor(client: PutioClient, parentScope: CoroutineScope) : this(
        loadConfig = { client.appConfig.get().toRecentSearchConfig() },
        saveTerms = { client.appConfig.save(recentSearchConfigUpdate(it)) },
        parentScope = parentScope,
    )

    private val storeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + storeJob)
    private val commands = Channel<RecentSearchCommand>(Channel.UNLIMITED)
    private val mutableTerms = MutableStateFlow<List<SearchTerm>>(emptyList())
    private val mutableFailure = MutableStateFlow<FilesFailure?>(null)

    override val terms: StateFlow<List<SearchTerm>> = mutableTerms.asStateFlow()
    override val failure: StateFlow<FilesFailure?> = mutableFailure.asStateFlow()

    init {
        scope.launch {
            var config = loadConfigOrNull()
            config?.let(::applyLoadedConfig)
            val pendingEdits = ArrayDeque<RecentSearchMutation>()
            for (command in commands) {
                if (command is RecentSearchCommand.Edit) {
                    pendingEdits.addLast(command.mutation)
                }
                if (config == null) {
                    config = loadConfigOrNull()
                    config?.let(::applyLoadedConfig)
                }
                val loadedConfig = config ?: continue
                if (loadedConfig.enabled) {
                    while (pendingEdits.isNotEmpty() && apply(pendingEdits.first())) {
                        pendingEdits.removeFirst()
                    }
                } else {
                    pendingEdits.clear()
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
            loadConfig().also { mutableFailure.value = null }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            mutableFailure.value = error.toFilesFailure()
            null
        } catch (unexpected: Exception) {
            mutableFailure.value = FilesFailure.Unexpected(unexpected)
            null
        }

    private fun applyLoadedConfig(config: RecentSearchConfig) {
        mutableTerms.value = if (config.enabled) normalize(config.terms) else emptyList()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun apply(edit: RecentSearchMutation): Boolean {
        val previous = mutableTerms.value
        val next =
            when (edit) {
                is RecentSearchMutation.Record ->
                    (listOf(edit.term) + previous.filterNot { it == edit.term }).take(MAX_RECENT_SEARCHES)
                is RecentSearchMutation.Remove -> previous.filterNot { it == edit.term }
                RecentSearchMutation.Clear -> emptyList()
            }
        if (next == previous) return true
        mutableTerms.value = next
        return try {
            saveTerms(next.map(SearchTerm::value))
            mutableFailure.value = null
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            mutableTerms.value = previous
            mutableFailure.value = error.toFilesFailure()
            false
        } catch (unexpected: Exception) {
            mutableTerms.value = previous
            mutableFailure.value = FilesFailure.Unexpected(unexpected)
            false
        }
    }

    private fun normalize(values: List<String>): List<SearchTerm> =
        values
            .mapNotNull { value -> value.trim().takeIf(String::isNotBlank)?.let(::SearchTerm) }
            .distinct()
            .take(MAX_RECENT_SEARCHES)
}

internal data class RecentSearchConfig(
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

private sealed interface RecentSearchMutation {
    data class Record(val term: SearchTerm) : RecentSearchMutation
    data class Remove(val term: SearchTerm) : RecentSearchMutation
    data object Clear : RecentSearchMutation
}

private sealed interface RecentSearchCommand {
    data class Edit(val mutation: RecentSearchMutation) : RecentSearchCommand

    data object Retry : RecentSearchCommand
}

internal const val SEARCH_HISTORY_KEY = "searchHistory"
internal const val SEARCH_HISTORY_ENABLED_KEY = "searchHistoryEnabled"
internal const val MAX_RECENT_SEARCHES = 5
