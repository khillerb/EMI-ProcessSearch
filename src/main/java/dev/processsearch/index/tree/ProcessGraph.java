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
    private PlanSummary planSummary;

    /**
     * What kind of answer this graph is, since the screen now varies three ways.
     *
     * <p>A WALK fans outward from one item and its children are <em>alternatives</em>. A ROUTE is
     * a found path. A PLAN is a tree of prerequisites whose children are <em>requirements</em> --
     * the same picture meaning the opposite thing, which is why the screen has to know.
     */
    public enum Mode { WALK, ROUTE, PLAN }

    private Mode mode = Mode.WALK;
    private EmiStack routeFrom;
    private EmiStack routeTo;
    private int routeSteps;
    /**
     * Why the search stopped, kept so a failed route is still a screen with a Deeper button on it
     * rather than a line of chat you cannot act on.
     */
    private RouteSearch.Outcome routeOutcome = RouteSearch.Outcome.FOUND;
    private PlanSearch.Outcome planOutcome = PlanSearch.Outcome.RESOLVED;
    /** How many times Deeper has already been pressed for this pair. */
    private int routeEscalation;

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

    public Mode mode() {
        return mode;
    }

    /** True when this is a found path from one item to another, not a walk outward from one. */
    public boolean isRoute() {
        return mode == Mode.ROUTE;
    }

    /** True when this is a tree of prerequisites: every child of a machine is required. */
    public boolean isPlan() {
        return mode == Mode.PLAN;
    }

    /**
     * True for the answers that are fixed rather than explorable.
     *
     * <p>Both are drawn to their own depth rather than the configured one, and neither offers the
     * Filters button, because re-filtering would rebuild a walk that this is not.
     */
    public boolean isFixed() {
        return mode != Mode.WALK;
    }

    /** Machines the plan needs built, raw materials it bottoms out at, branches it gave up on. */
    public PlanSummary planSummary() {
        return planSummary;
    }

    /**
     * The actionable half of a plan, derived once when it is built.
     *
     * @param machines  distinct recipe categories, which is the list of things to build
     * @param raw       distinct items nothing makes, which is the list of things to go and get
     * @param missing   distinct items the search gave up on
     * @param deepest   how many tiers the plan runs to
     */
    public record PlanSummary(java.util.List<EmiRecipeCategory> machines,
                              java.util.List<EmiStack> raw,
                              java.util.List<EmiStack> missing,
                              java.util.Map<EmiRecipeCategory, EmiRecipeCategory> alternatives,
                              int deepest) {}

    public EmiStack routeFrom() {
        return routeFrom;
    }

    public EmiStack routeTo() {
        return routeTo;
    }

    /** Machines between the two ends. */
    public int routeSteps() {
        return routeSteps;
    }

    public RouteSearch.Outcome routeOutcome() {
        return routeOutcome;
    }

    public int routeEscalation() {
        return routeEscalation;
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

    /**
     * A node that is deliberately <em>not</em> deduplicated.
     *
     * <p>Only for plans. Everything needs iron, so a plan built through {@link #nodeFor} would
     * hand the same node several parents -- and the screen's layout recurses through
     * {@code processes()}, which turns sharing into combinatorial work and puts a cycle one wrong
     * edge away from a stack overflow. A plan is a tree and is built as one; the screen's
     * per-key badge still marks an item that appears in several branches, which in a plan is the
     * most useful thing on screen rather than an artifact.
     */
    ItemNode newNode(Object key, EmiStack stack, int depth) {
        ItemNode created = new ItemNode(key, stack, depth);
        nodeCount++;
        deepest = Math.max(deepest, depth);
        return created;
    }

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

    void markPlan(EmiStack target, PlanSearch.Outcome outcome, PlanSummary summary,
                  int escalation) {
        this.mode = Mode.PLAN;
        this.routeFrom = target;
        this.routeTo = target;
        this.planOutcome = outcome;
        this.planSummary = summary;
        // Shared with routing's counter: a graph is one or the other, never both, and one field
        // keeps the screen from having to ask which kind it is before offering Deeper.
        this.routeEscalation = escalation;
    }

    public PlanSearch.Outcome planOutcome() {
        return planOutcome;
    }

    void markRoute(EmiStack from, EmiStack to, int steps, RouteSearch.Outcome outcome,
                   int escalation) {
        this.mode = Mode.ROUTE;
        this.routeFrom = from;
        this.routeTo = to;
        this.routeSteps = steps;
        this.routeOutcome = outcome;
        this.routeEscalation = escalation;
    }

    void countEncountered(EmiRecipeCategory category, int recipes) {
        encountered.merge(category, recipes, Integer::sum);
    }
}
