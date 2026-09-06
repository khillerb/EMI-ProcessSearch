package dev.processsearch.index.tree;

import dev.processsearch.ProcessSearchConfig;

/**
 * What a search is allowed to spend, and how that grows each time you press Deeper.
 *
 * <p>Routing and planning bound themselves the same way and for the same reasons. One limit bounds
 * the <em>answer</em> -- steps for a route, tiers for a plan -- because past a handful it stops
 * being advice. The node and time budgets bound the <em>search</em>, and the clock is the one that
 * actually protects the frame, since what a node costs to expand depends entirely on how tag-heavy
 * the pack is.
 *
 * <p>Retrying is what makes a limit survivable: a search that stops short and offers a button beats
 * one that tells you to go and edit a config file. A ceiling is what stops the retry becoming a
 * hang with extra clicks. The config decides where a search starts; these decide where it stops.
 *
 * @param limit    steps for a route, tiers for a plan
 * @param nanos    wall clock, already converted from the configured milliseconds
 * @param canRaise false once every budget has reached its ceiling, so the button can go away
 *                 rather than lying about what another press would do
 */
record Budgets(int limit, int nodes, long nanos, boolean canRaise) {
    /** Added to the answer limit per press. Doubling it would overshoot something readable. */
    private static final int LIMIT_STEP = 4;

    /** Guards the shift: past this the budget is at its ceiling anyway, and 1 << 32 wraps. */
    private static final int MAX_SHIFT = 16;

    static Budgets route(int level) {
        return of(level,
                ProcessSearchConfig.routeMaxSteps(), ProcessSearchConfig.MAX_ROUTE_STEPS,
                ProcessSearchConfig.routeMaxNodes(), ProcessSearchConfig.MAX_ROUTE_NODES,
                ProcessSearchConfig.routeMaxMillis(), ProcessSearchConfig.MAX_ROUTE_MILLIS);
    }

    static Budgets plan(int level) {
        // The node and time ceilings are shared with routing on purpose: they bound the same thing
        // -- how long the client thread may be busy -- and the config already clamps the plan's
        // own values against them.
        return of(level,
                ProcessSearchConfig.planMaxDepth(), ProcessSearchConfig.MAX_PLAN_DEPTH,
                ProcessSearchConfig.planMaxNodes(), ProcessSearchConfig.MAX_ROUTE_NODES,
                ProcessSearchConfig.planMaxMillis(), ProcessSearchConfig.MAX_ROUTE_MILLIS);
    }

    private static Budgets of(int level, int baseLimit, int ceilLimit, int baseNodes, int ceilNodes,
                              int baseMillis, int ceilMillis) {
        int shift = Math.min(Math.max(level, 0), MAX_SHIFT);
        int limit = Math.min(ceilLimit, baseLimit + Math.max(level, 0) * LIMIT_STEP);
        int nodes = (int) Math.min(ceilNodes, (long) baseNodes << shift);
        int millis = (int) Math.min(ceilMillis, (long) baseMillis << shift);
        boolean canRaise = limit < ceilLimit || nodes < ceilNodes || millis < ceilMillis;
        return new Budgets(limit, nodes, millis * 1_000_000L, canRaise);
    }
}
