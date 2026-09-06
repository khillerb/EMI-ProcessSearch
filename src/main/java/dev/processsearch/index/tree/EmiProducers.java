package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * The recipe graph as {@link PlanSearch} wants it: ways to make a thing, best first.
 *
 * <p>The ordering here <em>is</em> the recipe-choice heuristic. The search takes the first option
 * that fully resolves, so nothing downstream scores or compares -- what this returns first is what
 * gets built. Three rules, cheapest first:
 *
 * <ol>
 *   <li>recipes in {@code treeIncludedCategories} -- the machines the player ticked in Filters</li>
 *   <li>the busiest category, since a machine with a thousand recipes is how the pack generally
 *       does this and a two-recipe category is usually a one-off</li>
 *   <li>fewest slots, as a cheap proxy for the simpler recipe</li>
 *   <li>recipe id, so the same pack always produces the same plan</li>
 * </ol>
 *
 * <p>Determinism matters more than it looks: a plan that shuffled between openings would be
 * impossible to work from, and impossible to report a bug about.
 */
final class EmiProducers implements PlanSearch.Producers {
    /**
     * How many ways to fill one slot are kept.
     *
     * <p>An ore or plank tag can flatten to hundreds, and every one is a branch the search may
     * walk. The alternatives are ordered before being cut, so what survives is the sensible end of
     * the list rather than an arbitrary slice.
     */
    private static final int MAX_ALTERNATIVES = 12;

    private final EmiRecipeManager manager;
    private final IdentityRecipes identity = new IdentityRecipes();
    private final boolean hideIdentity;
    private final Set<String> preferred;

    private final Map<Object, EmiStack> stacks = new HashMap<>();
    /**
     * Which other machines do each step, shared with routing so both ask the same question the
     * same way. Display only -- the search still picks one recipe, and this is what lets the plan
     * say "or use one of these" instead of quietly deciding for you.
     */
    private final EquivalentMachines equivalents;
    /** Options per key. The search memoises results, not lookups, so this saves the rebuild. */
    private final Map<Object, List<PlanSearch.Option>> cache = new HashMap<>();

    EmiProducers(EmiRecipeManager manager) {
        this.manager = manager;
        this.equivalents = new EquivalentMachines(manager);
        this.hideIdentity = ProcessSearchConfig.treeHideIdentityRecipes();
        this.preferred = Set.copyOf(ProcessSearchConfig.treeIncludedCategories());
    }

    /** @return the key, or null when the stack is not something the index can name */
    Object remember(EmiStack stack) {
        Object key = Scan.key(stack);
        if (key != null) {
            stacks.putIfAbsent(key, stack);
        }
        return key;
    }

    /** The stack a key was first seen as, for drawing the plan afterwards. */
    EmiStack stackFor(Object key) {
        return stacks.get(key);
    }

    /** Other machines doing the same step, best first. Empty when this is the only way. */
    List<EmiRecipe> equivalentsFor(EmiRecipe recipe, Object producedKey) {
        return equivalents.like(recipe, stacks.get(producedKey));
    }

    @Override
    public List<PlanSearch.Option> waysToMake(Object key) {
        List<PlanSearch.Option> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<PlanSearch.Option> options = build(key);
        cache.put(key, options);
        return options;
    }

    private List<PlanSearch.Option> build(Object key) {
        EmiStack stack = stacks.get(key);
        if (stack == null) {
            return List.of();
        }
        List<EmiRecipe> recipes;
        try {
            recipes = manager.getRecipesByOutput(stack);
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
        if (recipes == null || recipes.isEmpty()) {
            // Nothing makes it, so the plan bottoms out here and tells you to go and get it.
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>(recipes.size());
        for (EmiRecipe recipe : recipes) {
            if (recipe == null || (hideIdentity && identity.test(recipe))) {
                // Identity recipes would pad every plan with anvil and grindstone steps that
                // change nothing about what you have.
                continue;
            }
            List<PlanSearch.Slot> slots = slotsOf(recipe, key);
            if (slots == null) {
                continue;
            }
            candidates.add(new Candidate(recipe, slots, isPreferred(recipe), idOf(recipe)));
        }

        // Preferred machines first, then the busiest: a category with a thousand recipes is the
        // pack's general-purpose way of doing this, and a two-recipe category is usually a
        // one-off. Slot count and id only break the remaining ties, so plans stay stable.
        candidates.sort(Comparator.comparing((Candidate c) -> !c.preferred)
                .thenComparing(Comparator.comparingInt(
                        (Candidate c) -> equivalents.sizeOf(EquivalentMachines.categoryOf(c.recipe)))
                        .reversed())
                .thenComparingInt(c -> c.slots.size())
                .thenComparing(c -> c.id));

        List<PlanSearch.Option> options = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            options.add(new PlanSearch.Option(candidate.recipe, candidate.slots));
        }
        return List.copyOf(options);
    }

    private record Candidate(EmiRecipe recipe, List<PlanSearch.Slot> slots, boolean preferred,
                             String id) {}

    /**
     * The recipe's ingredient slots, each keeping the items that could fill it.
     *
     * @param output the item being made, dropped from its own inputs -- a recipe that consumes
     *               some of what it produces would otherwise look circular before the search even
     *               got to it
     * @return null when the recipe could not be read at all
     */
    private List<PlanSearch.Slot> slotsOf(EmiRecipe recipe, Object output) {
        List<EmiIngredient> inputs;
        try {
            // Catalysts are deliberately absent: EMI keeps them off getInputs(), so a plan never
            // claims you consume the machine. Which machines are needed comes from the categories.
            inputs = recipe.getInputs();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        if (inputs == null) {
            return List.of();
        }

        List<PlanSearch.Slot> slots = new ArrayList<>(inputs.size());
        for (EmiIngredient ingredient : inputs) {
            if (ingredient == null) {
                continue;
            }
            Set<Object> alternatives = new LinkedHashSet<>();
            try {
                for (EmiStack candidate : ingredient.getEmiStacks()) {
                    Object alternative = remember(candidate);
                    if (alternative != null && !alternative.equals(output)) {
                        alternatives.add(alternative);
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
            if (alternatives.isEmpty()) {
                // An empty slot is not a requirement; a recipe of nothing but these is free.
                continue;
            }
            slots.add(new PlanSearch.Slot(trim(alternatives)));
        }
        return List.copyOf(slots);
    }

    /**
     * Orders a slot's alternatives, then caps them.
     *
     * <p>Vanilla first, because in a tag full of a hundred mods' variants the vanilla one is
     * almost always the plainly obtainable choice, and then by id so the plan is stable. This is a
     * heuristic and it is allowed to be wrong: the search tries alternatives in order and moves on
     * when one does not work out, so the cost of a poor guess is a slightly odd plan rather than
     * no plan.
     */
    private static List<Object> trim(Set<Object> alternatives) {
        List<Object> ordered = new ArrayList<>(alternatives);
        ordered.sort(Comparator.comparing((Object key) -> !isVanilla(key))
                .thenComparing(EmiProducers::idOf));
        return List.copyOf(ordered.subList(0, Math.min(ordered.size(), MAX_ALTERNATIVES)));
    }

    private static boolean isVanilla(Object key) {
        return idOf(key).startsWith("minecraft:");
    }

    private static String idOf(Object key) {
        if (key instanceof Item item) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            return id == null ? "" : id.toString();
        }
        ResourceLocation id = key instanceof net.minecraft.world.level.material.Fluid fluid
                ? BuiltInRegistries.FLUID.getKey(fluid) : null;
        return id == null ? String.valueOf(key) : id.toString();
    }

    private static String idOf(EmiRecipe recipe) {
        try {
            ResourceLocation id = recipe.getId();
            return id == null ? "" : id.toString();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    private boolean isPreferred(EmiRecipe recipe) {
        if (preferred.isEmpty()) {
            return false;
        }
        try {
            EmiRecipeCategory category = recipe.getCategory();
            return category != null && preferred.contains(category.getId().toString());
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
