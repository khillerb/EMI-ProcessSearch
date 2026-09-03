package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.List;

import dev.processsearch.index.tree.RouteSearch.Neighbours;
import dev.processsearch.index.tree.RouteSearch.Outcome;
import dev.processsearch.index.tree.RouteSearch.Route;
import dev.processsearch.index.tree.RouteSearch.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wall-clock bound.
 *
 * <p>Steps and nodes are both poor proxies for what a search costs, because what a single node
 * costs to expand depends entirely on how tag-heavy the pack is -- one recipe taking a large tag
 * hands back dozens of predecessors at once. The search runs in one go on the client thread, so the
 * clock is the only bound that reliably keeps it off the frame.
 */
class RouteSearchTimeTest {

    /** Expensive and effectively endless, the shape a large modpack presents at its worst. */
    private static final class SlowGraph implements Neighbours {
        private final long delayNanosPerCall;
        int calls;

        SlowGraph(long delayNanosPerCall) {
            this.delayNanosPerCall = delayNanosPerCall;
        }

        @Override
        public List<Step> from(Object key, boolean forward) {
            calls++;
            long until = System.nanoTime() + delayNanosPerCall;
            while (System.nanoTime() < until) {
                // Busy, because Thread.sleep would hand the clock back and measure nothing useful.
            }
            List<Step> steps = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                steps.add(new Step(key + "." + i, "r"));
            }
            return steps;
        }
    }

    @Test
    @Timeout(20)
    void stopsOnTheClockRatherThanRunningToTheNodeBudget() {
        // Node and step budgets are enormous; only the deadline can stop this.
        SlowGraph graph = new SlowGraph(200_000L);
        long start = System.nanoTime();
        Route route = RouteSearch.find("a", "unreachable", graph, 64, 5_000_000, 150_000_000L);
        long tookMillis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(Outcome.TIME_LIMIT, route.outcome());
        // Generous: the clock is read every 256 expansions, so overshoot is bounded but not zero.
        assertTrue(tookMillis < 2000, "gave up after " + tookMillis + " ms, which is not a bound");
    }

    @Test
    @Timeout(20)
    void aTinyBudgetStillReturnsRatherThanHanging() {
        Route route = RouteSearch.find("a", "unreachable", new SlowGraph(500_000L), 64, 5_000_000, 1L);
        assertEquals(Outcome.TIME_LIMIT, route.outcome());
    }

    @Test
    void anAnswerInsideTheBudgetIsUnaffected() {
        Neighbours quick = (key, forward) -> forward && key.equals("a")
                ? List.of(new Step("b", "r")) : List.of();
        Route route = RouteSearch.find("a", "b", quick, 8, 1000, 5_000_000_000L);

        assertTrue(route.found());
        assertEquals(1, route.steps());
    }

    @Test
    void theTimeLimitIsDistinctFromTheNodeBudget() {
        // Same graph, different bound hit first: the two failures must not be conflated, because
        // the fix differs -- one is "you asked it to look too far", the other "this graph is dear".
        SlowGraph graph = new SlowGraph(0L);
        assertEquals(Outcome.BUDGET_EXHAUSTED,
                RouteSearch.find("a", "nope", graph, 64, 200, Long.MAX_VALUE).outcome());
        assertEquals(Outcome.TIME_LIMIT,
                RouteSearch.find("a", "nope", new SlowGraph(100_000L), 64, 5_000_000, 20_000_000L)
                        .outcome());
    }

    @Test
    void theOverloadWithoutADeadlineNeverTimesOut() {
        // The five-argument form is what the other tests use; it must stay deterministic.
        SlowGraph graph = new SlowGraph(0L);
        Route route = RouteSearch.find("a", "nope", graph, 3, 500);
        assertTrue(route.outcome() == Outcome.STEP_LIMIT
                || route.outcome() == Outcome.BUDGET_EXHAUSTED);
    }
}
