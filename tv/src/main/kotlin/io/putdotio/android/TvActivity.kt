package io.putdotio.android

public abstract class TvActivity : BasePutioActivity() {
    override val maxFontScale: Float get() = TV_MAX_FONT_SCALE
}

internal const val TV_MAX_FONT_SCALE: Float = 1.15f
