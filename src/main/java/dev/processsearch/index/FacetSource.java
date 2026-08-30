package dev.processsearch.index;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Contributes recipe-level property tokens -- the things that are a <em>field</em> on a recipe
 * rather than a category, and so are invisible to category browsing.
 *
 * <p>Unlike the NeoForge version's adapter chain, sources are additive and none of them claim a
 * recipe: EMI hands back inputs and outputs itself, so roles have exactly one source and there is
 * nothing left to arbitrate.
 */
public interface FacetSource {
    /**
     * @param category the EMI category the recipe belongs to
     * @param recipe   the EMI recipe
     * @param backing  the datapack recipe behind it, or null when the category is synthetic
     * @param scan     buffer to add tokens to
     */
    void collect(EmiRecipeCategory category, EmiRecipe recipe, Recipe<?> backing, Scan scan);
}
