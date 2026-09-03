package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.Scan;

/**
 * The recipe graph, as {@link RouteSearch} wants to see it.
 *
 * <p>All the real work is EMI's: {@code EmiRecipes$Manager} keeps pre-built {@code byInput} and
 * {@code byOutput} maps with tag ingredients already expanded into them, so each expansion here is
 * one hash lookup rather than a scan. Having <em>both</em> directions pre-indexed is what makes
 * searching from both ends possible at all.
 *
 * <p>Keys are the registry singletons {@link Scan#key} hands back, so NBT variants collapse onto
 * one node and the search terminates. EMI needs an {@code EmiStack} to look anything up, though,
 * so a representative stack is remembered for every key as it is discovered -- seeded with the two
 * endpoints, and thereafter always recorded before a key is ever handed back to the search.
 */
final class EmiNeighbours implements RouteSearch.Neighbours {
    private final EmiRecipeManager manager;
    private final IdentityRecipes identity = new IdentityRecipes();
    private final boolean hideIdentity;

    /**
     * Categories the player ticked in the tree's Filters panel.
     *
     * <p>A preference by default rather than a gate. The tree needs an opt-in list because it fans
     * out exponentially, but a route is a targeted question whose fan-out is bounded by the node
     * budget -- and defaulting to "no machines enabled" would mean a fresh install finds nothing.
     * So these are sorted to the front, which makes breadth-first prefer them between routes of
     * equal length, and {@code routeRespectCategoryFilter} turns them into a hard constraint for
     * players who want "only what I can actually build".
     */
    private final Set<String> preferred;
    private final boolean hardFilter;

    /** Key to a stack that can be fed back into EMI's lookups. */
    private final Map<Object, EmiStack> stacks = new HashMap<>();

    EmiNeighbours(EmiRecipeManager manager) {
        this.manager = manager;
        this.hideIdentity = ProcessSearchConfig.treeHideIdentityRecipes();
        this.preferred = Set.copyOf(ProcessSearchConfig.treeIncludedCategories());
        this.hardFilter = ProcessSearchConfig.routeRespectCategoryFilter();
    }

    /** @return the key, or null when the stack is not something the index can name */
    Object remember(EmiStack stack) {
        Object key = Scan.key(stack);
        if (key != null) {
            stacks.putIfAbsent(key, stack);
        }
        return key;
    }

    /** The stack a key was first seen as, for drawing the route afterwards. */
    EmiStack stackFor(Object key) {
        return stacks.get(key);
    }

    @Override
    public List<RouteSearch.Step> from(Object key, boolean forward) {
        EmiStack stack = stacks.get(key);
        if (stack == null) {
            return List.of();
        }
        List<EmiRecipe> recipes = lookup(stack, forward);
        if (recipes.isEmpty()) {
            return List.of();
        }

        List<RouteSearch.Step> steps = new ArrayList<>();
        for (EmiRecipe recipe : ordered(recipes)) {
            if (!allowed(recipe)) {
                continue;
            }
            for (EmiStack side : farSide(recipe, forward)) {
                Object found = remember(side);
                // Dropping the item we came from kills the self-loop every reversible recipe has,
                // and stops a route reading "iron -> iron -> ingot".
                if (found != null && !found.equals(key)) {
                    steps.add(new RouteSearch.Step(found, recipe));
                }
            }
        }
        return steps;
    }

    private List<EmiRecipe> lookup(EmiStack stack, boolean forward) {
        try {
            List<EmiRecipe> found = forward
                    ? manager.getRecipesByInput(stack)
                    : manager.getRecipesByOutput(stack);
            return found == null ? List.of() : found;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /**
     * Preferred categories first, so a tie between two equally short routes goes to the machines
     * the player said they cared about. Breadth-first keeps the first arrival, so ordering here is
     * the whole mechanism -- there is no scoring pass.
     */
    private List<EmiRecipe> ordered(List<EmiRecipe> recipes) {
        if (preferred.isEmpty() || recipes.size() < 2) {
            return recipes;
        }
        List<EmiRecipe> sorted = new ArrayList<>(recipes);
        sorted.sort(Comparator.comparing(r -> !isPreferred(r)));
        return sorted;
    }

    private boolean allowed(EmiRecipe recipe) {
        if (recipe == null) {
            return false;
        }
        if (hardFilter && !preferred.isEmpty() && !isPreferred(recipe)) {
            return false;
        }
        return !hideIdentity || !identity.test(recipe);
    }

    private boolean isPreferred(EmiRecipe recipe) {
        try {
            EmiRecipeCategory category = recipe.getCategory();
            return category != null && preferred.contains(category.getId().toString());
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * The other end of the step: outputs going forward, inputs going backward.
     *
     * <p>Catalysts are deliberately absent -- EMI keeps them off {@code getInputs()} -- so a route
     * never claims you turn a Mechanical Mixer into anything.
     *
     * <p>A tag ingredient flattens to every stack it accepts, which for something like a plank tag
     * is dozens of predecessors from a single recipe. That is genuinely correct, since any of them
     * would do, so it is left uncapped and the node budget is what bounds it.
     */
    private List<EmiStack> farSide(EmiRecipe recipe, boolean forward) {
        try {
            if (forward) {
                List<EmiStack> outputs = recipe.getOutputs();
                return outputs == null ? List.of() : outputs;
            }
            List<EmiIngredient> inputs = recipe.getInputs();
            if (inputs == null) {
                return List.of();
            }
            List<EmiStack> flat = new ArrayList<>(inputs.size());
            for (EmiIngredient ingredient : inputs) {
                if (ingredient != null) {
                    flat.addAll(ingredient.getEmiStacks());
                }
            }
            return flat;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }
}
