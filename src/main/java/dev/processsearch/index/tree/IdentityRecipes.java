package dev.processsearch.index.tree;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.processsearch.index.Scan;

/**
 * Recipes whose every output is something they also consume.
 *
 * <p>Which is precisely anvil repairing, grindstone and enchanting: steps that hand back an item of
 * the same kind and so loop tools endlessly back on themselves. Because keys come from
 * {@link Scan#key}, an enchanted sword and a plain one are the same item here, which is what makes
 * enchanting fall out of this rule rather than needing to be named.
 *
 * <p>Shared by the tree walk and the route search. The tree drops these so branches do not double
 * back on themselves; the route search drops them so a path is not padded with steps that change
 * nothing. Same rule, same reason, so it lives in one place -- and the memo matters more for
 * routing, where a busy item can be re-examined from both frontiers.
 */
final class IdentityRecipes {
    /**
     * Two cheap gates before the expensive part. A repair-shaped recipe hands back a single item
     * and takes a handful of specific ones; anything with several outputs, or an input that is a
     * large tag, is a real transformation and not worth flattening to confirm.
     */
    private static final int MAX_OUTPUTS = 2;
    private static final int MAX_INPUT_STACKS = 64;

    /** Identity detection resolves tag ingredients, so a busy recipe is worth remembering. */
    private final Map<EmiRecipe, Boolean> cache = new IdentityHashMap<>();

    boolean test(EmiRecipe recipe) {
        if (recipe == null) {
            return false;
        }
        Boolean cached = cache.get(recipe);
        if (cached != null) {
            return cached;
        }
        boolean identity = compute(recipe);
        cache.put(recipe, identity);
        return identity;
    }

    private static boolean compute(EmiRecipe recipe) {
        try {
            List<EmiStack> outputs = recipe.getOutputs();
            List<EmiIngredient> inputs = recipe.getInputs();
            if (outputs == null || outputs.isEmpty() || inputs == null || inputs.isEmpty()) {
                return false;
            }
            if (outputs.size() > MAX_OUTPUTS) {
                return false;
            }
            Set<Object> inputKeys = new HashSet<>();
            for (EmiIngredient ingredient : inputs) {
                if (ingredient == null) {
                    continue;
                }
                List<EmiStack> stacks = ingredient.getEmiStacks();
                if (stacks.size() > MAX_INPUT_STACKS) {
                    return false;
                }
                for (EmiStack stack : stacks) {
                    Object key = Scan.key(stack);
                    if (key != null) {
                        inputKeys.add(key);
                    }
                }
            }
            if (inputKeys.isEmpty()) {
                return false;
            }
            boolean sawOutput = false;
            for (EmiStack output : outputs) {
                Object key = Scan.key(output);
                if (key == null) {
                    continue;
                }
                sawOutput = true;
                if (!inputKeys.contains(key)) {
                    return false;
                }
            }
            return sawOutput;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
