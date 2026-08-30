package dev.processsearch.index;

import java.util.Iterator;
import java.util.List;

import com.mojang.datafixers.util.Pair;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiStack;
import dev.processsearch.ProcessSearch;
import dev.processsearch.ProcessSearchConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/**
 * A cheap answer to "are these the same recipes I already indexed?".
 *
 * <p>EMI reloads on every world join and hands back a completely new set of recipe objects, but in
 * the overwhelmingly common case -- leaving a singleplayer world and joining a server running the
 * same pack -- the recipe <em>data</em> is identical and rebuilding the index is pure waste. This is
 * what makes it safe to skip: if the fingerprint matches, the previous index still describes the
 * world you just joined.
 *
 * <p>Every sum here is order-independent, because reload order is not guaranteed to be stable
 * between reloads and a reordering is not a change. Sums rather than XOR, since XOR would cancel a
 * pair of identical entries instead of counting them.
 *
 * <p>{@link System#identityHashCode} is fine as a key hash because this only ever compares two
 * fingerprints taken within one JVM run, which is exactly how long the cached index lives.
 */
public record Fingerprint(int categoryCount, int recipeCount, long recipeHash, long tagHash,
                          int configGeneration) {

    /**
     * @param categories the same excluded-category-filtered list the build would walk, so that the
     *                   fingerprint describes exactly what the index would contain
     * @return null if EMI's state could not be read, which callers must treat as "not equal to
     *         anything" and rebuild
     */
    public static Fingerprint of(EmiRecipeManager manager, List<EmiRecipeCategory> categories) {
        try {
            long recipeHash = 0;
            int recipeCount = 0;
            for (EmiRecipeCategory category : categories) {
                List<EmiRecipe> recipes = manager.getRecipes(category);
                int size = recipes == null ? 0 : recipes.size();
                recipeHash += mix(category.getId().hashCode() * 31L + size);
                if (recipes == null) {
                    continue;
                }
                recipeCount += size;
                for (EmiRecipe recipe : recipes) {
                    if (recipe != null) {
                        recipeHash += mix(hash(recipe));
                    }
                }
            }
            return new Fingerprint(categories.size(), recipeCount, recipeHash, computeTagHash(),
                    ProcessSearchConfig.generation());
        } catch (RuntimeException | LinkageError e) {
            // Degrades to always rebuilding, which is the safe direction.
            ProcessSearch.LOGGER.warn("Could not fingerprint EMI's recipes, will rebuild: {}", e.toString());
            return null;
        }
    }

    /**
     * Recipe id, input count, and every output stack in full.
     *
     * <p>The flattened contents of {@code getInputs()} are deliberately left out. Resolving a tag
     * ingredient to the stacks it accepts is the expensive half of the index build, and doing it
     * here would defeat the entire point of a cheap check. Outputs need no such resolution -- they
     * are already {@link EmiStack}s -- so they are hashed completely, which is also where a retuned
     * recipe is most likely to show up.
     */
    private static long hash(EmiRecipe recipe) {
        ResourceLocation id = recipe.getId();
        long h = id == null ? 0 : id.hashCode();
        List<?> inputs = recipe.getInputs();
        h = h * 31 + (inputs == null ? 0 : inputs.size());
        List<EmiStack> outputs = recipe.getOutputs();
        if (outputs != null) {
            for (EmiStack output : outputs) {
                if (output == null) {
                    continue;
                }
                h = h * 31 + System.identityHashCode(output.getKey());
                h = h * 31 + output.getAmount();
                h = h * 31 + Float.floatToIntBits(output.getChance());
            }
        }
        return h;
    }

    /**
     * Tags are server-synced, and {@code Scan.addInput} flattens tag ingredients through them, so
     * two servers with identical recipe json but different tag contents produce different indexes.
     * One pass over item and fluid tag membership is cheap and closes that hole.
     */
    private static long computeTagHash() {
        return tagsOf(BuiltInRegistries.ITEM) + tagsOf(BuiltInRegistries.FLUID);
    }

    private static <T> long tagsOf(Registry<T> registry) {
        long total = 0;
        Iterator<Pair<TagKey<T>, HolderSet.Named<T>>> tags = registry.getTags().iterator();
        while (tags.hasNext()) {
            Pair<TagKey<T>, HolderSet.Named<T>> tag = tags.next();
            HolderSet.Named<T> holders = tag.getSecond();
            long h = tag.getFirst().location().hashCode() * 31L + holders.size();
            long members = 0;
            for (Holder<T> holder : holders) {
                members += mix(System.identityHashCode(holder.value()));
            }
            total += mix(h + members);
        }
        return total;
    }

    /** splitmix64 finalizer: spreads a weak hash across all 64 bits before it is summed. */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
