package dev.processsearch.index.sources;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import aztech.modern_industrialization.api.energy.CableTier;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.processsearch.index.FacetSource;
import dev.processsearch.index.Facets;
import dev.processsearch.index.Scan;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Modern Industrialization.
 *
 * <p>MI is shaped the same way Create is: one {@link MachineRecipe} class backs every
 * machine it ships, carrying {@code eu} and {@code duration}. So this single source covers the whole
 * mod, and any addon built on the same recipe type with it.
 *
 * <p>The facet that matters is voltage. EU/t is a hard automation constraint -- an LV recipe and an
 * HV recipe are different problems -- and it is shown on the recipe but cannot be filtered by.
 *
 * <p>Output probabilities are deliberately not read here. MI's own EMI wrapper calls
 * {@code EmiStack.setChance}, so {@code chance.certain} / {@code chance.random} already fall out of
 * {@link Scan} for free, the same way they do for every other mod.
 *
 * <p>Only loaded when MI is present; registration is behind a
 * {@code FabricLoader.isModLoaded("modern_industrialization")} check.
 */
public final class MachineRecipeFacets implements FacetSource {
    /**
     * Sorted ascending, resolved once per index build. {@code allTiers()} rather than the five
     * built-in constants, so tiers registered by addons are included instead of silently landing in
     * whichever bucket happened to be last.
     */
    private final List<CableTier> tiers;

    public MachineRecipeFacets() {
        this.tiers = CableTier.allTiers().stream()
                .sorted(Comparator.comparingLong(CableTier::getEu))
                .toList();
    }

    @Override
    public void collect(EmiRecipeCategory category, EmiRecipe recipe, Recipe<?> backing, Scan scan) {
        if (!(backing instanceof MachineRecipe machine)) {
            return;
        }
        String tier = euTier(machine.eu);
        if (tier != null) {
            scan.tokens.add(tier);
        }
        String speed = Facets.speedBucket(machine.duration);
        if (speed != null) {
            scan.tokens.add(speed);
        }
    }

    /** @return {@code eu.lv} .. {@code eu.superconductor}, or null if MI registered no tiers. */
    private String euTier(int eu) {
        if (tiers.isEmpty()) {
            return null;
        }
        for (CableTier tier : tiers) {
            if (tier.getEu() >= eu) {
                return "eu." + Facets.sanitize(tier.name.toLowerCase(Locale.ROOT));
            }
        }
        // Above every registered tier. Naming it after the top tier would be a lie, so say so.
        return "eu.above_max";
    }
}
