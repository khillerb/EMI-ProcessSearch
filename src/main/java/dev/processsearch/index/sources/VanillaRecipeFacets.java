package dev.processsearch.index.sources;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.processsearch.index.FacetSource;
import dev.processsearch.index.Facets;
import dev.processsearch.index.Scan;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * The two facets that come from the plain datapack recipe: whether it is shapeless, and whether it
 * is a pack/unpack round trip.
 *
 * <p>{@code packing} is deliberately scoped to {@code minecraft}-namespace categories. Detecting it
 * means resolving every ingredient, which means resolving tags, and this pack has 445 mods -- paying
 * that everywhere to catch nugget/ingot/block round trips, which only ever appear in crafting, would
 * dominate the build.
 */
public final class VanillaRecipeFacets implements FacetSource {
    private final RegistryAccess registries;

    public VanillaRecipeFacets(RegistryAccess registries) {
        this.registries = registries;
    }

    @Override
    public void collect(EmiRecipeCategory category, EmiRecipe recipe, Recipe<?> backing, Scan scan) {
        if (backing == null) {
            return;
        }
        // Create's automatic_shapeless category already carries "shapeless" inside its own name and
        // matching is by substring, so >shapeless catches both without special-casing it.
        if (backing instanceof ShapelessRecipe) {
            scan.tokens.add(Facets.SHAPELESS);
        }

        if (backing.isSpecial() || !"minecraft".equals(category.getId().getNamespace())) {
            return;
        }

        ItemStack result;
        try {
            result = backing.getResultItem(registries);
        } catch (RuntimeException | LinkageError e) {
            // Runs against modded Recipe implementations far more often than vanilla ones, and one
            // that throws would otherwise take down the whole index build.
            return;
        }
        if (result == null || result.isEmpty()) {
            return;
        }
        if (Facets.isPacking(backing, result)) {
            scan.tokens.add(Facets.PACKING);
        }
    }
}
