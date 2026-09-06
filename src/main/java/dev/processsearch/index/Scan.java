package dev.processsearch.index;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/**
 * Scratch buffer for one recipe: the facet tokens it earned, and the ingredient keys playing each
 * role.
 *
 * <p>Keys are {@code Item} or {@code Fluid} instances, which is exactly what {@code EmiStack.getKey}
 * hands back. Both are registry singletons, so they work directly as map keys, and using them rather
 * than stacks collapses every count/NBT variant of an item onto one entry -- which is what you want
 * when the question is "can this machine make this thing", and is why the index stays small in a
 * pack this size.
 *
 * <p>{@code fluid.*} and {@code chance.*} fall out of the stacks themselves, so they are recorded
 * here rather than in a mod-specific source and apply to every mod.
 */
public final class Scan {
    public final Set<String> tokens = new HashSet<>();
    /**
     * {@code ~} classes this recipe hangs on its outputs.
     *
     * <p>Separate from {@link #tokens} because it is a different question: those describe the
     * recipe, these describe the thing that came out of it, and they land in different maps. Only
     * the rule engine fills this -- {@code ~dye} used to be a special case in the index build, and
     * is now just a rule like any other.
     */
    public final Set<String> itemClasses = new HashSet<>();
    public final List<Object> outputs = new ArrayList<>();
    public final List<Object> inputs = new ArrayList<>();

    private boolean sawOutput;
    private boolean randomOutput;
    private int inputSlots;
    private int outputSlots;

    public void reset() {
        tokens.clear();
        itemClasses.clear();
        outputs.clear();
        inputs.clear();
        sawOutput = false;
        randomOutput = false;
        inputSlots = 0;
        outputSlots = 0;
    }

    /**
     * Ingredient <em>slots</em>, not stacks: a tag counts once rather than once per item it accepts.
     *
     * <p>That is the number a rule author means by "two inputs", and it is the only one that is
     * stable -- {@link #inputs} is the flattened form, so one tag ingredient can put hundreds of
     * entries in it.
     */
    public int inputSlots() {
        return inputSlots;
    }

    /** Output stacks that were non-empty. */
    public int outputSlots() {
        return outputSlots;
    }

    public boolean hasRoles() {
        return !outputs.isEmpty() || !inputs.isEmpty();
    }

    public void addOutput(EmiStack stack) {
        Object k = key(stack);
        if (k == null) {
            return;
        }
        outputSlots++;
        outputs.add(k);
        sawOutput = true;
        if (k instanceof Fluid) {
            tokens.add(Facets.FLUID_OUT);
        }
        // Create and MI both call setChance on their EMI output stacks, so this separates a
        // crushing recipe you can build a ratio around from one you cannot, for any mod that
        // bothers to set it.
        if (stack.getChance() < 1.0F) {
            randomOutput = true;
        }
    }

    public void addInput(EmiIngredient ingredient) {
        if (ingredient == null) {
            return;
        }
        inputSlots++;
        // A tag ingredient flattens to every stack it accepts; each is a real way to feed the
        // recipe, so each earns the used-in entry.
        for (EmiStack stack : ingredient.getEmiStacks()) {
            Object k = key(stack);
            if (k == null) {
                continue;
            }
            inputs.add(k);
            if (k instanceof Fluid) {
                tokens.add(Facets.FLUID_IN);
            }
        }
    }

    /** Call once, after every role has been added. */
    public void finishChance() {
        if (sawOutput) {
            tokens.add(randomOutput ? Facets.CHANCE_RANDOM : Facets.CHANCE_CERTAIN);
        }
    }

    /**
     * The first stack of an ingredient that the index can actually name.
     *
     * <p>A hovered slot may hold a tag, and a tag flattens to anything from one stack to two
     * hundred. Everything that starts from "what is the cursor on" needs one representative, and
     * needs it chosen the same way, or the tree and the plan would disagree about what you pointed
     * at.
     *
     * @return null when nothing in the ingredient is an item or a fluid
     */
    public static EmiStack firstKeyable(EmiIngredient ingredient) {
        if (ingredient == null) {
            return null;
        }
        try {
            for (EmiStack stack : ingredient.getEmiStacks()) {
                if (key(stack) != null) {
                    return stack;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return null;
    }

    /** @return the registry singleton to key on, or null if this is not an item or fluid. */
    public static Object key(EmiStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Object k;
        try {
            k = stack.getKey();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return (k instanceof Item || k instanceof Fluid) ? k : null;
    }
}
