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

    /**
     * Another machine that does exactly this step, when one exists.
     *
     * <p>Plenty of packs ship two ways to grind an ore -- a Macerator and a set of Crushing Wheels
     * take the same thing in and give the same thing out. Picking one silently would hide a real
     * choice, so the plan names the runner-up.
     */
    EmiRecipeCategory equivalent;

    /** True when this node exists only to name an alternative, and has no inputs of its own. */
    boolean alternative;

    /**
     * Shared by machines that do the same job, so the screen can draw them as one block.
     *
     * <p>Set by a builder that already knows -- a plan picks one recipe and knows the runner-up it
     * passed over. Left null by the walk, where the screen works it out instead: two machines are
     * interchangeable when they lead to exactly the same items, which is true from that item's
     * point of view whichever direction the graph runs.
     */
    Object group;

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

    /** The other machine that does this same step, or null. */
    public EmiRecipeCategory equivalent() {
        return equivalent;
    }

    /** True when this node is the runner-up machine rather than the one the plan chose. */
    public boolean isAlternative() {
        return alternative;
    }

    /** What this machine is interchangeable with, or null when the screen should work it out. */
    public Object group() {
        return group;
    }

    @Override
    public String toString() {
        return "ProcessNode[" + category.getId() + " x" + recipes.size() + "]";
    }
}
