package dev.processsearch.index;

/** Which of the four relations a search prefix asks about. */
public enum Role {
    /** {@code >} -- the item is an output of a recipe carrying the token. */
    MADE_BY,
    /** {@code <} -- the item is an input. */
    USED_IN,
    /** {@code *} -- the item is the workstation that runs the process. */
    MACHINE_FOR,
    /** {@code ~} -- what kind of item this is, independent of any recipe. */
    ITEM_CLASS
}
