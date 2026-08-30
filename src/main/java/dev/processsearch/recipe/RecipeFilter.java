package dev.processsearch.recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;
import dev.processsearch.recipe.FacetQueryClauses.Clause;

/**
 * Applies EMI's search box to the recipe screen as well as the item list.
 *
 * <p>Filtering the item list never solved the actual problem: opening the uses of a Mechanical Mixer
 * still hands you two thousand pages. This reuses the text already in EMI's search box rather than
 * adding a second one, so the query that found the item also narrows its recipes.
 *
 * <p>The grammar lives in {@link FacetQueryClauses}, shared with the process tree so that a query
 * which hides a recipe page hides the same recipe in the tree.
 */
public final class RecipeFilter {
    private static int lastTotal;
    private static int lastKept;

    private RecipeFilter() {}

    /** How many of how many survived the last pass, for the notice on the recipe screen. */
    public static int lastKept() {
        return lastKept;
    }

    public static int lastTotal() {
        return lastTotal;
    }

    public static boolean isActive() {
        return lastTotal > 0 && lastKept < lastTotal;
    }

    /**
     * Narrows the category-to-recipes map behind the recipe screen.
     *
     * @return {@code pages} filtered to the recipes matching the current query, or {@code pages}
     *         itself when nothing applies
     */
    public static Map<EmiRecipeCategory, List<EmiRecipe>> apply(
            Map<EmiRecipeCategory, List<EmiRecipe>> pages) {
        if (pages == null || pages.isEmpty() || !ProcessSearchConfig.recipePageFilter()) {
            return pages;
        }
        if (!ProcessIndex.isReady()) {
            // Better to show everything than to silently hide pages using a half-built index.
            clear();
            return pages;
        }

        int total = 0;
        for (List<EmiRecipe> list : pages.values()) {
            if (list != null) {
                total += list.size();
            }
        }
        if (total <= 1) {
            // One recipe in the whole map means a caller asked for that specific recipe --
            // EmiApi.displayRecipe or focusRecipe. Quietly dropping it would be wrong.
            clear();
            return pages;
        }

        List<Clause> clauses = FacetQueryClauses.parse(ProcessIndex.currentFilterText());
        if (clauses.isEmpty()) {
            clear();
            return pages;
        }

        Map<EmiRecipeCategory, List<EmiRecipe>> filtered = new LinkedHashMap<>();
        int kept = 0;
        for (Map.Entry<EmiRecipeCategory, List<EmiRecipe>> entry : pages.entrySet()) {
            List<EmiRecipe> list = entry.getValue();
            if (list == null || list.isEmpty()) {
                continue;
            }
            List<EmiRecipe> survivors = new ArrayList<>(list.size());
            for (EmiRecipe recipe : list) {
                Set<String> facets = ProcessIndex.facetsForRecipe(entry.getKey(), recipe);
                if (FacetQueryClauses.matchesAny(clauses, facets)) {
                    survivors.add(recipe);
                }
            }
            if (!survivors.isEmpty()) {
                filtered.put(entry.getKey(), survivors);
                kept += survivors.size();
            }
        }

        lastTotal = total;
        if (filtered.isEmpty()) {
            // EMI's setPages does nothing at all with an empty map, so the recipe screen would
            // simply never open. Fall through to the unfiltered list and let the notice say nothing
            // was filtered.
            lastKept = total;
            return pages;
        }
        lastKept = kept;
        return filtered;
    }

    private static void clear() {
        lastTotal = 0;
        lastKept = 0;
    }

    public static void invalidate() {
        clear();
    }
}
