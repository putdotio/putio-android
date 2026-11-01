package io.putdotio.android.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.MobilePlaybackService

internal class MobilePlaybackViewModel(
    target: PlaybackTarget,
    repository: PlaybackRepository,
) : ViewModel() {
    val controller = PlaybackController(
        target,
        repository,
        viewModelScope,
        startup = if (target.mediaType == PlaybackMediaType.AUDIO) {
            PlaybackStartup.AttachAudioSession
        } else {
            PlaybackStartup.Resolve
        },
        analytics = target.mediaType == PlaybackMediaType.VIDEO,
    )

    val analytics: PlaybackAnalyticsReporter =
        if (target.mediaType == PlaybackMediaType.AUDIO) {
            MobilePlaybackService.analytics.follow(controller, viewModelScope)
        } else {
            controller.analyticsReporter
        }

    override fun onCleared() {
        controller.close()
    }
}

internal fun mobilePlaybackViewModelFactory(
    target: PlaybackTarget,
    repository: PlaybackRepository,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            MobilePlaybackViewModel(target, repository)
        }
    }
