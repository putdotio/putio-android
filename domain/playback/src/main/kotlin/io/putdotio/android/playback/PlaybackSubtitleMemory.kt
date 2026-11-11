package io.putdotio.android.playback

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.media3.common.C
import java.util.Locale

public sealed interface RememberedSubtitle {
    public data object Off : RememberedSubtitle

    public data class Language(val tag: String) : RememberedSubtitle
}

internal fun SubtitleSelection.toRememberedSubtitle(): RememberedSubtitle? =
    when (this) {
        SubtitleSelection.Off -> RememberedSubtitle.Off
        SubtitleSelection.Automatic -> null
        is SubtitleSelection.Track ->
            identity.language?.takeIf { languageName(it) != null }?.let(RememberedSubtitle::Language)
    }

public fun RememberedSubtitle.selectionFor(tracks: List<PlaybackSubtitleTrack>): SubtitleSelection? =
    when (this) {
        RememberedSubtitle.Off -> SubtitleSelection.Off
        is RememberedSubtitle.Language ->
            tracks
                .filter { it.identity.language?.sameLanguageAs(tag) == true }
                .sortedWith(
                    compareBy<PlaybackSubtitleTrack> { (it.identity.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 }
                        .thenBy { (it.identity.roleFlags and ACCESSORY_ROLES) != 0 }
                        .thenBy { it.identity.language?.sameTagAs(tag) != true }
                        .thenBy { !it.isServerDefault },
                )
                .firstOrNull()
                ?.let { SubtitleSelection.Track(it.identity) }
    }

private fun String.sameTagAs(tag: String): Boolean =
    Locale.forLanguageTag(this).toLanguageTag().equals(Locale.forLanguageTag(tag).toLanguageTag(), ignoreCase = true)

@Stable
public class SubtitleLanguageStore(private val context: Context?) {
    public var remembered: RememberedSubtitle? by mutableStateOf(context?.let(::storedSubtitle))
        private set

    public fun update(selection: SubtitleSelection) {
        val next = selection.toRememberedSubtitle()
        remembered = next
        context?.getSharedPreferences(PLAYER_PREFERENCES_NAME, Context.MODE_PRIVATE)?.edit {
            when (next) {
                null -> remove(LANGUAGE_KEY)
                RememberedSubtitle.Off -> putString(LANGUAGE_KEY, OFF_VALUE)
                is RememberedSubtitle.Language -> putString(LANGUAGE_KEY, next.tag)
            }
        }
    }

    private companion object {
        const val LANGUAGE_KEY = "subtitle_language"
        const val OFF_VALUE = "off"

        fun storedSubtitle(context: Context): RememberedSubtitle? =
            when (
                val stored = context.getSharedPreferences(PLAYER_PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .getString(LANGUAGE_KEY, null)
            ) {
                null -> null
                OFF_VALUE -> RememberedSubtitle.Off
                else -> stored.takeIf { languageName(it) != null }?.let(RememberedSubtitle::Language)
            }
    }
}

@Composable
public fun rememberSubtitleLanguageStore(): SubtitleLanguageStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { SubtitleLanguageStore(context) }
}
