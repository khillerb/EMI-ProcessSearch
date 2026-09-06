package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import net.minecraft.resources.ResourceLocation;

/**
 * Machines that do the same job: same things in, same thing out.
 *
 * <p>A big pack offers the same step three or four ways -- a Macerator, a set of Crushing Wheels
 * and somebody's Pulveriser all turn an ore into dust. Drawing them as separate steps reads as
 * several decisions when it is one, and picking one silently hides that there was a choice at all.
 *
 * <p>Shared by the plan and the route, which both need the same question answered and would
 * otherwise each grow their own copy of it. The process tree does not: it already has every machine
 * as a node, so the screen groups those by what they lead to rather than asking here.
 *
 * <p>Ranked by how many recipes the machine's category holds, largest first. A category with a
 * thousand recipes is how the pack generally does this sort of thing; a two-recipe category is
 * usually a one-off, and offering it as the headline answer would be odd.
 */
final class EquivalentMachines {
    /**
     * A generous ceiling on how many are kept.
     *
     * <p>The screen draws nine and rolls the rest into a chip, so this only needs to be enough for
     * that chip to be honest. It exists to bound the work, not the answer.
     */
    private static final int MAX_EQUIVALENTS = 16;

    private final EmiRecipeManager manager;
    private final IdentityRecipes identity = new IdentityRecipes();
    private final boolean hideIdentity;

    private final Map<EmiRecipeCategory, Integer> categorySize = new HashMap<>();
    private final Map<EmiRecipe, String> signatures = new IdentityHashMap<>();
    private final Map<EmiRecipe, List<EmiRecipe>> cache = new IdentityHashMap<>();

    EquivalentMachines(EmiRecipeManager manager) {
        this.manager = manager;
        this.hideIdentity = ProcessSearchConfig.treeHideIdentityRecipes();
    }

    /**
     * Other machines that make {@code output} from exactly the same inputs as {@code recipe}.
     *
     * <p>One entry per machine, not per recipe: two recipes of the same category are the same
     * answer twice over, and the point of the list is the choice between machines.
     *
     * @param output the stack being produced, which is what narrows the search to a handful
     * @return best first, never including {@code recipe} or anything of its own category
     */
    List<EmiRecipe> like(EmiRecipe recipe, EmiStack output) {
        if (recipe == null || output == null) {
            return List.of();
        }
        List<EmiRecipe> cached = cache.get(recipe);
        if (cached != null) {
            return cached;
        }
        List<EmiRecipe> found = compute(recipe, output);
        cache.put(recipe, found);
        return found;
    }

    private List<EmiRecipe> compute(EmiRecipe recipe, EmiStack output) {
        List<EmiRecipe> candidates;
        try {
            candidates = manager.getRecipesByOutput(output);
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
        if (candidates == null || candidates.size() < 2) {
            return List.of();
        }

        String wanted = signatureOf(recipe);
        Set<EmiRecipeCategory> seen = new HashSet<>();
        EmiRecipeCategory own = categoryOf(recipe);
        if (own != null) {
            seen.add(own);
        }

        List<EmiRecipe> same = new ArrayList<>();
        for (EmiRecipe other : candidates) {
            if (other == null || other == recipe) {
                continue;
            }
            if (hideIdentity && identity.test(other)) {
                continue;
            }
            // Signature before the category check, so a category is not used up by a recipe that
            // was never going to match anyway.
            if (!wanted.equals(signatureOf(other))) {
                continue;
            }
            EmiRecipeCategory category = categoryOf(other);
            if (category == null || !seen.add(category)) {
                continue;
            }
            same.add(other);
        }
        if (same.isEmpty()) {
            return List.of();
        }

        same.sort(Comparator.comparingInt((EmiRecipe r) -> sizeOf(categoryOf(r))).reversed()
                .thenComparing(EquivalentMachines::idOf));
        return List.copyOf(same.subList(0, Math.min(same.size(), MAX_EQUIVALENTS)));
    }

    /**
     * What a recipe consumes, as a comparable string.
     *
     * <p>Every item that could fill every slot, sorted, so two recipes match only when they really
     * do accept the same things. A looser test would happily call a Macerator and a furnace the
     * same step because both start from ore.
     */
    String signatureOf(EmiRecipe recipe) {
        String cached = signatures.get(recipe);
        if (cached != null) {
            return cached;
        }
        String signature = computeSignature(recipe);
        signatures.put(recipe, signature);
        return signature;
    }

    private static String computeSignature(EmiRecipe recipe) {
        List<EmiIngredient> inputs;
        try {
            inputs = recipe.getInputs();
        } catch (RuntimeException | LinkageError e) {
            // Unreadable, and two unreadable recipes are not thereby the same. The identity of the
            // recipe object keeps them apart.
            return "?" + System.identityHashCode(recipe);
        }
        if (inputs == null || inputs.isEmpty()) {
            return "";
        }
        List<String> slots = new ArrayList<>(inputs.size());
        for (EmiIngredient ingredient : inputs) {
            if (ingredient == null) {
                continue;
            }
            List<String> keys = new ArrayList<>(4);
            try {
                for (EmiStack stack : ingredient.getEmiStacks()) {
                    Object key = Scan.key(stack);
                    if (key != null) {
                        keys.add(String.valueOf(key));
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                return "?" + System.identityHashCode(recipe);
            }
            if (keys.isEmpty()) {
                continue;
            }
            keys.sort(Comparator.naturalOrder());
            slots.add(String.join(",", keys));
        }
        slots.sort(Comparator.naturalOrder());
        return String.join("|", slots);
    }

    /** Recipes in a category, memoised: this is asked once per candidate while sorting. */
    int sizeOf(EmiRecipeCategory category) {
        if (category == null) {
            return 0;
        }
        return categorySize.computeIfAbsent(category, c -> {
            try {
                List<EmiRecipe> all = manager.getRecipes(c);
                return all == null ? 0 : all.size();
            } catch (RuntimeException | LinkageError e) {
                return 0;
            }
        });
    }

    static EmiRecipeCategory categoryOf(EmiRecipe recipe) {
        try {
            return recipe.getCategory();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static String idOf(EmiRecipe recipe) {
        try {
            ResourceLocation id = recipe.getId();
            return id == null ? "" : id.toString();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }
}
