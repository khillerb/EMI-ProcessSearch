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
    public final List<Object> outputs = new ArrayList<>();
    public final List<Object> inputs = new ArrayList<>();

    private boolean sawOutput;
    private boolean randomOutput;

    public void reset() {
        tokens.clear();
        outputs.clear();
        inputs.clear();
        sawOutput = false;
        randomOutput = false;
    }

    public boolean hasRoles() {
        return !outputs.isEmpty() || !inputs.isEmpty();
    }

    public void addOutput(EmiStack stack) {
        Object k = key(stack);
        if (k == null) {
            return;
        }
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
