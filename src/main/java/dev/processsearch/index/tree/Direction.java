package dev.processsearch.index.tree;

/**
 * Which way the graph grows.
 *
 * <p>Note this is the mirror image of the search prefixes, where {@code >} means "made by". On the
 * tree the arrow points the way the chain runs, which is the reading that makes sense once you are
 * looking at a chain rather than a single item.
 */
public enum Direction {
    /** {@code <} -- follow outputs forward: what can this be processed into? */
    CONSUMERS('<', "processed into"),
    /** {@code >} -- follow inputs backward: what are all the ways I can produce this? */
    PRODUCERS('>', "produced by");

    public final char symbol;
    public final String description;

    Direction(char symbol, String description) {
        this.symbol = symbol;
        this.description = description;
    }

    public Direction opposite() {
        return this == CONSUMERS ? PRODUCERS : CONSUMERS;
    }

    /**
     * Which way the layers stack: {@code <} is a top-down tree with the root above what it becomes,
     * {@code >} a bottom-up one with the root below everything that feeds it.
     */
    public boolean growsDown() {
        return this == CONSUMERS;
    }

    /** How a process node reads in the drill-down header: "47 recipes consuming Cobblestone". */
    public String verb() {
        return this == CONSUMERS ? "consuming" : "producing";
    }
}
