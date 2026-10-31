package io.putdotio.android.design

/** The mono glyphs of DESIGN.md's Empty and error states table. */
public enum class PutioEmptyGlyph(public val text: String) {
    EmptyFolder("[ ]"),
    NoResults("¯\\_(ツ)_/¯"),
    NoTransfers("( ⌐■_■)"),
    Offline("(x_x)"),

    /** The player's error state, which shares the offline face. */
    CantPlay("(x_x)"),
    CouldNotLoad("(・_・;)"),

    /** TV Home with nothing pinned, or nothing at all yet. */
    NothingYet("( ˘▽˘)っ"),
}
