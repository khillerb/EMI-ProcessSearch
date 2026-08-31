package dev.processsearch.index.sources;

import java.util.List;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.processsearch.index.FacetSource;
import dev.processsearch.index.Facets;
import dev.processsearch.index.Scan;
import dev.processsearch.index.rules.FacetRule;
import dev.processsearch.index.rules.FacetRules;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

/**
 * The data-driven source: facets for mods nobody wrote a {@link FacetSource} for.
 *
 * <p>{@link CreateFacets} and {@link MachineRecipeFacets} exist because Create's heat condition and
 * MI's EU cost are <em>fields</em>, and reading a field means compiling against the mod. That is a
 * fine trade for two mods and a bad one for four hundred: every new mod would cost a vendored jar,
 * a build change, a mod-loaded gate and a config toggle.
 *
 * <p>So this asks only the questions EMI can already answer -- which category, which recipe id,
 * how many ingredient slots, fluids or not -- plus the backing recipe's <em>type name</em>, which
 * needs no linkage and is what lets one rule cover a mod's whole ecosystem. A pack adds facets by
 * dropping a JSON file in {@code config/processsearch/facet_rules/}.
 *
 * <p>Registered unconditionally, with no {@code isModLoaded} check: rules select themselves by
 * matching, so a rule for a mod that is not installed simply never fires.
 */
public final class RuleFacets implements FacetSource {
    private final FacetRules rules;

    // Single-slot memo for the last (category, backing type) seen.
    //
    // The index build walks a category at a time and a category almost always has one backing
    // recipe class, so consecutive recipes hit this nearly every time. What it saves is not the
    // rule matching -- FacetRules memoises that already -- but the ResourceLocation.toString()
    // that would otherwise allocate a string for every recipe in the pack just to look the answer
    // up again. Identity comparisons, because these are the very same objects each time.
    private EmiRecipeCategory lastCategory;
    private Class<?> lastBacking;
    private List<FacetRule> lastCandidates = List.of();

    public RuleFacets(FacetRules rules) {
        this.rules = rules == null ? FacetRules.EMPTY : rules;
    }

    @Override
    public void collect(EmiRecipeCategory category, EmiRecipe recipe, Recipe<?> backing, Scan scan) {
        if (rules.isEmpty()) {
            return;
        }
        List<FacetRule> candidates = candidatesFor(category, backing == null ? null : backing.getClass());
        if (candidates.isEmpty()) {
            return;
        }

        // Both were recorded as the stacks went by, so neither costs a second pass over them.
        boolean fluidIn = scan.tokens.contains(Facets.FLUID_IN);
        boolean fluidOut = scan.tokens.contains(Facets.FLUID_OUT);

        // Resolved at most once, and only if some surviving rule actually asks for it: getId() is
        // the one thing here that can throw, and most rules never look at it.
        String recipeId = null;
        boolean resolvedId = false;

        for (FacetRule rule : candidates) {
            if (rule.needsRecipeId() && !resolvedId) {
                recipeId = idOf(recipe);
                resolvedId = true;
            }
            if (!rule.matchesRecipe(recipeId, fluidIn, fluidOut,
                    scan.inputSlots(), scan.outputSlots())) {
                continue;
            }
            scan.tokens.addAll(rule.tokens());
            scan.itemClasses.addAll(rule.itemClasses());
        }
    }

    private List<FacetRule> candidatesFor(EmiRecipeCategory category, Class<?> backing) {
        if (category == lastCategory && backing == lastBacking) {
            return lastCandidates;
        }
        List<FacetRule> found = rules.candidates(category.getId().toString(), backing);
        lastCategory = category;
        lastBacking = backing;
        lastCandidates = found;
        return found;
    }

    private static String idOf(EmiRecipe recipe) {
        try {
            ResourceLocation id = recipe.getId();
            return id == null ? null : id.toString();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
