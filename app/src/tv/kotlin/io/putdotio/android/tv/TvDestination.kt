package io.putdotio.android.tv

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.putdotio.android.R

/** The four top-level TV destinations, in drawer order. */
enum class TvDestination(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    Files(R.string.tv_destination_files, R.drawable.ic_ph_folder_fill),
    Search(R.string.tv_destination_search, R.drawable.ic_ph_magnifying_glass_fill),
    History(R.string.tv_destination_history, R.drawable.ic_ph_clock_counter_clockwise_fill),
    Account(R.string.tv_destination_account, R.drawable.ic_ph_user_circle_fill),
}
