package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.List;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;

/**
 * One machine in the graph: every recipe of a single category that touches the parent item.
 *
 * <p>Aggregating by category rather than drawing a node per recipe is what keeps the overview
 * readable: the Crushing Wheels node stands for all 47 crushing recipes that take cobblestone, and
 * clicking it opens the list of the 47. Category is also the granularity the {@code >} and
 * {@code *} vocabulary uses, so what you can filter is what you can see.
 */
public final class ProcessNode {
    public final EmiRecipeCategory category;
    /** The workstation that runs it, so the node reads "Crushing Wheels", not "create:crushing". */
    public final EmiIngredient icon;
    public final List<EmiRecipe> recipes;
    public final ItemNode parent;
    public final int depth;

    private final List<ItemNode> items = new ArrayList<>(4);

    /** Items the width cap dropped. */
    int hiddenItems;

    ProcessNode(EmiRecipeCategory category, EmiIngredient icon, List<EmiRecipe> recipes,
                ItemNode parent) {
        this.category = category;
        this.icon = icon;
        this.recipes = recipes;
        this.parent = parent;
        this.depth = parent.depth + 1;
    }

    /** The far side of this step: outputs when following consumers, inputs when following producers. */
    public List<ItemNode> items() {
        return items;
    }

    void add(ItemNode item) {
        items.add(item);
    }

    public int hiddenItems() {
        return hiddenItems;
    }

    public int recipeCount() {
        return recipes.size();
    }

    @Override
    public String toString() {
        return "ProcessNode[" + category.getId() + " x" + recipes.size() + "]";
    }
}
