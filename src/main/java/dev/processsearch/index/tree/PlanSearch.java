package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "What do I need to make this?" -- the whole tree of prerequisites, down to things you can mine.
 *
 * <p>Routing answers with a <em>path</em>, and a path is the wrong shape for planning. A route
 * reads "copper, then mixing, then pressing", but the mixing step also wants zinc and the pressing
 * step wants a press you have not built. It shows one thread through a fabric of requirements.
 *
 * <p>The difference is that resolving is an AND/OR problem and routing only ever walked the OR
 * side:
 *
 * <ul>
 *   <li>an <b>item</b> is an OR over the recipes that make it -- any one will do</li>
 *   <li>a <b>recipe</b> is an AND over its inputs -- you need all of them</li>
 * </ul>
 *
 * <p>So this picks one recipe per item and recurses into every input, which is why what comes back
 * is a genuine tree and can be drawn by the screen that already draws trees.
 *
 * <p>Free of EMI and Minecraft, like {@link RouteSearch}: keys are opaque and the graph arrives
 * through {@link Producers}. The order options come back in <em>is</em> the recipe-choice
 * heuristic -- the first one that resolves is taken -- so the adapter owns that policy and this
 * owns the walking.
 */
public final class PlanSearch {

    /**
     * One slot of a recipe, and everything that would fill it.
     *
     * <p>A tag ingredient is an OR nested inside the recipe's AND: {@code #c:planks} means "any
     * plank", not "every plank". Collapsing it to one arbitrary stack is what would make plans
     * fail spuriously -- if whichever variant happened to be first were unobtainable, a perfectly
     * good recipe would be rejected. So the slot keeps its alternatives and the search tries them
     * in order, exactly as it tries recipes.
     */
    public record Slot(List<Object> alternatives) {
        public static Slot of(Object only) {
            return new Slot(List.of(only));
        }
    }

    /** One way to make an item: the recipe, and the slots it needs filled. */
    public record Option(Object recipe, List<Slot> slots) {}

    /** Supplies the graph. This never learns what a key or a recipe actually is. */
    public interface Producers {
        /**
         * @return ways to make {@code key}, best first. An empty list means raw -- nothing makes
         *         this, so the plan bottoms out and tells you to go and get it.
         */
        List<Option> waysToMake(Object key);
    }

    public enum Outcome {
        /** Every branch bottomed out in something you can obtain. */
        RESOLVED,
        /** No combination of recipes reaches the bottom. */
        UNRESOLVABLE,
        /** Something is deeper than we looked. */
        DEPTH_LIMIT,
        /** Ran out of nodes. */
        BUDGET_EXHAUSTED,
        /** Ran out of clock. */
        TIME_LIMIT
    }

    public enum Kind {
        /** Made by a recipe, from the inputs below it. */
        MADE,
        /** Nothing makes this: an ore, a mob drop, worldgen. Go and get it. */
        RAW,
        /** We stopped before finding out how to make this. */
        UNRESOLVED
    }

    /**
     * A node of the plan.
     *
     * @param recipe the recipe chosen, for {@link Kind#MADE} only
     * @param why    for {@link Kind#UNRESOLVED}: the limit that stopped us, or null when this
     *               branch simply loops back on itself. The distinction drives everything -- a
     *               loop means "this recipe cannot work, try another", a limit means "I stopped
     *               looking", and only the second is worth showing the player.
     */
    public record Step(Kind kind, Object key, Object recipe, List<Step> inputs, Outcome why) {
        public boolean resolved() {
            return kind != Kind.UNRESOLVED;
        }
    }

    /** @param complete false when some branch is marked {@link Kind#UNRESOLVED} */
    public record Plan(Outcome outcome, Step root, boolean complete) {
        public boolean usable() {
            return root != null && root.resolved();
        }
    }

    private PlanSearch() {}

    public static Plan resolve(Object target, Producers producers,
                               int maxDepth, int maxNodes, long maxNanos) {
        if (target == null || producers == null) {
            return new Plan(Outcome.UNRESOLVABLE, null, false);
        }
        Resolver resolver = new Resolver(producers, maxDepth, maxNodes,
                maxNanos == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + maxNanos);
        Step root = resolver.resolve(target, 0);

        Outcome outcome;
        if (!root.resolved()) {
            outcome = root.why() == null ? Outcome.UNRESOLVABLE : root.why();
        } else if (resolver.worstLimit != null) {
            outcome = resolver.worstLimit;
        } else {
            outcome = Outcome.RESOLVED;
        }
        return new Plan(outcome, root, resolver.worstLimit == null && root.resolved());
    }

    /** How often the clock is read; reading it per node would cost more than it guards. */
    private static final int CLOCK_INTERVAL = 64;

    /**
     * True for the outcomes that mean "we stopped looking", as opposed to "there is nothing here".
     *
     * <p>The distinction decides whether a branch is kept. A limit is ours -- a bigger budget would
     * have got further -- so the branch stays, marked, and the rest of the plan is still worth
     * reading. A loop or a dead end belongs to the recipe: no budget would have helped, so the
     * recipe is abandoned and the next one tried.
     */
    private static boolean isLimit(Outcome why) {
        return why == Outcome.DEPTH_LIMIT
                || why == Outcome.BUDGET_EXHAUSTED
                || why == Outcome.TIME_LIMIT;
    }

    /**
     * Which limit a partial plan should report when several were hit.
     *
     * <p>Running out of clock or nodes stops the <em>whole</em> search, so anything still
     * unresolved may be unresolved only because of that. A depth limit is local to one branch. The
     * global ones therefore outrank it, or a plan that timed out would blame its depth setting.
     */
    private static int severity(Outcome why) {
        return switch (why) {
            case TIME_LIMIT, BUDGET_EXHAUSTED -> 2;
            case DEPTH_LIMIT -> 1;
            default -> 0;
        };
    }

    private static final class Resolver {
        private final Producers producers;
        private final int maxDepth;
        private final int maxNodes;
        private final long deadline;

        /**
         * Successes only.
         *
         * <p>A failure must never be cached: a branch that failed because it looped through its
         * own ancestors may resolve perfectly well somewhere else, and caching that would poison
         * unrelated subtrees. A success, by contrast, contains no reference to the ancestors that
         * were on the stack when it was found, so it stays valid anywhere -- at worst it is the
         * answer a slightly different context would have improved on.
         */
        private final Map<Object, Step> memo = new HashMap<>();
        private final Set<Object> ancestors = new HashSet<>();

        private int nodes;
        /** Set once a global budget blows; every later call short-circuits to it. */
        private Outcome stopped;
        /** The most serious limit hit anywhere, so a partial plan can say what it is missing. */
        private Outcome worstLimit;

        Resolver(Producers producers, int maxDepth, int maxNodes, long deadline) {
            this.producers = producers;
            this.maxDepth = maxDepth;
            this.maxNodes = maxNodes;
            this.deadline = deadline;
        }

        Step resolve(Object key, int depth) {
            if (stopped != null) {
                return limited(key, stopped);
            }
            if (++nodes > maxNodes) {
                return limited(key, stopped = Outcome.BUDGET_EXHAUSTED);
            }
            if ((nodes & (CLOCK_INTERVAL - 1)) == 0 && System.nanoTime() >= deadline) {
                return limited(key, stopped = Outcome.TIME_LIMIT);
            }

            Step cached = memo.get(key);
            if (cached != null) {
                return cached;
            }
            if (ancestors.contains(key)) {
                // Loops back on itself. Null reason, so the caller reads this as "that recipe
                // cannot work" and tries the next one rather than reporting it to the player.
                return new Step(Kind.UNRESOLVED, key, null, List.of(), null);
            }
            if (depth >= maxDepth) {
                return limited(key, Outcome.DEPTH_LIMIT);
            }

            List<Option> options = producers.waysToMake(key);
            if (options == null || options.isEmpty()) {
                Step raw = new Step(Kind.RAW, key, null, List.of(), null);
                memo.put(key, raw);
                return raw;
            }

            ancestors.add(key);
            try {
                Step partial = null;
                for (Option option : options) {
                    if (option == null || option.slots() == null) {
                        continue;
                    }
                    List<Step> children = new ArrayList<>(option.slots().size());
                    boolean impossible = false;
                    boolean whole = true;

                    for (Slot slot : option.slots()) {
                        Step chosen = fill(slot, depth);
                        if (chosen == null) {
                            // Nothing fills this slot: a loop, or a dead end. Either way no budget
                            // would have helped, so abandon this recipe and try the next.
                            impossible = true;
                            break;
                        }
                        children.add(chosen);
                        if (!chosen.resolved()) {
                            // A limit is our problem rather than the recipe's: keep the branch and
                            // mark it, because most of the tree is still worth showing. Discarding
                            // it would be a blank screen for a plan that mostly worked.
                            whole = false;
                        }
                    }

                    if (impossible) {
                        continue;
                    }
                    Step made = new Step(Kind.MADE, key, option.recipe(), List.copyOf(children), null);
                    if (whole) {
                        memo.put(key, made);
                        return made;
                    }
                    if (partial == null) {
                        partial = made;
                    }
                    if (stopped != null) {
                        break;
                    }
                }
                if (partial != null) {
                    return partial;
                }
            } finally {
                ancestors.remove(key);
            }
            // Every recipe looped or was empty. Genuinely unmakeable from here.
            return new Step(Kind.UNRESOLVED, key, null, List.of(), Outcome.UNRESOLVABLE);
        }

        /**
         * The best alternative for one slot, or null when none of them can work.
         *
         * <p>First one that resolves wins, same rule as recipes. A merely <em>limited</em>
         * alternative is held as a fallback rather than accepted immediately: another alternative
         * may still resolve properly, and a real answer beats a marked one.
         */
        private Step fill(Slot slot, int depth) {
            if (slot == null || slot.alternatives().isEmpty()) {
                return null;
            }
            Step fallback = null;
            for (Object alternative : slot.alternatives()) {
                Step step = resolve(alternative, depth + 1);
                if (step.resolved()) {
                    return step;
                }
                if (fallback == null && isLimit(step.why())) {
                    fallback = step;
                }
                if (stopped != null) {
                    break;
                }
            }
            return fallback;
        }

        private Step limited(Object key, Outcome why) {
            if (worstLimit == null || severity(why) > severity(worstLimit)) {
                worstLimit = why;
            }
            return new Step(Kind.UNRESOLVED, key, null, List.of(), why);
        }
    }
}
