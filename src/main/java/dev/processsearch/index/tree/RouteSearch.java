package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * "How do I get from this to that?" -- bidirectional breadth-first search over the recipe graph.
 *
 * <p>The three search prefixes answer lookups: what makes a thing, what consumes it, what machine
 * runs the process. The process tree extends those one hop at a time. Neither answers the question
 * a player in a large pack actually has, which is a <em>path</em> -- and finding a five-step chain
 * by hand means opening twenty recipe screens and holding the result in your head.
 *
 * <p>Searching from both ends is what makes it affordable. A recipe graph branches hard in both
 * directions, so a one-way search to depth 8 is the branching factor to the eighth; meeting in the
 * middle is two searches to depth 4. Always expanding the <em>smaller</em> frontier keeps that
 * true even when the two ends have wildly different fan-out, which they usually do -- an ingot is
 * consumed by hundreds of recipes and produced by three.
 *
 * <p>Deliberately free of EMI and Minecraft: keys are opaque and the graph arrives through
 * {@link Neighbours}. That is what lets the interesting half of this feature -- cycles, budgets,
 * the meet-in-the-middle reconstruction -- be tested without a game running, the same split
 * {@code FacetRule} and {@code RuleFacets} use.
 */
public final class RouteSearch {

    /**
     * One edge of the graph, always expressed in the direction things actually flow.
     *
     * @param key    the item at the other end
     * @param recipe the recipe crossing the edge, opaque here and unwrapped by the caller
     */
    public record Step(Object key, Object recipe) {}

    /** Supplies the graph. The search never learns what a key or a recipe actually is. */
    public interface Neighbours {
        /**
         * @param key     the item to expand from
         * @param forward true to ask "what can this become" -- each returned {@link Step} is a
         *                recipe consuming {@code key} and the item it yields. False to ask "what
         *                makes this" -- each step is a recipe producing {@code key} and one of the
         *                items it consumes. Either way the step describes the same forward-running
         *                edge, which is what lets both halves of the search share a representation.
         */
        List<Step> from(Object key, boolean forward);
    }

    /**
     * Why the search stopped. The three failures need different advice, and telling a player "no
     * route" when the truth is "not within 8 steps" sends them looking for a path that exists.
     */
    public enum Outcome {
        /** A path was found. */
        FOUND,
        /** The reachable set was exhausted from one end. Nothing connects the two. */
        NO_PATH,
        /** A path may exist, but not within the step cap. */
        STEP_LIMIT,
        /** The node budget ran out first. A path may exist, and may even be short. */
        BUDGET_EXHAUSTED,
        /**
         * The time budget ran out.
         *
         * <p>Distinct from the node budget because the fix is different: nodes are about how far
         * you asked it to look, time is about how much the graph cost to look through. A pack with
         * enormous tags can burn a lot of clock without touching many distinct items.
         */
        TIME_LIMIT
    }

    /** One machine step: the recipe used, and the item that came out. */
    public record Leg(Object recipe, Object key) {}

    /**
     * @param source the item started from; {@code legs} runs forward from here
     * @param legs   empty when the outcome is not {@link Outcome#FOUND}, or when source and target
     *               are the same item
     */
    public record Route(Outcome outcome, Object source, List<Leg> legs) {
        public boolean found() {
            return outcome == Outcome.FOUND;
        }

        /** Machines between source and target. */
        public int steps() {
            return legs.size();
        }

        /** The item this route ends at, or the source when there are no legs. */
        public Object target() {
            return legs.isEmpty() ? source : legs.get(legs.size() - 1).key();
        }

        static Route failed(Outcome outcome, Object source) {
            return new Route(outcome, source, List.of());
        }
    }

    /** How a key was reached: the key one step nearer whichever end this search started from. */
    private record Edge(Object adjacent, Object recipe) {}

    private RouteSearch() {}

    /** How often the clock is read. Reading it per node would cost more than the work it guards. */
    private static final int CLOCK_INTERVAL = 256;

    /** Without a deadline. Used by tests, where determinism matters more than a time bound. */
    public static Route find(Object source, Object target, Neighbours neighbours,
                             int maxSteps, int maxNodes) {
        return find(source, target, neighbours, maxSteps, maxNodes, Long.MAX_VALUE);
    }

    /**
     * @param maxSteps the most machines a route may pass through. Past a handful a route stops
     *                 being advice and becomes noise, so this bounds the <em>answer</em>.
     * @param maxNodes distinct items the search may touch before giving up, across both frontiers
     * @param maxNanos wall clock before giving up. This is the bound that matters for feel: steps
     *                 and nodes are both poor proxies for cost, because how expensive a node is to
     *                 expand depends entirely on how tag-heavy the pack is. The search runs in one
     *                 go on the client thread, so this is what keeps it off the frame.
     */
    public static Route find(Object source, Object target, Neighbours neighbours,
                             int maxSteps, int maxNodes, long maxNanos) {
        if (source == null || target == null || neighbours == null) {
            return Route.failed(Outcome.NO_PATH, source);
        }
        if (source.equals(target)) {
            // Truthful rather than an error: you are already there.
            return new Route(Outcome.FOUND, source, List.of());
        }

        // Seeded with the endpoints mapped to null, so "have I reached this?" is one containment
        // check that needs no special case for the two roots.
        Map<Object, Edge> fromSource = new HashMap<>();
        Map<Object, Edge> fromTarget = new HashMap<>();
        fromSource.put(source, null);
        fromTarget.put(target, null);

        List<Object> forward = new ArrayList<>();
        List<Object> backward = new ArrayList<>();
        forward.add(source);
        backward.add(target);

        int forwardDepth = 0;
        int backwardDepth = 0;
        long deadline = maxNanos == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + maxNanos;
        int sinceClockCheck = 0;

        while (!forward.isEmpty() && !backward.isEmpty()) {
            if (forwardDepth + backwardDepth >= maxSteps) {
                // Both frontiers still have somewhere to go, so this is a cap and not a dead end.
                return Route.failed(Outcome.STEP_LIMIT, source);
            }
            if (fromSource.size() + fromTarget.size() >= maxNodes) {
                return Route.failed(Outcome.BUDGET_EXHAUSTED, source);
            }
            if (System.nanoTime() >= deadline) {
                return Route.failed(Outcome.TIME_LIMIT, source);
            }

            // The whole point of searching from both ends: keep the cheaper side moving.
            boolean expandForward = forward.size() <= backward.size();
            List<Object> frontier = expandForward ? forward : backward;
            Map<Object, Edge> seen = expandForward ? fromSource : fromTarget;
            Map<Object, Edge> other = expandForward ? fromTarget : fromSource;

            List<Object> next = new ArrayList<>();
            for (Object key : frontier) {
                for (Step step : safeFrom(neighbours, key, expandForward)) {
                    if (step == null || step.key() == null) {
                        continue;
                    }
                    Object found = step.key();
                    if (seen.containsKey(found)) {
                        // Already reached this side, by a path no longer than this one. Revisiting
                        // is what would turn cobblestone -> stone -> cobblestone into a hang.
                        continue;
                    }
                    seen.put(found, new Edge(key, step.recipe()));
                    if (other.containsKey(found)) {
                        return join(found, source, target, fromSource, fromTarget);
                    }
                    next.add(found);

                    if ((++sinceClockCheck & (CLOCK_INTERVAL - 1)) == 0
                            && System.nanoTime() >= deadline) {
                        return Route.failed(Outcome.TIME_LIMIT, source);
                    }
                }
            }

            if (expandForward) {
                forward = next;
                forwardDepth++;
            } else {
                backward = next;
                backwardDepth++;
            }
        }
        // A frontier emptied: everything reachable from one end was enumerated and the other end
        // was not in it.
        return Route.failed(Outcome.NO_PATH, source);
    }

    /**
     * Stitches the two half-paths at the item where they met.
     *
     * <p>Both maps store forward-running edges, so the source half is walked backwards and
     * reversed, and the target half is walked forwards and kept as is.
     */
    private static Route join(Object meeting, Object source, Object target,
                              Map<Object, Edge> fromSource, Map<Object, Edge> fromTarget) {
        LinkedList<Leg> legs = new LinkedList<>();

        for (Object at = meeting; !at.equals(source); ) {
            Edge edge = fromSource.get(at);
            if (edge == null) {
                break;
            }
            legs.addFirst(new Leg(edge.recipe(), at));
            at = edge.adjacent();
        }
        for (Object at = meeting; !at.equals(target); ) {
            Edge edge = fromTarget.get(at);
            if (edge == null) {
                break;
            }
            // adjacent() is the key one step nearer the target, so this edge already runs forward.
            legs.addLast(new Leg(edge.recipe(), edge.adjacent()));
            at = edge.adjacent();
        }
        return new Route(Outcome.FOUND, source, List.copyOf(legs));
    }

    /** A neighbour source that throws must not take the search down with it. */
    private static List<Step> safeFrom(Neighbours neighbours, Object key, boolean forward) {
        try {
            List<Step> steps = neighbours.from(key, forward);
            return steps == null ? List.of() : steps;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }
}
