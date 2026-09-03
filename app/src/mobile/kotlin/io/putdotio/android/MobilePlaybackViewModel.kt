package io.putdotio.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackTarget

internal class MobilePlaybackViewModel(
    target: PlaybackTarget,
    repository: PlaybackRepository,
) : ViewModel() {
    val controller = PlaybackController(target, repository, viewModelScope)

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
