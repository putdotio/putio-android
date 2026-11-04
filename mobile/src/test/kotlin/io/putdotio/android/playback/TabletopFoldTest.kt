package io.putdotio.android.playback

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabletopFoldTest {
    private val density = Density(2f)

    @Test
    fun aHingeAcrossThePlayerSplitsItWhereTheHingeCrossesIt() {
        val fold = tabletopFold(
            hingeInWindow = Rect(0f, 1000f, 1600f, 1040f),
            contentInWindow = Rect(0f, 200f, 1600f, 1800f),
            density = density,
        )

        assertEquals(TabletopFold(top = 400.dp, bottom = 420.dp), fold)
    }

    @Test
    fun aHingeOutsideThePlayerLeavesItWhole() {
        assertNull(tabletopFold(Rect(0f, 100f, 1600f, 100f), Rect(0f, 200f, 1600f, 1800f), density))
        assertNull(tabletopFold(Rect(0f, 1900f, 1600f, 1900f), Rect(0f, 200f, 1600f, 1800f), density))
    }
}
