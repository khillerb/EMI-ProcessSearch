package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.List;

import dev.emi.emi.api.stack.EmiStack;

/**
 * One item or fluid in the graph.
 *
 * <p>Keyed on the registry singleton, the same way {@code ProcessIndex} keys its maps, so NBT
 * variants collapse into one node. Each key appears at most once in a graph -- a second sighting
 * links to the existing node rather than duplicating it, which is what stops cobblestone to stone to
 * cobblestone from running forever.
 */
public final class ItemNode {
    /** An {@code Item} or {@code Fluid}, from {@code Scan.key}. */
    public final Object key;
    /** The stack to draw, and to feed back into EMI's by-input/by-output maps. */
    public final EmiStack stack;
    public final int depth;

    private final List<ProcessNode> processes = new ArrayList<>(4);

    /** True once this node has been walked; a node is only ever expanded once. */
    boolean expanded;
    /** Processes the width cap dropped, reported honestly as "+N more" rather than hidden. */
    int hiddenProcesses;
    /** Set when this key was already in the graph, so it is drawn as a link rather than a branch. */
    boolean repeat;

    /** Set when the search box's positive terms match this item; drives the tint and draw order. */
    boolean matchesFilter;

    ItemNode(Object key, EmiStack stack, int depth) {
        this.key = key;
        this.stack = stack;
        this.depth = depth;
    }

    public List<ProcessNode> processes() {
        return processes;
    }

    void add(ProcessNode process) {
        processes.add(process);
    }

    public boolean isExpanded() {
        return expanded;
    }

    public boolean isRepeat() {
        return repeat;
    }

    public boolean matchesFilter() {
        return matchesFilter;
    }

    public int hiddenProcesses() {
        return hiddenProcesses;
    }

    /** True when this node has never been walked, so following it will reveal something new. */
    public boolean canExpand() {
        return !expanded;
    }

    @Override
    public String toString() {
        return "ItemNode[" + stack + " depth=" + depth + " processes=" + processes.size() + "]";
    }
}
