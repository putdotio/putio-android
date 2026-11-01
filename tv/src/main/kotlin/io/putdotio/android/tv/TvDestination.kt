package io.putdotio.android.tv

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.putdotio.android.R
import io.putdotio.android.files.FilesOpenOrigin
import io.putdotio.android.design.R as DesignR

internal enum class TvDestination(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    Home(R.string.tv_destination_home, R.drawable.ic_ph_house, R.drawable.ic_ph_house_fill),
    Files(R.string.tv_destination_files, R.drawable.ic_ph_folder, DesignR.drawable.ic_ph_folder_fill),
    Search(R.string.tv_destination_search, R.drawable.ic_ph_magnifying_glass, R.drawable.ic_ph_magnifying_glass_fill),
    History(
        R.string.tv_destination_history,
        R.drawable.ic_ph_clock_counter_clockwise,
        R.drawable.ic_ph_clock_counter_clockwise_fill,
    ),
    Account(R.string.tv_destination_account, R.drawable.ic_ph_user_circle, R.drawable.ic_ph_user_circle_fill),
}

internal fun FilesOpenOrigin?.returnDestination(): TvDestination? =
    when (this) {
        FilesOpenOrigin.SEARCH -> TvDestination.Search
        FilesOpenOrigin.HISTORY -> TvDestination.History
        FilesOpenOrigin.HOME -> TvDestination.Home
        FilesOpenOrigin.TRANSFERS, FilesOpenOrigin.LINK, null -> null
    }
