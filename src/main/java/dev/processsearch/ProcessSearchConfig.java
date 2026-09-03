package dev.processsearch;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.processsearch.index.Facets;
import dev.processsearch.index.Role;
import dev.processsearch.index.rules.FacetRule;
import dev.processsearch.index.rules.FacetRuleLoader;
import dev.processsearch.index.rules.FacetRules;
import dev.processsearch.input.HotKey;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

/**
 * Client config, at {@code config/processsearch.json}.
 *
 * <p>Gson rather than a config library, because Minecraft already ships Gson and a client mod meant
 * to be dropped into a 445-mod pack should not add a dependency to store twelve values. The file is
 * rewritten on every load, which both refreshes the {@code _comment} help and migrates newly added
 * keys into an older file.
 *
 * <p>{@link #roleFor(char)} is read from a mixin on EMI's search thread, so the whole config is
 * swapped as one immutable object behind a volatile rather than mutated in place.
 */
public final class ProcessSearchConfig {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            // Without this Gson would write the prefixes as > and <, which is legal JSON
            // and completely unreadable in the one file a player is meant to edit by hand.
            .disableHtmlEscaping()
            .create();

    /**
     * Bumped when a default changes in a way an existing file must pick up.
     *
     * <p>Gson overwrites field defaults with whatever is stored, so raising a default does nothing
     * for anyone who already has the file -- the tree caps would have stayed at their old values on
     * every existing install. Version 2 raises them.
     */
    private static final int CONFIG_VERSION = 9;

    private static final char DEFAULT_MADE_BY = '>';
    private static final char DEFAULT_USED_IN = '<';
    private static final char DEFAULT_MACHINE_FOR = '*';
    private static final char DEFAULT_ITEM_CLASS = '~';

    /**
     * Characters EMI has already spoken for: {@code @} mod, {@code #} tooltip, {@code $} tag, and
     * the operators {@code -} NOT, {@code |} OR, {@code &} AND. Note {@code #} and {@code $} are the
     * opposite way round from JEI. A quote or slash would break EMI's tokenizer outright.
     */
    private static final String RESERVED = "@#$-|&\"/";

    /** {@code <} and {@code >} on a US layout. Config-driven because they are not, elsewhere. */
    private static final String DEFAULT_CONSUMERS_KEY = "shift+comma";
    private static final String DEFAULT_PRODUCERS_KEY = "shift+period";
    private static final HotKey DEFAULT_CONSUMERS = new HotKey(GLFW.GLFW_KEY_COMMA, true, false, false);
    private static final HotKey DEFAULT_PRODUCERS = new HotKey(GLFW.GLFW_KEY_PERIOD, true, false, false);

    /** R for route. Shifted so it cannot collide with a plain letter typed into EMI's search. */
    /**
     * Ceilings the "search deeper" retry escalates towards and never passes.
     *
     * <p>Retrying is what makes a step cap survivable, but an unbounded retry is a hang with extra
     * clicks. These are the hard limits: the config decides where a search starts, these decide
     * where it stops.
     */
    public static final int MAX_ROUTE_STEPS = 24;
    public static final int MAX_ROUTE_NODES = 500000;
    public static final int MAX_ROUTE_MILLIS = 5000;

    private static final String DEFAULT_ROUTE_KEY = "shift+r";
    private static final HotKey DEFAULT_ROUTE = new HotKey(GLFW.GLFW_KEY_R, true, false, false);

    private static final List<String> HELP = List.of(
            "Process Search -- four extra search prefixes for EMI.",
            "  >mixing               what MAKES this item",
            "  <crushing             what CONSUMES it",
            "  *mixing               which MACHINE runs the process",
            "  ~decorative           what KIND of item it is",
            "  >mixing/heat.heated   process and property as ONE token -- see the README for why",
            "Run /processsearch facets <text> in game to discover the tokens this pack actually has.",
            "Each prefix must be a single character. EMI already claims @ # $ and the operators - | &.",
            "decorativeModIds / trimModIds / compressedModIds are mod ids whose items get that ~ class;",
            "they are matched on the item's namespace, so they catch items with no recipes at all.",
            "excludedCategories takes full EMI category ids, namespace:path -- the same strings",
            "/processsearch facets prints. Skipping one keeps it out of the index entirely.",
            "reuseIndexAcrossWorlds keeps the index when you leave a world and reuses it on the next",
            "join if the recipes are unchanged, so singleplayer -> menu -> server does not rebuild.",
            "Process tree: hover an item in EMI and press < or > for a graph of what it turns into,",
            "or of everything that makes it. treeConsumersKey / treeProducersKey are written as",
            "'shift+comma' -- any Minecraft key name works, which matters on non-US layouts where",
            "< and > are somewhere else. The tree* caps bound how wide and deep the graph goes,",
            "and treeMinZoom is how far out the overview will zoom before it gives up on fitting",
            "the whole graph and centres on the root instead.",
            "treeIncludedCategories is opt-in and starts empty -- the tree follows nothing until you",
            "tick machines in with the Filters button on the graph. treeViewLayers is how many rows",
            "are drawn including the focus, and the walk depth follows from it. treeVisible* decide",
            "how much is drawn per layer before the rest collapses into a +N chip, and are what to",
            "raise if the screen feels sparse; treeMax* are ceilings on how much the walk keeps.",
            "treeHideIdentityRecipes drops steps that hand back an item they also consumed --",
            "anvil repair, grindstone, enchanting -- which otherwise loop tools back on themselves.",
            "Facet rules: drop .json files in config/processsearch/facet_rules/ to add facets for any",
            "mod, with no code change and nothing to compile against. A rule matches on the category",
            "id or namespace, a regex over the recipe id, the backing recipe's class name (which",
            "covers every subclass, so one rule reaches a whole mod family), whether fluids are",
            "involved, and how many ingredient slots there are -- then emits tokens, and optionally",
            "~ classes for the recipe's outputs. See the README for the full field list.",
            "Run /processsearch gaps to see which categories earned no facets beyond their own name;",
            "that is the list of mods a rule would actually be worth writing for.",
            "Routes: hover what you have, press treeRouteKey, then press it again on what you want",
            "and the tree shows a path between them. Pressing it twice on the same item cancels.",
            "routeMaxSteps bounds how long a route may be -- past a handful it stops being advice.",
            "routeMaxNodes is how many items the search may touch before giving up, and",
            "routeMaxMillis bounds it by the clock instead -- that is the one that keeps a search",
            "off the frame, since what a node costs to expand depends on how tag-heavy the pack is.",
            "The Deeper button on the route screen retries with all three raised, up to a ceiling. routeRespectCategoryFilter turns treeIncludedCategories into a hard",
            "constraint (\"route using only the machines I have\") instead of a preference between",
            "equally short routes, which is what it is by default.");

    /** Defaults are the mods Prominence II: Hasturian Era actually ships. */
    private static final List<String> DEFAULT_DECORATIVE = List.of(
            "chipped", "chipped_express", "handcrafted", "decorative_blocks", "supplementaries",
            "twigs", "convenientdecor", "fastpaintings", "darkpaintings", "betterbeds",
            "immersivelanterns", "connectiblechains");

    /**
     * Four trim mods between them generate a smithing recipe per material per pattern, which is this
     * pack's answer to ATM10's compressed blocks: thousands of entries that are never the answer to
     * an automation question.
     */
    private static final List<String> DEFAULT_TRIM = List.of(
            "allthetrims", "dynamictrim", "more_armor_trims", "bettertrims");

    private static final class Data {
        List<String> _comment = List.of();
        /** Zero on any file written before versioning, which is exactly what needs migrating. */
        int configVersion;

        String madeByPrefix = String.valueOf(DEFAULT_MADE_BY);
        String usedInPrefix = String.valueOf(DEFAULT_USED_IN);
        String machineForPrefix = String.valueOf(DEFAULT_MACHINE_FOR);
        String itemClassPrefix = String.valueOf(DEFAULT_ITEM_CLASS);

        boolean enableCreateFacets = true;
        boolean enableModernIndustrializationFacets = true;
        boolean enableFacetRules = true;
        boolean enableCatalystFacets = true;
        boolean enableRecipePageFilter = true;
        boolean reuseIndexAcrossWorlds = true;

        boolean enableProcessTree = true;
        String treeConsumersKey = DEFAULT_CONSUMERS_KEY;
        String treeProducersKey = DEFAULT_PRODUCERS_KEY;
        String treeRouteKey = DEFAULT_ROUTE_KEY;
        int routeMaxSteps = 8;
        int routeMaxNodes = 20000;
        int routeMaxMillis = 100;
        /** A preference by default; see the accessor for why the tree's filter is opt-in and this is not. */
        boolean routeRespectCategoryFilter = false;
        int treeViewLayers = 7;
        int treeVisibleMachines = 12;
        /** Six fits a 3x2 block, which is as much as one machine can show and stay scannable. */
        int treeVisibleItemsPerMachine = 6;
        int treeVisiblePerLayer = 72;
        int treeMaxNodes = 6000;
        int treeMaxProcessesPerItem = 32;
        int treeMaxItemsPerProcess = 32;
        double treeMinZoom = 0.08;
        boolean treeHideIdentityRecipes = true;
        /** Opt-in: empty means the tree follows nothing until you tick machines in. */
        List<String> treeIncludedCategories = new ArrayList<>();

        List<String> decorativeModIds = new ArrayList<>(DEFAULT_DECORATIVE);
        List<String> trimModIds = new ArrayList<>(DEFAULT_TRIM);
        List<String> compressedModIds = new ArrayList<>();
        List<String> dyeCategoryIds = new ArrayList<>();
        List<String> dyeRecipePatterns = new ArrayList<>();
        List<String> excludedCategories = new ArrayList<>();

        int buildTimeBudgetMillisPerTick = 3;

        // Resolved once at load so the search thread never parses a string.
        transient char madeBy = DEFAULT_MADE_BY;
        transient char usedIn = DEFAULT_USED_IN;
        transient char machineFor = DEFAULT_MACHINE_FOR;
        transient char itemClass = DEFAULT_ITEM_CLASS;
        transient HotKey consumers = DEFAULT_CONSUMERS;
        transient HotKey producers = DEFAULT_PRODUCERS;
        transient HotKey route = DEFAULT_ROUTE;
    }

    private static volatile Data data = resolved(new Data());

    /**
     * The facet rules for this session, reloaded whenever the config is.
     *
     * <p>Kept here rather than in {@code ProcessIndex} so that one thing owns "everything a player
     * can edit", and so a rule edit rides the existing {@link #generation} bump into the index
     * fingerprint -- which is what makes an edited rule file invalidate the cached index for free.
     */
    private static volatile FacetRules facetRules = FacetRules.EMPTY;

    /**
     * Bumped on every load. The index fingerprint includes it, so a config edit followed by
     * {@code /processsearch rebuild} can never be answered from the cached index -- the item-class
     * rules and the excluded-category list both live inside a built snapshot.
     */
    private static volatile int generation;

    private ProcessSearchConfig() {}

    // ---------------------------------------------------------------- load / save

    public static void load() {
        Path path = path();
        Data loaded = new Data();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Data parsed = GSON.fromJson(reader, Data.class);
                if (parsed != null) {
                    loaded = parsed;
                }
            } catch (Exception e) {
                ProcessSearch.LOGGER.error("Could not read {}; using defaults. {}", path, e.toString());
                loaded = new Data();
            }
        }
        loaded._comment = HELP;
        data = resolved(loaded);
        facetRules = loadRules(data);
        generation++;
        save(path, data);
    }

    /**
     * Bundled defaults, then {@code config/processsearch/facet_rules/}, then whatever the legacy
     * dye keys translate to.
     *
     * <p>The directory is created empty if it is missing, because a config option nobody can find
     * is not a config option.
     */
    private static FacetRules loadRules(Data value) {
        if (!value.enableFacetRules) {
            return FacetRules.EMPTY;
        }
        Path dir = rulesDir();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            ProcessSearch.LOGGER.warn("Could not create {}: {}", dir, e.toString());
        }
        return FacetRuleLoader.load(dir, legacyDyeRules(value));
    }

    /**
     * The old {@code dyeCategoryIds} / {@code dyeRecipePatterns} keys, as rules.
     *
     * <p>{@code ~dye} used to be hardcoded in the index build. It is a rule like any other now, so
     * rather than keep a second engine alive for two config keys, the keys are translated. Existing
     * configs keep working and there is one code path instead of two.
     *
     * <p>The original semantics were an OR -- match a listed category <em>or</em> any pattern --
     * and a rule ANDs its conditions, so this is one rule for the categories plus one per pattern.
     * Built as JSON and handed to the parser so the validation and the regex handling are shared
     * rather than reimplemented.
     */
    private static List<FacetRule> legacyDyeRules(Data value) {
        if (value.dyeCategoryIds.isEmpty() && value.dyeRecipePatterns.isEmpty()) {
            return List.of();
        }
        JsonArray rules = new JsonArray();
        if (!value.dyeCategoryIds.isEmpty()) {
            JsonObject rule = dyeRule("legacy_dye_categories");
            JsonArray ids = new JsonArray();
            value.dyeCategoryIds.forEach(ids::add);
            rule.add("categoryId", ids);
            rules.add(rule);
        }
        int n = 0;
        for (String pattern : value.dyeRecipePatterns) {
            JsonObject rule = dyeRule("legacy_dye_pattern_" + n++);
            rule.addProperty("recipeIdPattern", pattern);
            rules.add(rule);
        }
        JsonObject root = new JsonObject();
        root.add("rules", rules);
        return FacetRuleLoader.parse(GSON.toJson(root), "legacy dye config");
    }

    private static JsonObject dyeRule(String id) {
        JsonObject rule = new JsonObject();
        rule.addProperty("id", id);
        JsonArray dye = new JsonArray();
        dye.add(Facets.DYE);
        rule.add("tokens", dye);
        // The same token in both channels: it describes the recipe and classifies what came out.
        rule.add("itemClasses", dye.deepCopy());
        return rule;
    }

    public static Path rulesDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("processsearch").resolve("facet_rules");
    }

    /** The rules the index build should apply. Empty when the feature is switched off. */
    public static FacetRules facetRules() {
        return facetRules;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("processsearch.json");
    }

    private static void save(Path path, Data value) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
        } catch (Exception e) {
            ProcessSearch.LOGGER.error("Could not write {}: {}", path, e.toString());
        }
    }

    /** Normalises a freshly loaded object: fills nulls, clamps ranges, and settles the prefixes. */
    private static Data resolved(Data value) {
        migrate(value);
        value.decorativeModIds = nonNull(value.decorativeModIds);
        value.trimModIds = nonNull(value.trimModIds);
        value.compressedModIds = nonNull(value.compressedModIds);
        value.dyeCategoryIds = nonNull(value.dyeCategoryIds);
        value.dyeRecipePatterns = nonNull(value.dyeRecipePatterns);
        value.excludedCategories = nonNull(value.excludedCategories);
        value.buildTimeBudgetMillisPerTick = Math.max(1, Math.min(50, value.buildTimeBudgetMillisPerTick));
        // Odd values only: the rows alternate item, machine, item, so an even count would end on a
        // machine layer with nothing to show for it.
        value.treeViewLayers = Math.max(1, Math.min(9, value.treeViewLayers | 1));
        value.treeVisibleMachines = Math.max(1, Math.min(64, value.treeVisibleMachines));
        value.treeVisibleItemsPerMachine = Math.max(1, Math.min(128, value.treeVisibleItemsPerMachine));
        value.treeVisiblePerLayer = Math.max(2, Math.min(200, value.treeVisiblePerLayer));
        value.treeMaxNodes = Math.max(50, Math.min(50000, value.treeMaxNodes));
        value.treeMaxProcessesPerItem = Math.max(1, Math.min(128, value.treeMaxProcessesPerItem));
        value.treeMaxItemsPerProcess = Math.max(1, Math.min(128, value.treeMaxItemsPerProcess));
        value.treeMinZoom = Math.max(0.03, Math.min(1.0, value.treeMinZoom));
        value.treeIncludedCategories = nonNull(value.treeIncludedCategories);
        value.routeMaxSteps = Math.max(1, Math.min(MAX_ROUTE_STEPS, value.routeMaxSteps));
        value.routeMaxNodes = Math.max(500, Math.min(MAX_ROUTE_NODES, value.routeMaxNodes));
        value.routeMaxMillis = Math.max(10, Math.min(MAX_ROUTE_MILLIS, value.routeMaxMillis));
        value.consumers = HotKey.parse(value.treeConsumersKey, DEFAULT_CONSUMERS, "process tree <");
        value.producers = HotKey.parse(value.treeProducersKey, DEFAULT_PRODUCERS, "process tree >");
        value.route = HotKey.parse(value.treeRouteKey, DEFAULT_ROUTE, "process tree route");

        value.madeBy = prefix(value.madeByPrefix, DEFAULT_MADE_BY, "made by");
        value.usedIn = prefix(value.usedInPrefix, DEFAULT_USED_IN, "used in");
        value.machineFor = prefix(value.machineForPrefix, DEFAULT_MACHINE_FOR, "machine for");
        value.itemClass = prefix(value.itemClassPrefix, DEFAULT_ITEM_CLASS, "item class");
        warnOnDuplicates(value);
        return value;
    }

    /**
     * Raises caps that a new default moved, without ever lowering one somebody chose deliberately.
     * A fresh install runs this as a no-op, since its values already meet the new floors.
     */
    private static void migrate(Data value) {
        if (value.configVersion >= CONFIG_VERSION) {
            return;
        }
        if (value.configVersion < 2) {
            value.treeMaxProcessesPerItem = Math.max(value.treeMaxProcessesPerItem, 32);
            value.treeMaxItemsPerProcess = Math.max(value.treeMaxItemsPerProcess, 32);
            value.treeMaxNodes = Math.max(value.treeMaxNodes, 6000);
        }
        if (value.configVersion < 3) {
            // treeInitialHops is gone, replaced by a layer count the walk depth derives from.
            value.treeViewLayers = Math.max(value.treeViewLayers, 5);
            value.treeVisiblePerLayer = Math.max(value.treeVisiblePerLayer, 24);
        }
        if (value.configVersion < 4) {
            value.treeViewLayers = Math.max(value.treeViewLayers, 7);
            // Enough allowance for every machine on a layer to show a full 3x2 block.
            value.treeVisiblePerLayer = Math.max(value.treeVisiblePerLayer, 72);
        }
        if (value.configVersion < 5) {
            // The one migration that lowers a value rather than raising it. Below the label
            // threshold the icons now grow as you keep zooming out, and a floor of 0.3 left them
            // barely any room to do it in.
            value.treeMinZoom = Math.min(value.treeMinZoom, 0.15);
        }
        if (value.configVersion < 6) {
            // Widening again: the icons now grow most of the way to holding their size on screen,
            // so the useful part of the zoom range is further out than 0.15 could reach.
            value.treeMinZoom = Math.min(value.treeMinZoom, 0.08);
        }
        if (value.configVersion < 9) {
            // New in 9; an older file has it at zero, which the clamp would read as 10ms.
            value.routeMaxMillis = Math.max(value.routeMaxMillis, 100);
        }
        if (value.configVersion < 8) {
            // New in 8; an older file has the field at its zero value, which the clamp would
            // otherwise turn into a one-step cap and a 500-node budget.
            value.routeMaxSteps = Math.max(value.routeMaxSteps, 8);
            value.routeMaxNodes = Math.max(value.routeMaxNodes, 20000);
            if (value.treeRouteKey == null || value.treeRouteKey.isBlank()) {
                value.treeRouteKey = DEFAULT_ROUTE_KEY;
            }
        }
        value.configVersion = CONFIG_VERSION;
    }

    /**
     * A prefix already claimed by EMI is refused rather than stolen: silently taking over {@code #}
     * would turn a config typo into a bug report about tooltip search being broken, with no clue in
     * it.
     */
    private static char prefix(String configured, char fallback, String what) {
        if (configured == null || configured.isEmpty()) {
            return fallback;
        }
        char c = configured.charAt(0);
        if (Character.isWhitespace(c) || RESERVED.indexOf(c) >= 0) {
            ProcessSearch.LOGGER.error(
                    "Search prefix '{}' ({}) is reserved by EMI; falling back to '{}'", c, what, fallback);
            return fallback;
        }
        return c;
    }

    private static void warnOnDuplicates(Data value) {
        // LinkedHashMap so the prefix that lost is named in registration order rather than the
        // collision being reported twice from both sides.
        Map<Character, String> seen = new LinkedHashMap<>(4);
        claim(seen, value.madeBy, "made by");
        claim(seen, value.usedIn, "used in");
        claim(seen, value.machineFor, "machine for");
        claim(seen, value.itemClass, "item class");
    }

    private static void claim(Map<Character, String> seen, char c, String what) {
        String owner = seen.putIfAbsent(c, what);
        if (owner != null) {
            ProcessSearch.LOGGER.error(
                    "Prefix '{}' is configured for both '{}' and '{}'; '{}' will never match",
                    c, owner, what, what);
        }
    }

    private static List<String> nonNull(List<String> value) {
        return value == null ? new ArrayList<>() : value;
    }

    // ---------------------------------------------------------------- editing

    /**
     * A mutable copy of everything a config screen may change.
     *
     * <p>A screen editing the live config field by field would leave it half-applied while the user
     * is still deciding, and each write would have to save. A draft is taken, edited freely, and
     * applied in one go -- or discarded by simply never applying it.
     *
     * <p>{@code treeIncludedCategories} is deliberately absent: it is derived from whatever graph
     * you are looking at, and the Filters panel is the only place that has the list to offer.
     */
    public static final class Draft {
        public String madeByPrefix;
        public String usedInPrefix;
        public String machineForPrefix;
        public String itemClassPrefix;

        public boolean enableCreateFacets;
        public boolean enableModernIndustrializationFacets;
        public boolean enableFacetRules;
        public boolean enableCatalystFacets;
        public boolean enableRecipePageFilter;
        public boolean reuseIndexAcrossWorlds;
        public int buildTimeBudgetMillisPerTick;

        public boolean enableProcessTree;
        public String treeConsumersKey;
        public String treeProducersKey;
        public String treeRouteKey;
        public int routeMaxSteps;
        public int routeMaxNodes;
        public int routeMaxMillis;
        public boolean routeRespectCategoryFilter;
        public int treeViewLayers;
        public int treeVisibleMachines;
        public int treeVisibleItemsPerMachine;
        public int treeVisiblePerLayer;
        public int treeMaxProcessesPerItem;
        public int treeMaxItemsPerProcess;
        public int treeMaxNodes;
        public double treeMinZoom;
        public boolean treeHideIdentityRecipes;

        public List<String> decorativeModIds;
        public List<String> trimModIds;
        public List<String> compressedModIds;
        public List<String> excludedCategories;
    }

    /** A snapshot of the current settings, safe for a screen to scribble on. */
    public static Draft draft() {
        Data current = data;
        Draft d = new Draft();
        d.madeByPrefix = current.madeByPrefix;
        d.usedInPrefix = current.usedInPrefix;
        d.machineForPrefix = current.machineForPrefix;
        d.itemClassPrefix = current.itemClassPrefix;
        d.enableCreateFacets = current.enableCreateFacets;
        d.enableModernIndustrializationFacets = current.enableModernIndustrializationFacets;
        d.enableFacetRules = current.enableFacetRules;
        d.enableCatalystFacets = current.enableCatalystFacets;
        d.enableRecipePageFilter = current.enableRecipePageFilter;
        d.reuseIndexAcrossWorlds = current.reuseIndexAcrossWorlds;
        d.buildTimeBudgetMillisPerTick = current.buildTimeBudgetMillisPerTick;
        d.enableProcessTree = current.enableProcessTree;
        d.treeConsumersKey = current.treeConsumersKey;
        d.treeProducersKey = current.treeProducersKey;
        d.treeRouteKey = current.treeRouteKey;
        d.routeMaxSteps = current.routeMaxSteps;
        d.routeMaxNodes = current.routeMaxNodes;
        d.routeMaxMillis = current.routeMaxMillis;
        d.routeRespectCategoryFilter = current.routeRespectCategoryFilter;
        d.treeViewLayers = current.treeViewLayers;
        d.treeVisibleMachines = current.treeVisibleMachines;
        d.treeVisibleItemsPerMachine = current.treeVisibleItemsPerMachine;
        d.treeVisiblePerLayer = current.treeVisiblePerLayer;
        d.treeMaxProcessesPerItem = current.treeMaxProcessesPerItem;
        d.treeMaxItemsPerProcess = current.treeMaxItemsPerProcess;
        d.treeMaxNodes = current.treeMaxNodes;
        d.treeMinZoom = current.treeMinZoom;
        d.treeHideIdentityRecipes = current.treeHideIdentityRecipes;
        d.decorativeModIds = new ArrayList<>(current.decorativeModIds);
        d.trimModIds = new ArrayList<>(current.trimModIds);
        d.compressedModIds = new ArrayList<>(current.compressedModIds);
        d.excludedCategories = new ArrayList<>(current.excludedCategories);
        return d;
    }

    /**
     * Writes a draft back, normalises it and saves once.
     *
     * <p>The generation bump matters as much here as it does for the category list: it is part of
     * the index fingerprint, so without it a cached index built under the old item-class rules
     * would be handed straight back.
     */
    public static void apply(Draft d) {
        Data next = data;
        next.madeByPrefix = d.madeByPrefix;
        next.usedInPrefix = d.usedInPrefix;
        next.machineForPrefix = d.machineForPrefix;
        next.itemClassPrefix = d.itemClassPrefix;
        next.enableCreateFacets = d.enableCreateFacets;
        next.enableModernIndustrializationFacets = d.enableModernIndustrializationFacets;
        next.enableFacetRules = d.enableFacetRules;
        next.enableCatalystFacets = d.enableCatalystFacets;
        next.enableRecipePageFilter = d.enableRecipePageFilter;
        next.reuseIndexAcrossWorlds = d.reuseIndexAcrossWorlds;
        next.buildTimeBudgetMillisPerTick = d.buildTimeBudgetMillisPerTick;
        next.enableProcessTree = d.enableProcessTree;
        next.treeConsumersKey = d.treeConsumersKey;
        next.treeProducersKey = d.treeProducersKey;
        next.treeRouteKey = d.treeRouteKey;
        next.routeMaxSteps = d.routeMaxSteps;
        next.routeMaxNodes = d.routeMaxNodes;
        next.routeMaxMillis = d.routeMaxMillis;
        next.routeRespectCategoryFilter = d.routeRespectCategoryFilter;
        next.treeViewLayers = d.treeViewLayers;
        next.treeVisibleMachines = d.treeVisibleMachines;
        next.treeVisibleItemsPerMachine = d.treeVisibleItemsPerMachine;
        next.treeVisiblePerLayer = d.treeVisiblePerLayer;
        next.treeMaxProcessesPerItem = d.treeMaxProcessesPerItem;
        next.treeMaxItemsPerProcess = d.treeMaxItemsPerProcess;
        next.treeMaxNodes = d.treeMaxNodes;
        next.treeMinZoom = d.treeMinZoom;
        next.treeHideIdentityRecipes = d.treeHideIdentityRecipes;
        next.decorativeModIds = new ArrayList<>(d.decorativeModIds);
        next.trimModIds = new ArrayList<>(d.trimModIds);
        next.compressedModIds = new ArrayList<>(d.compressedModIds);
        next.excludedCategories = new ArrayList<>(d.excludedCategories);

        data = resolved(next);
        facetRules = loadRules(data);
        generation++;
        save(path(), data);
    }

    // ---------------------------------------------------------------- accessors

    public static char madeByPrefix() {
        return data.madeBy;
    }

    public static char usedInPrefix() {
        return data.usedIn;
    }

    public static char machineForPrefix() {
        return data.machineFor;
    }

    public static char itemClassPrefix() {
        return data.itemClass;
    }

    /**
     * @return which relation this prefix character asks about, or null if it is not one of ours.
     *         Called per search token from EMI's search thread.
     */
    public static Role roleFor(char c) {
        Data current = data;
        if (c == current.madeBy) {
            return Role.MADE_BY;
        }
        if (c == current.usedIn) {
            return Role.USED_IN;
        }
        if (c == current.machineFor) {
            return Role.MACHINE_FOR;
        }
        if (c == current.itemClass) {
            return Role.ITEM_CLASS;
        }
        return null;
    }

    /** True for the three prefixes that describe a recipe; {@code ~} describes an item. */
    public static boolean isRecipePrefix(char c) {
        Role role = roleFor(c);
        return role != null && role != Role.ITEM_CLASS;
    }

    public static boolean createFacets() {
        return data.enableCreateFacets;
    }

    public static boolean miFacets() {
        return data.enableModernIndustrializationFacets;
    }

    /** Whether the data-driven rules in {@code config/processsearch/facet_rules/} are applied. */
    public static boolean facetRulesEnabled() {
        return data.enableFacetRules;
    }

    public static boolean catalystFacets() {
        return data.enableCatalystFacets;
    }

    public static boolean recipePageFilter() {
        return data.enableRecipePageFilter;
    }

    public static boolean reuseIndexAcrossWorlds() {
        return data.reuseIndexAcrossWorlds;
    }

    // ---------------------------------------------------------------- process tree

    public static boolean processTree() {
        return data.enableProcessTree;
    }

    /** {@code <} -- what can this be processed into. */
    public static HotKey treeConsumersKey() {
        return data.consumers;
    }

    /** {@code >} -- what are all the ways to produce this. */
    public static HotKey treeProducersKey() {
        return data.producers;
    }

    /** The key that anchors a route, then runs it. */
    public static HotKey treeRouteKey() {
        return data.route;
    }

    /** The most machines a route may pass through. Past a handful it stops being advice. */
    public static int routeMaxSteps() {
        return data.routeMaxSteps;
    }

    /** Distinct items a route search may touch across both frontiers before giving up. */
    public static int routeMaxNodes() {
        return data.routeMaxNodes;
    }

    /**
     * Wall clock a route search may spend, in milliseconds.
     *
     * <p>The bound that actually protects the frame. Steps and nodes are both poor proxies for
     * cost, because what a node costs to expand depends entirely on how tag-heavy the pack is --
     * one recipe taking a large tag contributes dozens of predecessors at once.
     */
    public static int routeMaxMillis() {
        return data.routeMaxMillis;
    }

    /**
     * Whether {@link #treeIncludedCategories()} is a hard constraint on routes rather than a
     * preference between equally short ones.
     *
     * <p>Off by default, which is the opposite of how the same list works for the tree, and
     * deliberately so: the tree fans out exponentially and needs an opt-in list to stay finite,
     * while a route is a targeted question already bounded by the node budget. Defaulting a fresh
     * install to "no machines enabled" would mean routing found nothing at all.
     */
    public static boolean routeRespectCategoryFilter() {
        return data.routeRespectCategoryFilter;
    }

    /** Rendered rows including the focus: focus, machines, items, machines, items. */
    public static int treeViewLayers() {
        return data.treeViewLayers;
    }

    /**
     * How far the walk goes, derived rather than configured separately so the two can never
     * disagree -- a view asking for rows the walk never took would just render gaps.
     */
    public static int treeWalkHops() {
        return Math.max(1, (data.treeViewLayers - 1) / 2);
    }

    /** Nodes drawn on any layer past the focus's own item layer, before a "+N" chip takes over. */
    public static int treeVisiblePerLayer() {
        return data.treeVisiblePerLayer;
    }

    /** Machines drawn around the focus before the rest become a "+N" chip. */
    public static int treeVisibleMachines() {
        return data.treeVisibleMachines;
    }

    /** Items drawn under one machine before the rest become a "+N" chip. */
    public static int treeVisibleItemsPerMachine() {
        return data.treeVisibleItemsPerMachine;
    }

    public static int treeMaxNodes() {
        return data.treeMaxNodes;
    }

    public static int treeMaxProcessesPerItem() {
        return data.treeMaxProcessesPerItem;
    }

    public static int treeMaxItemsPerProcess() {
        return data.treeMaxItemsPerProcess;
    }

    /**
     * How far out the overview is allowed to zoom to fit a graph on screen. Below this it stops
     * trying to show everything and centres on the root instead, because a graph zoomed out past
     * legibility is no more useful than one off the edge of the screen.
     */
    public static float treeMinZoom() {
        return (float) data.treeMinZoom;
    }

    /**
     * Drop recipes whose outputs are all items they also consume. That is exactly what anvil
     * repairing, grinding and enchanting are -- steps that do not change what the item is -- and it
     * catches anything else shaped like them without having to name it.
     */
    public static boolean treeHideIdentityRecipes() {
        return data.treeHideIdentityRecipes;
    }

    /**
     * Machines the tree is allowed to follow. Empty by default: you tick in what you want to see
     * rather than trying to name everything you do not.
     */
    public static List<String> treeIncludedCategories() {
        return data.treeIncludedCategories;
    }

    /**
     * Written by the tree's category dropdown, which is the only place config is edited in game.
     *
     * <p>Bumping the generation matters: it is part of the index fingerprint, so without it a
     * cached index built under the old exclusions would be served straight back.
     */
    public static void setTreeIncludedCategories(List<String> categories) {
        Data current = data;
        current.treeIncludedCategories = new ArrayList<>(categories);
        generation++;
        save(path(), current);
    }

    /** Changes whenever the config file is loaded; part of the index fingerprint. */
    public static int generation() {
        return generation;
    }

    public static int buildBudgetMillis() {
        return data.buildTimeBudgetMillisPerTick;
    }

    public static List<String> decorativeModIds() {
        return data.decorativeModIds;
    }

    public static List<String> trimModIds() {
        return data.trimModIds;
    }

    public static List<String> compressedModIds() {
        return data.compressedModIds;
    }

    public static List<String> excludedCategories() {
        return data.excludedCategories;
    }
}
