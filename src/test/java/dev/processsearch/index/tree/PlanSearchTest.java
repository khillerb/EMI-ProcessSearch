package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.processsearch.index.tree.PlanSearch.Kind;
import dev.processsearch.index.tree.PlanSearch.Option;
import dev.processsearch.index.tree.PlanSearch.Outcome;
import dev.processsearch.index.tree.PlanSearch.Plan;
import dev.processsearch.index.tree.PlanSearch.Producers;
import dev.processsearch.index.tree.PlanSearch.Slot;
import dev.processsearch.index.tree.PlanSearch.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AND/OR resolution, over synthetic graphs.
 *
 * <p>The half that can genuinely go wrong: telling a loop apart from a limit, not caching failures,
 * and keeping a partial answer when a budget blows rather than collapsing to nothing. None of it
 * needs a game, which is the whole reason {@link PlanSearch} takes its graph through an interface.
 */
class PlanSearchTest {

    /** {@code made("brass", "mixing", "copper", "zinc")} -- anything unnamed is raw. */
    private static final class Recipes implements Producers {
        private final Map<Object, List<Option>> options = new LinkedHashMap<>();
        int calls;

        Recipes made(String out, String recipe, String... inputs) {
            List<Slot> slots = new ArrayList<>();
            for (String input : inputs) {
                slots.add(Slot.of(input));
            }
            options.computeIfAbsent(out, k -> new ArrayList<>())
                    .add(new Option(recipe, slots));
            return this;
        }

        /** A slot several different items could fill, the way a tag ingredient behaves. */
        Recipes madeFromAny(String out, String recipe, String... alternatives) {
            options.computeIfAbsent(out, k -> new ArrayList<>())
                    .add(new Option(recipe, List.of(new Slot(List.of((Object[]) alternatives)))));
            return this;
        }

        @Override
        public List<Option> waysToMake(Object key) {
            calls++;
            return options.getOrDefault(key, List.of());
        }
    }

    private static Plan plan(Recipes r, String target) {
        return PlanSearch.resolve(target, r, 16, 10_000, Long.MAX_VALUE);
    }

    /** Every leaf, left to right, as "kind:key". */
    private static List<String> leaves(Step step) {
        List<String> out = new ArrayList<>();
        collect(step, out);
        return out;
    }

    private static void collect(Step step, List<String> out) {
        if (step.inputs().isEmpty()) {
            out.add(step.kind() + ":" + step.key());
            return;
        }
        for (Step child : step.inputs()) {
            collect(child, out);
        }
    }

    // -- the AND half, which routing never had

    @Test
    void resolvesEveryInputNotJustOne() {
        // The point of the whole feature: a route through brass would mention copper and stop.
        Recipes r = new Recipes().made("brass", "mixing", "copper", "zinc");
        Plan plan = plan(r, "brass");

        assertEquals(Outcome.RESOLVED, plan.outcome());
        assertTrue(plan.complete());
        assertEquals(Kind.MADE, plan.root().kind());
        assertEquals(2, plan.root().inputs().size());
        assertEquals(List.of("RAW:copper", "RAW:zinc"), leaves(plan.root()));
    }

    @Test
    void recursesThroughSeveralTiers() {
        Recipes r = new Recipes()
                .made("sheet", "pressing", "brass")
                .made("brass", "mixing", "copper", "zinc")
                .made("copper", "smelting", "copper_ore");
        Plan plan = plan(r, "sheet");

        assertTrue(plan.complete());
        assertEquals(List.of("RAW:copper_ore", "RAW:zinc"), leaves(plan.root()));
    }

    @Test
    void anItemNothingMakesIsRawRatherThanAFailure() {
        Plan plan = plan(new Recipes(), "iron_ore");
        assertEquals(Outcome.RESOLVED, plan.outcome());
        assertEquals(Kind.RAW, plan.root().kind());
    }

    // -- the OR half: choosing between recipes

    @Test
    void takesTheFirstRecipeThatResolves() {
        // Ordering is the adapter's job, so the search must respect it exactly.
        Recipes r = new Recipes()
                .made("plate", "preferred", "ingot")
                .made("plate", "fallback", "ingot");
        assertEquals("preferred", plan(r, "plate").root().recipe());
    }

    @Test
    void fallsThroughToALaterRecipeWhenTheFirstLoops() {
        // press(plate) needs plate: circular. The hammer recipe is the real one.
        Recipes r = new Recipes()
                .made("plate", "press", "plate")
                .made("plate", "hammer", "ingot");
        Plan plan = plan(r, "plate");

        assertTrue(plan.complete());
        assertEquals("hammer", plan.root().recipe());
    }

    @Test
    void fallsThroughWhenTheFirstRecipeLoopsDeeper() {
        // a <- b <- a is a two-step loop, which the ancestor set has to catch, not just self-refs.
        Recipes r = new Recipes()
                .made("a", "long", "b")
                .made("b", "back", "a")
                .made("a", "direct", "ore");
        Plan plan = plan(r, "a");

        assertTrue(plan.complete());
        assertEquals("direct", plan.root().recipe());
    }

    @Test
    @Timeout(10)
    void mutuallyRecursiveRecipesTerminate() {
        Recipes r = new Recipes()
                .made("a", "r1", "b")
                .made("b", "r2", "a");
        Plan plan = plan(r, "a");
        assertFalse(plan.usable());
        assertEquals(Outcome.UNRESOLVABLE, plan.outcome());
    }

    @Test
    void aRecipeWithOneImpossibleInputIsRejectedForALaterOne() {
        Recipes r = new Recipes()
                .made("gizmo", "hard", "copper", "impossible")
                .made("impossible", "loop", "impossible")
                .made("gizmo", "easy", "copper");
        Plan plan = plan(r, "gizmo");

        assertEquals("easy", plan.root().recipe());
        assertTrue(plan.complete());
    }

    // -- memoisation, and what must not be memoised

    @Test
    void sharedSubtreesAreResolvedOnce() {
        // Everything needs iron; without a memo this is exponential on a real pack.
        Recipes r = new Recipes()
                .made("machine", "assembly", "gear", "casing")
                .made("gear", "press", "iron")
                .made("casing", "press", "iron")
                .made("iron", "smelting", "iron_ore");
        Plan plan = plan(r, "machine");
        assertTrue(plan.complete());

        // gear and casing both want iron; iron must be looked up once, not twice.
        int calls = r.calls;
        Recipes again = new Recipes()
                .made("machine", "assembly", "gear", "casing")
                .made("gear", "press", "iron")
                .made("casing", "press", "iron")
                .made("iron", "smelting", "iron_ore");
        PlanSearch.resolve("machine", again, 16, 10_000, Long.MAX_VALUE);
        assertEquals(calls, again.calls);
        assertTrue(calls <= 6, "expected one lookup per distinct item, took " + calls);
    }

    @Test
    void aFailureIsNotCachedAndPoisoningLaterBranches() {
        // "shared" is unmakeable while resolving through the looping branch, but perfectly fine
        // underneath the second one. Caching the first answer would lose the whole plan.
        Recipes r = new Recipes()
                .made("top", "viaLoop", "shared", "loop")
                .made("loop", "self", "loop")
                .made("top", "direct", "shared")
                .made("shared", "make", "ore");
        Plan plan = plan(r, "top");

        assertTrue(plan.complete(), "the second recipe should still resolve");
        assertEquals("direct", plan.root().recipe());
        assertEquals(List.of("RAW:ore"), leaves(plan.root()));
    }

    // -- limits, which must stay apart from failures

    @Test
    void depthIsMarkedInPlaceRatherThanDiscardingTheTree() {
        Recipes r = new Recipes()
                .made("a", "r", "b").made("b", "r", "c")
                .made("c", "r", "d").made("d", "r", "ore");
        Plan plan = PlanSearch.resolve("a", r, 2, 10_000, Long.MAX_VALUE);

        assertEquals(Outcome.DEPTH_LIMIT, plan.outcome());
        assertFalse(plan.complete());
        // Still usable: most of the tree is there, with the far end flagged.
        assertTrue(plan.usable());
        assertEquals(Kind.MADE, plan.root().kind());
        assertTrue(leaves(plan.root()).stream().anyMatch(l -> l.startsWith("UNRESOLVED:")));
    }

    @Test
    void theNodeBudgetLeavesAPartialPlan() {
        Recipes r = new Recipes()
                .made("a", "r", "b", "c").made("b", "r", "d", "e").made("c", "r", "f", "g");
        Plan plan = PlanSearch.resolve("a", r, 16, 3, Long.MAX_VALUE);

        assertEquals(Outcome.BUDGET_EXHAUSTED, plan.outcome());
        assertFalse(plan.complete());
    }

    @Test
    @Timeout(20)
    void theClockLeavesAPartialPlan() {
        Producers slow = key -> {
            long until = System.nanoTime() + 300_000L;
            while (System.nanoTime() < until) {
                // Busy, so the deadline measures something real.
            }
            return List.of(new Option("r",
                    List.of(Slot.of(key + ".a"), Slot.of(key + ".b"))));
        };
        Plan plan = PlanSearch.resolve("a", slow, 24, 5_000_000, 50_000_000L);
        assertEquals(Outcome.TIME_LIMIT, plan.outcome());
    }

    @Test
    void limitsAndLoopsAreNotConflated() {
        // A loop must make the caller try another recipe silently; a limit must be reported.
        Recipes looping = new Recipes().made("a", "self", "a");
        assertEquals(Outcome.UNRESOLVABLE, plan(looping, "a").outcome());

        Recipes deep = new Recipes().made("a", "r", "b").made("b", "r", "ore");
        assertEquals(Outcome.DEPTH_LIMIT,
                PlanSearch.resolve("a", deep, 1, 10_000, Long.MAX_VALUE).outcome());
    }

    // -- robustness

    // -- tag ingredients: an OR nested inside the recipe's AND

    @Test
    void aSlotNeedsOnlyOneOfItsAlternatives() {
        // "any plank" must not read as "every plank", which is what collapsing a tag would do.
        Recipes r = new Recipes().madeFromAny("chest", "crafting", "oak", "birch", "spruce");
        Plan plan = plan(r, "chest");

        assertTrue(plan.complete());
        assertEquals(1, plan.root().inputs().size(), "one slot, not one child per alternative");
        assertEquals(List.of("RAW:oak"), leaves(plan.root()));
    }

    @Test
    void anUnobtainableAlternativeFallsThroughToTheNextOne() {
        // The whole reason slots keep their alternatives: whichever variant happens to come first
        // may be unmakeable, and that must not reject an otherwise fine recipe.
        Recipes r = new Recipes()
                .madeFromAny("chest", "crafting", "cursed", "birch")
                .made("cursed", "loop", "cursed");
        Plan plan = plan(r, "chest");

        assertTrue(plan.complete());
        assertEquals(List.of("RAW:birch"), leaves(plan.root()));
    }

    @Test
    void aSlotNothingCanFillRejectsTheRecipe() {
        Recipes r = new Recipes()
                .madeFromAny("gizmo", "hard", "cursed")
                .made("cursed", "loop", "cursed")
                .made("gizmo", "easy", "ore");
        assertEquals("easy", plan(r, "gizmo").root().recipe());
    }

    @Test
    void aThrowingProducerDoesNotPropagate() {
        Producers broken = key -> {
            throw new IllegalStateException("mod did something odd");
        };
        // The adapter catches; this asserts the search does not add its own failure mode.
        Producers guarded = key -> {
            try {
                return broken.waysToMake(key);
            } catch (RuntimeException e) {
                return List.of();
            }
        };
        Plan plan = PlanSearch.resolve("a", guarded, 8, 100, Long.MAX_VALUE);
        assertEquals(Kind.RAW, plan.root().kind());
    }

    @Test
    void nullInputsAreNotAnException() {
        assertNotNull(PlanSearch.resolve(null, new Recipes(), 8, 100, Long.MAX_VALUE));
        assertEquals(Outcome.UNRESOLVABLE,
                PlanSearch.resolve("a", null, 8, 100, Long.MAX_VALUE).outcome());
    }
}
