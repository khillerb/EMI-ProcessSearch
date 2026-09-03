package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.processsearch.index.tree.RouteSearch.Leg;
import dev.processsearch.index.tree.RouteSearch.Neighbours;
import dev.processsearch.index.tree.RouteSearch.Outcome;
import dev.processsearch.index.tree.RouteSearch.Route;
import dev.processsearch.index.tree.RouteSearch.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search, over synthetic graphs.
 *
 * <p>This is the half of route finding that can genuinely go wrong -- the meet-in-the-middle
 * reconstruction, cycles, and telling the three failure modes apart -- and it is the half that
 * needs no game to check, which is why {@link RouteSearch} takes its graph through an interface
 * rather than reaching for EMI.
 */
class RouteSearchTest {

    /** A directed recipe graph written as edges: {@code edge("iron", "smelting", "ingot")}. */
    private static final class Graph implements Neighbours {
        private final Map<Object, List<Step>> forward = new LinkedHashMap<>();
        private final Map<Object, List<Step>> backward = new LinkedHashMap<>();
        int expansions;

        Graph edge(String from, String recipe, String to) {
            forward.computeIfAbsent(from, k -> new ArrayList<>()).add(new Step(to, recipe));
            backward.computeIfAbsent(to, k -> new ArrayList<>()).add(new Step(from, recipe));
            return this;
        }

        @Override
        public List<Step> from(Object key, boolean goingForward) {
            expansions++;
            return (goingForward ? forward : backward).getOrDefault(key, List.of());
        }
    }

    private static Route find(Graph g, String from, String to) {
        return RouteSearch.find(from, to, g, 16, 10_000);
    }

    /** The chain as "recipe>item" pairs, which is what a route actually reads as. */
    private static List<String> legs(Route route) {
        List<String> out = new ArrayList<>();
        for (Leg leg : route.legs()) {
            out.add(leg.recipe() + ">" + leg.key());
        }
        return out;
    }

    // -- the happy path

    @Test
    void findsASingleStep() {
        Graph g = new Graph().edge("iron", "smelting", "ingot");
        Route route = find(g, "iron", "ingot");

        assertTrue(route.found());
        assertEquals(1, route.steps());
        assertEquals(List.of("smelting>ingot"), legs(route));
        assertEquals("iron", route.source());
        assertEquals("ingot", route.target());
    }

    @Test
    void reconstructsALongChainInOrder() {
        // Odd length, so the two halves meet off-centre and the join has to splice properly.
        Graph g = new Graph()
                .edge("a", "r1", "b")
                .edge("b", "r2", "c")
                .edge("c", "r3", "d")
                .edge("d", "r4", "e");
        Route route = find(g, "a", "e");

        assertTrue(route.found());
        assertEquals(List.of("r1>b", "r2>c", "r3>d", "r4>e"), legs(route));
    }

    @Test
    void reconstructsAnEvenLengthChain() {
        Graph g = new Graph()
                .edge("a", "r1", "b")
                .edge("b", "r2", "c")
                .edge("c", "r3", "d");
        assertEquals(List.of("r1>b", "r2>c", "r3>d"), legs(find(g, "a", "d")));
    }

    @Test
    void picksTheShortestOfSeveralRoutes() {
        Graph g = new Graph()
                .edge("a", "long1", "x").edge("x", "long2", "y").edge("y", "long3", "b")
                .edge("a", "short", "b");
        Route route = find(g, "a", "b");

        assertEquals(1, route.steps(), "breadth-first must not return the scenic route");
        assertEquals(List.of("short>b"), legs(route));
    }

    @Test
    void routeToSelfIsZeroSteps() {
        // Truthful rather than an error: you are already holding it.
        Route route = find(new Graph().edge("a", "r", "b"), "a", "a");
        assertTrue(route.found());
        assertEquals(0, route.steps());
        assertEquals("a", route.target());
    }

    // -- direction actually matters

    @Test
    void doesNotTravelEdgesBackwards() {
        Graph g = new Graph().edge("ingot", "crafting", "block");
        assertTrue(find(g, "ingot", "block").found());
        // A block is not an ingredient of the ingot, so there is no route the other way.
        assertEquals(Outcome.NO_PATH, find(g, "block", "ingot").outcome());
    }

    // -- termination

    @Test
    void cyclesTerminate() {
        // cobblestone -> stone -> cobblestone, the loop that makes a naive walk hang.
        Graph g = new Graph()
                .edge("cobble", "smelt", "stone")
                .edge("stone", "crush", "cobble")
                .edge("stone", "cut", "slab");
        Route route = find(g, "cobble", "slab");

        assertTrue(route.found());
        assertEquals(List.of("smelt>stone", "cut>slab"), legs(route));
    }

    @Test
    void aSelfLoopDoesNotHang() {
        Graph g = new Graph().edge("a", "loop", "a").edge("a", "r", "b");
        assertEquals(List.of("r>b"), legs(find(g, "a", "b")));
    }

    // -- the three failures, which must stay distinguishable

    @Test
    void disconnectedIsNoPath() {
        Graph g = new Graph().edge("a", "r", "b").edge("x", "r", "y");
        Route route = find(g, "a", "y");

        assertFalse(route.found());
        assertEquals(Outcome.NO_PATH, route.outcome());
        assertTrue(route.legs().isEmpty());
    }

    @Test
    void tooFarIsAStepLimitNotADeadEnd() {
        Graph g = new Graph()
                .edge("a", "r1", "b").edge("b", "r2", "c")
                .edge("c", "r3", "d").edge("d", "r4", "e");
        // The route is 4 long and exists; the cap is what stops us.
        Route route = RouteSearch.find("a", "e", g, 3, 10_000);

        assertEquals(Outcome.STEP_LIMIT, route.outcome(),
                "reporting NO_PATH here would send someone looking for a route they already have");
        assertTrue(RouteSearch.find("a", "e", g, 4, 10_000).found(),
                "and the same route must be found once the cap allows it");
    }

    @Test
    void aHugeGraphReportsTheBudgetRatherThanNoPath() {
        Graph g = new Graph();
        // A wide fan with the target off on its own: the search burns nodes and never arrives.
        for (int i = 0; i < 500; i++) {
            g.edge("a", "r" + i, "leaf" + i);
        }
        Route route = RouteSearch.find("a", "unreachable", g, 8, 50);

        assertEquals(Outcome.BUDGET_EXHAUSTED, route.outcome());
    }

    @Test
    void aStepCapOfZeroOnlyMatchesTheItemItself() {
        Graph g = new Graph().edge("a", "r", "b");
        assertEquals(Outcome.STEP_LIMIT, RouteSearch.find("a", "b", g, 0, 10_000).outcome());
        assertTrue(RouteSearch.find("a", "a", g, 0, 10_000).found());
    }

    // -- robustness

    @Test
    void aThrowingNeighbourSourceDoesNotPropagate() {
        Neighbours broken = (key, forward) -> {
            throw new IllegalStateException("mod did something odd");
        };
        Route route = RouteSearch.find("a", "b", broken, 8, 1000);
        assertEquals(Outcome.NO_PATH, route.outcome());
    }

    @Test
    void nullStepsAreSkippedRatherThanCrashing() {
        Neighbours ragged = (key, forward) -> {
            List<Step> steps = new ArrayList<>();
            steps.add(null);
            steps.add(new Step(null, "r"));
            if (key.equals("a") && forward) {
                steps.add(new Step("b", "good"));
            }
            return steps;
        };
        assertEquals(List.of("good>b"), legs(RouteSearch.find("a", "b", ragged, 8, 1000)));
    }

    @Test
    void nullEndpointsAreNotAnException() {
        Graph g = new Graph();
        assertEquals(Outcome.NO_PATH, RouteSearch.find(null, "b", g, 8, 100).outcome());
        assertEquals(Outcome.NO_PATH, RouteSearch.find("a", null, g, 8, 100).outcome());
        assertEquals(Outcome.NO_PATH, RouteSearch.find("a", "b", null, 8, 100).outcome());
    }

    // -- the reason for searching from both ends

    @Test
    void expandsTheSmallerFrontierSoLopsidedGraphsStayCheap() {
        // Source fans out to 200; target has exactly one producer. A forward-only search would
        // walk the whole fan, and the point of meeting in the middle is that this one does not.
        Graph wide = new Graph();
        for (int i = 0; i < 200; i++) {
            wide.edge("source", "spread" + i, "junk" + i);
        }
        wide.edge("source", "real", "middle").edge("middle", "final", "target");

        Route route = find(wide, "source", "target");
        assertTrue(route.found());
        assertEquals(List.of("real>middle", "final>target"), legs(route));

        Graph forwardOnly = new Graph();
        for (int i = 0; i < 200; i++) {
            forwardOnly.edge("source", "spread" + i, "junk" + i);
        }
        forwardOnly.edge("source", "real", "middle").edge("middle", "final", "target");
        RouteSearch.find("source", "target", forwardOnly, 16, 10_000);

        assertTrue(wide.expansions <= 4,
                "a two-step route in a 200-wide graph should cost a handful of expansions, took "
                        + wide.expansions);
    }

    @Test
    void ordersOfNeighboursDecideTiesWithoutChangingLength() {
        // The adapter sorts preferred categories first; ties must resolve to whichever the
        // neighbour source offered first, which is how that preference reaches the result.
        Map<Object, List<Step>> f = new HashMap<>();
        f.put("a", List.of(new Step("b", "preferred"), new Step("b2", "other")));
        Neighbours ordered = (key, forward) -> forward ? f.getOrDefault(key, List.of())
                : "b".equals(key) || "b2".equals(key) ? List.of(new Step("a", "back")) : List.of();

        Route route = RouteSearch.find("a", "b", ordered, 8, 1000);
        assertEquals(1, route.steps());
        assertEquals(List.of("preferred>b"), legs(route));
    }
}
