package dev.processsearch.index;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.processsearch.ProcessSearchConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluid;

/** The searchable token vocabulary, and the rules that decide which tokens a recipe earns. */
public final class Facets {
    /** Separates a process from a property: {@code mixing/heat.heated}. */
    public static final char COMPOUND = '/';

    // -- recipe properties. Dotted so >heat.heated cannot match heat.superheated by substring.
    public static final String HEAT_NONE = "heat.none";
    public static final String HEAT_HEATED = "heat.heated";
    public static final String HEAT_SUPERHEATED = "heat.superheated";

    public static final String FLUID_IN = "fluid.in";
    public static final String FLUID_OUT = "fluid.out";

    public static final String CHANCE_CERTAIN = "chance.certain";
    public static final String CHANCE_RANDOM = "chance.random";

    public static final String SPEED_FAST = "speed.fast";
    public static final String SPEED_NORMAL = "speed.normal";
    public static final String SPEED_SLOW = "speed.slow";

    public static final String SHAPELESS = "shapeless";
    public static final String PACKING = "packing";

    /**
     * Both a recipe facet and an item class, which is why it is one constant rather than two.
     *
     * <p>No longer produced by any code here: it comes out of the rule engine like every other
     * data-driven token. The constant survives because the legacy {@code dyeCategoryIds} and
     * {@code dyeRecipePatterns} config keys are translated into rules that emit exactly this.
     */
    public static final String DYE = "dye";

    // -- item classes, reached with the ~ prefix
    public static final String CLASS_COMPRESSED = "compressed";
    public static final String CLASS_DECORATIVE = "decorative";
    public static final String CLASS_TRIM = "trim";

    /**
     * The tokens every mod earns without anyone writing anything: they fall out of the ingredient
     * stacks themselves rather than out of a source or a rule.
     *
     * <p>Named as a set so {@code /processsearch gaps} can subtract them. A category whose recipes
     * earned only these is a category we know the name of and nothing else, which is exactly the
     * list of mods worth writing a rule for.
     */
    public static final Set<String> UNIVERSAL =
            Set.of(FLUID_IN, FLUID_OUT, CHANCE_CERTAIN, CHANCE_RANDOM);

    /** True when {@code token} is one every mod gets for free, so it says nothing about coverage. */
    public static boolean isUniversal(String token) {
        return UNIVERSAL.contains(token);
    }

    private Facets() {}

    /**
     * EMI tokenises the filter box on whitespace and on {@code |}, so a token containing either
     * could never be typed. Lowercase and collapse everything else to underscores.
     */
    public static String sanitize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).trim();
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':' || c == '.' || c == '/' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore && sb.length() > 0) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * Builds the tokens a recipe contributes: every process bare, plus one {@code process/property}
     * compound per pair.
     *
     * <p>The compounds are the whole point. Search tokens are intersected independently, so
     * {@code >mixing >heat.heated} only means "made by mixing" AND "made by something heated" --
     * an item made by cold mixing and, separately, by heated crushing satisfies both and matches
     * wrongly. A single {@code >mixing/heat.heated} token cannot be fooled that way.
     *
     * <p>Bare processes and bare properties stay reachable because matching is by substring, and
     * both are substrings of the compound.
     */
    public static Set<String> combine(Set<String> processes, Set<String> properties) {
        Set<String> out = new HashSet<>(processes.size() + properties.size() * 4);
        combineInto(out, processes, properties);
        return out;
    }

    /**
     * Same, into a caller-supplied set.
     *
     * <p>The index build runs this once per recipe, so handing in a reused scratch set rather than
     * allocating one each time is worth the slightly clumsier signature.
     */
    public static void combineInto(Set<String> out, Set<String> processes, Set<String> properties) {
        out.addAll(processes);
        out.addAll(properties);
        if (processes.isEmpty() || properties.isEmpty()) {
            return;
        }
        for (String process : processes) {
            for (String property : properties) {
                out.add(process + COMPOUND + property);
            }
        }
    }

    /** Duration in ticks, bucketed. Raw values would put hundreds of dead tokens in the index. */
    public static String speedBucket(int ticks) {
        if (ticks <= 0) {
            return null;
        }
        if (ticks < 100) {
            return SPEED_FAST;
        }
        return ticks <= 400 ? SPEED_NORMAL : SPEED_SLOW;
    }

    /**
     * True for pure NxN pack / 1-to-N unpack recipes -- nugget to ingot to block and back.
     *
     * <p>Only ever called for {@code minecraft}-namespace categories. It resolves every ingredient,
     * which means resolving tags, and paying that across four hundred mods worth of recipes to find
     * a facet that only matters for crafting would be a bad trade.
     */
    public static boolean isPacking(Recipe<?> recipe, ItemStack result) {
        List<Ingredient> ingredients;
        try {
            ingredients = recipe.getIngredients();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        int count = 0;
        String single = null;
        for (Ingredient ingredient : ingredients) {
            ItemStack[] items;
            try {
                items = ingredient.getItems();
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
            if (items.length == 0) {
                continue;
            }
            count++;
            String id = itemId(items[0].getItem());
            if (single == null) {
                single = id;
            } else if (!single.equals(id)) {
                return false;
            }
        }
        if (single == null) {
            return false;
        }
        // N identical inputs collapsing to one output, or one input exploding into N.
        boolean packs = (count == 4 || count == 9) && result.getCount() == 1;
        boolean unpacks = count == 1 && (result.getCount() == 4 || result.getCount() == 9);
        return packs || unpacks;
    }

    private static String itemId(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "" : id.toString();
    }

    /**
     * Item classification, reached with {@code ~}. These describe the item itself rather than any
     * recipe, which is why they get their own prefix -- {@code -~decorative} reads as "not a
     * furniture block", where a recipe-shaped exclusion would have been much harder to phrase.
     */
    public record ItemClassRules(Set<String> decorativeMods, Set<String> trimMods,
                                 Set<String> compressedMods) {
        public static ItemClassRules fromConfig() {
            return new ItemClassRules(
                    Set.copyOf(ProcessSearchConfig.decorativeModIds()),
                    Set.copyOf(ProcessSearchConfig.trimModIds()),
                    Set.copyOf(ProcessSearchConfig.compressedModIds()));
        }

        /** @param key an Item or Fluid registry singleton, as stored in the index */
        public Set<String> classify(Object key) {
            String namespace = namespaceOf(key);
            if (namespace == null) {
                return Set.of();
            }
            Set<String> classes = new HashSet<>(3);
            if (compressedMods.contains(namespace)) {
                classes.add(CLASS_COMPRESSED);
            }
            if (decorativeMods.contains(namespace)) {
                classes.add(CLASS_DECORATIVE);
            }
            if (trimMods.contains(namespace)) {
                classes.add(CLASS_TRIM);
            }
            return classes;
        }

        private static String namespaceOf(Object key) {
            ResourceLocation id = null;
            if (key instanceof Item item) {
                id = BuiltInRegistries.ITEM.getKey(item);
            } else if (key instanceof Fluid fluid) {
                id = BuiltInRegistries.FLUID.getKey(fluid);
            }
            return id == null ? null : id.getNamespace();
        }
    }
}
