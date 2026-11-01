package io.putdotio.android.design

public enum class PutioEmptyGlyph(public val text: String) {
    EmptyFolder("[ ]"),
    NoResults("¯\\_(ツ)_/¯"),
    NoTransfers("( ⌐■_■)"),
    Offline("(x_x)"),

    /** The player's error state, which shares the offline face. */
    CantPlay("(x_x)"),
    CouldNotLoad("(・_・;)"),
    NothingYet("( ˘▽˘)っ"),
    HistoryOff("(-_-) zZ"),
}
