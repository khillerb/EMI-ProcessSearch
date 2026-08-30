package dev.processsearch.index.sources;

import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.processsearch.index.FacetSource;
import dev.processsearch.index.Facets;
import dev.processsearch.index.Scan;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Create's heat and speed requirements.
 *
 * <p>Nearly every Create machine -- mixing, crushing, milling, pressing, deploying, item
 * application, spout filling, item draining, sawing, all four fan processes, packing, compacting --
 * and every Create addon recipe built on the same base share one superclass,
 * {@link ProcessingRecipe}. So a single {@code instanceof} covers the whole ecosystem, which in
 * Prominence II means Create Crafts &amp; Additions, Create: New Age, Steam 'n' Rails, Copycats+,
 * Create Questing, Testosterone and Estrogen as well as Create itself.
 *
 * <p>It reads exactly what a category cannot express: heat requirement is a <em>field</em> on the
 * recipe, not a separate category, so "made by a heated mixer" is invisible to category browsing and
 * needs precisely this.
 *
 * <p>Create's {@code CreateEmiRecipe} sets its id from the datapack recipe and
 * {@code EmiRecipe.getBackingRecipe()} resolves ids through the vanilla recipe manager, so reaching
 * the recipe needs no reflection into Create's EMI wrappers and no mixin on Create.
 *
 * <p>This class must only be loaded when Create is present; it is registered behind a
 * {@code FabricLoader.isModLoaded("create")} check so the JVM never tries to link these types
 * otherwise.
 */
public final class CreateFacets implements FacetSource {
    @Override
    public void collect(EmiRecipeCategory category, EmiRecipe recipe, Recipe<?> backing, Scan scan) {
        if (!(backing instanceof ProcessingRecipe<?> processing)) {
            return;
        }
        scan.tokens.add(heatToken(processing.getRequiredHeat()));

        String speed = Facets.speedBucket(processing.getProcessingDuration());
        if (speed != null) {
            scan.tokens.add(speed);
        }
    }

    private static String heatToken(HeatCondition heat) {
        if (heat == null) {
            return Facets.HEAT_NONE;
        }
        return switch (heat) {
            case HEATED -> Facets.HEAT_HEATED;
            case SUPERHEATED -> Facets.HEAT_SUPERHEATED;
            default -> Facets.HEAT_NONE;
        };
    }
}
