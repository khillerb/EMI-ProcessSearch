package dev.processsearch.index.rules;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One rule: a set of conditions, and the tokens a recipe earns for satisfying all of them.
 *
 * <p>This is the thing that lets a pack add facets for a mod nobody compiled against. Every
 * condition is answered from what EMI already hands over -- the category, the recipe id, the shape
 * of the ingredient lists -- or from the backing recipe's <em>type name</em>. Nothing here reads a
 * field or calls a method on a recipe, so unlike a hand-written {@code FacetSource} a rule cannot
 * throw on a mod it was not written for; the worst it can do is fail to match.
 *
 * <p>Conditions are ANDed. Within one condition a list is an OR: {@code categoryId} naming three
 * categories matches any of the three. A rule with no condition at all is rejected by
 * {@link FacetRuleLoader}, because it would tag every recipe in the pack.
 *
 * <p>Matching is split in two on purpose. {@link #matchesClass} depends only on the backing type,
 * and {@link #matchesCategory} only on the category, so {@link FacetRules} can answer both once per
 * (category, class) pair rather than once per recipe. Only {@link #matchesRecipe} -- the id regex
 * and the ingredient-shape tests -- has to run per recipe.
 */
public final class FacetRule {
    /** Names the rule in log messages. Never a token; it exists so a bad rule can be pointed at. */
    private final String id;

    private final Set<String> categoryIds;
    private final Set<String> categoryNamespaces;
    private final Pattern recipeIdPattern;
    private final String recipeClass;
    private final boolean matchSubclasses;
    private final Boolean requiresFluidInput;
    private final Boolean requiresFluidOutput;
    private final int minInputs;
    private final int maxInputs;
    private final int minOutputs;
    private final int maxOutputs;

    private final List<String> tokens;
    private final List<String> itemClasses;

    FacetRule(String id, Set<String> categoryIds, Set<String> categoryNamespaces,
              Pattern recipeIdPattern, String recipeClass, boolean matchSubclasses,
              Boolean requiresFluidInput, Boolean requiresFluidOutput,
              int minInputs, int maxInputs, int minOutputs, int maxOutputs,
              List<String> tokens, List<String> itemClasses) {
        this.id = id;
        this.categoryIds = categoryIds;
        this.categoryNamespaces = categoryNamespaces;
        this.recipeIdPattern = recipeIdPattern;
        this.recipeClass = recipeClass;
        this.matchSubclasses = matchSubclasses;
        this.requiresFluidInput = requiresFluidInput;
        this.requiresFluidOutput = requiresFluidOutput;
        this.minInputs = minInputs;
        this.maxInputs = maxInputs;
        this.minOutputs = minOutputs;
        this.maxOutputs = maxOutputs;
        this.tokens = tokens;
        this.itemClasses = itemClasses;
    }

    public String id() {
        return id;
    }

    /** Facet tokens, already sanitised. Reachable through {@code >}, {@code <} and {@code *}. */
    public List<String> tokens() {
        return tokens;
    }

    /** {@code ~} classes to hang on this recipe's outputs. Usually empty. */
    public List<String> itemClasses() {
        return itemClasses;
    }

    /**
     * True when this rule cannot be decided without the recipe's id, so a caller can skip resolving
     * one for the rules that never look at it.
     */
    public boolean needsRecipeId() {
        return recipeIdPattern != null;
    }

    /** True when this rule is keyed to a specific category, so {@link FacetRules} can bucket it. */
    boolean hasCategoryKey() {
        return !categoryIds.isEmpty() || !categoryNamespaces.isEmpty();
    }

    Set<String> categoryIds() {
        return categoryIds;
    }

    Set<String> categoryNamespaces() {
        return categoryNamespaces;
    }

    /**
     * @param categoryId the full {@code namespace:path}
     * @param namespace  its namespace, passed in rather than re-split per call
     */
    boolean matchesCategory(String categoryId, String namespace) {
        if (!categoryIds.isEmpty() && !categoryIds.contains(categoryId)) {
            return false;
        }
        return categoryNamespaces.isEmpty() || categoryNamespaces.contains(namespace);
    }

    /**
     * Whether the backing recipe's type satisfies this rule.
     *
     * <p>The whole superclass and interface graph is walked by <em>name</em>. That is what lets one
     * rule cover a mod's entire ecosystem the way {@code instanceof ProcessingRecipe} covers
     * Create's -- every Create addon built on the same base matches -- without the class ever
     * having to be on our classpath.
     *
     * @param type the backing recipe's class, or null when the recipe has no datapack recipe behind
     *             it. A rule naming a class never matches a recipe that has none.
     */
    boolean matchesClass(Class<?> type) {
        if (recipeClass == null) {
            return true;
        }
        if (type == null) {
            return false;
        }
        if (!matchSubclasses) {
            return recipeClass.equals(type.getName());
        }
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            if (recipeClass.equals(at.getName()) || matchesInterfaceOf(at)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesInterfaceOf(Class<?> type) {
        for (Class<?> face : type.getInterfaces()) {
            if (recipeClass.equals(face.getName()) || matchesInterfaceOf(face)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The per-recipe half: everything that cannot be decided from the category and the type alone.
     *
     * @param recipeId    the full recipe id, or null. A rule with an id pattern never matches a
     *                    recipe whose id could not be read.
     * @param fluidIn     whether any input was a fluid
     * @param fluidOut    whether any output was a fluid
     * @param inputSlots  ingredient slots, not stacks -- a tag counts once, not once per item it
     *                    accepts, which is the number a rule author means by "two inputs"
     * @param outputSlots output stacks
     */
    public boolean matchesRecipe(String recipeId, boolean fluidIn, boolean fluidOut,
                                 int inputSlots, int outputSlots) {
        if (requiresFluidInput != null && requiresFluidInput != fluidIn) {
            return false;
        }
        if (requiresFluidOutput != null && requiresFluidOutput != fluidOut) {
            return false;
        }
        if (inputSlots < minInputs || inputSlots > maxInputs) {
            return false;
        }
        if (outputSlots < minOutputs || outputSlots > maxOutputs) {
            return false;
        }
        // Last, and only once everything cheaper has passed: this is the one condition that costs
        // more than a comparison.
        if (recipeIdPattern != null) {
            return recipeId != null && recipeIdPattern.matcher(recipeId).find();
        }
        return true;
    }

    @Override
    public String toString() {
        return "FacetRule[" + id + " -> " + tokens + (itemClasses.isEmpty() ? "" : " ~" + itemClasses) + "]";
    }
}
