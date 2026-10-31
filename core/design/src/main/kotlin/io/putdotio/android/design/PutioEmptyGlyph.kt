package io.putdotio.android.design

/** The mono glyphs of DESIGN.md's Empty and error states table. */
public enum class PutioEmptyGlyph(public val text: String) {
    EmptyFolder("[ ]"),
    NoResults("¯\\_(ツ)_/¯"),
    NoTransfers("( ⌐■_■)"),
    Offline("(x_x)"),

    /** The player's error state, which shares the offline face. */
    CantPlay("(x_x)"),

    /** DESIGN.md › TV states: Couldn't load and Playback failed. */
    CouldNotLoad("(・_・;)"),
}
