package dev.processsearch.mixin;

import java.util.List;
import java.util.Map;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.processsearch.ProcessSearch;
import dev.processsearch.recipe.RecipeFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Filters the recipe list behind a category page.
 *
 * <p>{@code setPages} is the single choke point every route into the recipe screen goes through --
 * {@code displayRecipes}, {@code displayUses}, {@code displayRecipeCategory},
 * {@code displayAllRecipes} and {@code displayRecipe} all end here -- so narrowing the map at its
 * head is what turns two thousand mixing pages into the dozen that are actually heated. The JEI
 * version needed two hooks for the same thing.
 *
 * <p>The guards live in {@link RecipeFilter}: it returns the map unchanged when a caller asked for
 * one specific recipe, and when filtering would empty every category. The second one matters more
 * on EMI than it did on JEI, because {@code setPages} with an empty map does nothing at all and the
 * screen would simply never open.
 */
@Mixin(value = EmiApi.class, remap = false)
public class EmiApiMixin {

    @ModifyVariable(method = "setPages(Ljava/util/Map;Ldev/emi/emi/api/stack/EmiIngredient;)V",
            at = @At("HEAD"), argsOnly = true, index = 0, remap = false)
    private static Map<EmiRecipeCategory, List<EmiRecipe>> processsearch$narrowPages(
            Map<EmiRecipeCategory, List<EmiRecipe>> pages) {
        try {
            return RecipeFilter.apply(pages);
        } catch (RuntimeException | LinkageError e) {
            // Showing every page is a worse answer, but a recipe screen that fails to open is a
            // broken mod.
            ProcessSearch.LOGGER.error("Recipe page filter failed; showing everything", e);
            return pages;
        }
    }
}
