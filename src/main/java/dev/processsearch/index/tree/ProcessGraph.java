package dev.processsearch.index.tree;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;

/**
 * A built process graph: alternating item and machine nodes, rooted at whatever was hovered.
 *
 * <p>Item nodes are deduplicated by registry key, so this is a DAG drawn as a tree. That is the
 * whole reason it terminates: without it, cobblestone to stone to cobblestone is an infinite walk,
 * and in a 445-mod pack an unbounded one is millions of nodes.
 */
public final class ProcessGraph {
    public final Direction direction;
    /** The search text this was built under; the cache is dropped when it changes. */
    public final String query;

    private final Map<Object, ItemNode> byKey = new HashMap<>();
    /**
     * Every category the walk met, including the ones it then skipped, with how many recipes each
     * contributed. The Filters dropdown lists these -- it has to show the excluded ones too, or
     * there would be no way to turn one back on.
     */
    private final Map<EmiRecipeCategory, Integer> encountered = new LinkedHashMap<>();
    private ItemNode root;
    private ProcessGraphBuilder builder;

    private int nodeCount;
    private int deepest;
    private boolean budgetExhausted;
    private boolean facetsApplied;
    private boolean itemFilterApplied;
    private boolean excludedApplied;
    private boolean indexReady = true;

    ProcessGraph(Direction direction, String query) {
        this.direction = direction;
        this.query = query == null ? "" : query;
    }

    public ItemNode root() {
        return root;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public int deepest() {
        return deepest;
    }

    /** True when the walk stopped at the node budget rather than at the requested depth. */
    public boolean budgetExhausted() {
        return budgetExhausted;
    }

    /** True when the search box's facet tokens narrowed which recipes were followed. */
    public boolean facetsApplied() {
        return facetsApplied;
    }

    /** True when the search box's positive terms highlighted matches. */
    public boolean itemFilterApplied() {
        return itemFilterApplied;
    }

    /** True when a negated search term removed items outright. */
    public boolean excludedApplied() {
        return excludedApplied;
    }

    /**
     * False when the query needed the item index and it was not built yet, in which case the search
     * was <em>not</em> applied. Saying so matters: the failure mode is silent otherwise, and a
     * negated class filter with no index quietly admits everything.
     */
    public boolean indexReady() {
        return indexReady;
    }

    public Map<EmiRecipeCategory, Integer> encountered() {
        return encountered;
    }

    /**
     * Walks outward from a node far enough to fill the view.
     *
     * @return true when anything new appeared, so the screen knows to re-lay-out
     */
    public boolean expand(ItemNode node, int hops) {
        return builder != null && builder.expandFrom(this, node, hops);
    }

    // ------------------------------------------------------------ build-time internals

    ItemNode nodeFor(Object key, EmiStack stack, int depth) {
        ItemNode existing = byKey.get(key);
        if (existing != null) {
            // Second sighting: link to the node that already exists rather than walking it again.
            existing.repeat = true;
            return existing;
        }
        ItemNode created = new ItemNode(key, stack, depth);
        byKey.put(key, created);
        nodeCount++;
        deepest = Math.max(deepest, depth);
        return created;
    }

    boolean isKnown(Object key) {
        return byKey.containsKey(key);
    }

    void setRoot(ItemNode node) {
        this.root = node;
    }

    void setBuilder(ProcessGraphBuilder builder) {
        this.builder = builder;
    }

    void countProcess() {
        nodeCount++;
    }

    boolean overBudget(int maxNodes) {
        if (nodeCount >= maxNodes) {
            budgetExhausted = true;
            return true;
        }
        return false;
    }

    void markFacetsApplied() {
        facetsApplied = true;
    }

    void markItemFilterApplied() {
        itemFilterApplied = true;
    }

    void markExcludedApplied() {
        excludedApplied = true;
    }

    void markIndexNotReady() {
        indexReady = false;
    }

    void countEncountered(EmiRecipeCategory category, int recipes) {
        encountered.merge(category, recipes, Integer::sum);
    }
}
