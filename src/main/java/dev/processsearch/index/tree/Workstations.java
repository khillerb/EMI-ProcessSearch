package dev.processsearch.index.tree;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;

/**
 * The block that runs a category, so a node reads "Crushing Wheels" rather than "create:crushing".
 *
 * <p>Every graph wants this and each used to carry its own copy with its own cache. The lookup is
 * not free -- {@code getWorkstations} is a list build per call -- and a graph asks about the same
 * handful of categories over and over, so the memo is the point as much as the sharing is.
 */
final class Workstations {
    private final EmiRecipeManager manager;
    private final Map<EmiRecipeCategory, EmiIngredient> icons = new HashMap<>();

    Workstations(EmiRecipeManager manager) {
        this.manager = manager;
    }

    /** @return the first workstation, or {@link EmiStack#EMPTY} so callers never see null */
    EmiIngredient iconFor(EmiRecipeCategory category) {
        if (category == null) {
            return EmiStack.EMPTY;
        }
        return icons.computeIfAbsent(category, this::lookUp);
    }

    private EmiIngredient lookUp(EmiRecipeCategory category) {
        try {
            List<EmiIngredient> workstations = manager.getWorkstations(category);
            if (workstations != null) {
                for (EmiIngredient workstation : workstations) {
                    if (workstation != null && !workstation.isEmpty()) {
                        return workstation;
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // Fall through; the screen draws the category's own icon instead.
        }
        return EmiStack.EMPTY;
    }
}
