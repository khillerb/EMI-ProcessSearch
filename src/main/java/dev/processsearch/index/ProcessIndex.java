package dev.processsearch.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.search.EmiSearch;
import dev.processsearch.ProcessSearch;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.emi.EmiCompat;
import dev.processsearch.index.sources.CreateFacets;
import dev.processsearch.index.sources.MachineRecipeFacets;
import dev.processsearch.index.sources.RuleFacets;
import dev.processsearch.index.sources.VanillaRecipeFacets;
import dev.processsearch.search.SearchHook;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Reverse index from registry singletons (Item or Fluid) to searchable facet tokens.
 *
 * <p>Four relations, one per search prefix: what makes a thing, what consumes it, what machine runs
 * a process, and what kind of thing an item is.
 *
 * <p>Built on the client thread, sliced across ticks under a time budget, then published once as an
 * immutable {@link Snapshot}. The publish is the load-bearing part: EMI compiles and runs queries on
 * its own daemon thread, so the live build maps must never be visible to a search.
 */
public final class ProcessIndex {
    public enum State { IDLE, BUILDING, READY }

    /**
     * The finished index, immutable and safe to read from EMI's search thread.
     *
     * <p>Item classes are half stored and half computed: {@code ~dye} comes from recipes and lives
     * in the map, while {@code ~decorative}, {@code ~trim} and {@code ~compressed} are a namespace
     * test, so computing them on lookup costs nothing and, more importantly, catches items that have
     * no recipes at all -- which is most of what a furniture mod ships.
     */
    public record Snapshot(Map<Object, Set<String>> madeBy,
                           Map<Object, Set<String>> usedIn,
                           Map<Object, Set<String>> machineFor,
                           Map<Object, Set<String>> itemClass,
                           Map<String, Set<String>> categoryTokens,
                           List<CategoryGap> gaps,
                           Facets.ItemClassRules classRules,
                           Stats stats) {

        /**
         * A category whose recipes earned nothing beyond the category's own name.
         *
         * <p>Which is to say: a mod we can find by name and cannot ask anything about. Ranked by
         * recipe count, this is the shortlist of mods a facet rule would actually be worth writing
         * for, and it is not guessable in a pack this size -- hence {@code /processsearch gaps}.
         */
        public record CategoryGap(String id, String name, int recipes) {}

        /**
         * What it cost to build this index and what came out of it.
         *
         * <p>Carried by the snapshot rather than kept in static counters, because a snapshot can
         * outlive the build that produced it -- reusing a cached index across a world change must
         * report the numbers of the build it actually came from.
         */
        public record Stats(int recipes, int categories, int madeByEntries, int usedInEntries,
                            int machineEntries, long workMillis, long elapsedSeconds) {}

        public Set<String> tokensFor(Role role, Object key) {
            return switch (role) {
                case MADE_BY -> madeBy.getOrDefault(key, Set.of());
                case USED_IN -> usedIn.getOrDefault(key, Set.of());
                case MACHINE_FOR -> machineFor.getOrDefault(key, Set.of());
                case ITEM_CLASS -> itemClasses(key);
            };
        }

        private Set<String> itemClasses(Object key) {
            Set<String> fromRules = classRules.classify(key);
            Set<String> fromRecipes = itemClass.getOrDefault(key, Set.of());
            if (fromRules.isEmpty()) {
                return fromRecipes;
            }
            if (fromRecipes.isEmpty()) {
                return fromRules;
            }
            Set<String> all = new HashSet<>(fromRules);
            all.addAll(fromRecipes);
            return all;
        }

        public Set<String> distinctFacets() {
            Set<String> distinct = new HashSet<>();
            madeBy.values().forEach(distinct::addAll);
            usedIn.values().forEach(distinct::addAll);
            machineFor.values().forEach(distinct::addAll);
            return distinct;
        }
    }

    // Live build state. Client thread only.
    private static final Map<Object, Set<String>> MADE_BY = new HashMap<>();
    private static final Map<Object, Set<String>> USED_IN = new HashMap<>();
    private static final Map<Object, Set<String>> MACHINE_FOR = new HashMap<>();
    private static final Map<Object, Set<String>> ITEM_CLASS = new HashMap<>();
    private static final Map<String, Set<String>> CATEGORY_TOKENS = new HashMap<>();
    // For the gap report: how big each category is, what it is called, and whether anything in it
    // earned a token that was not free for every mod.
    private static final Map<String, Integer> CATEGORY_RECIPES = new HashMap<>();
    private static final Map<String, String> CATEGORY_NAMES = new HashMap<>();
    private static final Set<String> CATEGORY_WITH_FACETS = new HashSet<>();

    private static volatile Snapshot snapshot;
    private static State state = State.IDLE;

    /**
     * The previous index, kept but not published when a world unloads.
     *
     * <p>EMI replaces every recipe object on reload, but a snapshot holds none of them -- only
     * {@code Item} and {@code Fluid} registry singletons, which come from {@code BuiltInRegistries}
     * and are frozen at startup rather than synced from the server. So the only thing that can
     * invalidate it is the recipe data changing, which {@link Fingerprint} can check far more
     * cheaply than a rebuild.
     */
    private static Snapshot retained;
    private static Fingerprint retainedFingerprint;
    /** Taken when a build starts, stored beside the snapshot when it finishes. */
    private static Fingerprint buildingFingerprint;

    /** Set from EMI's search thread when a facet query ran before the index existed. */
    private static volatile boolean coldQuery;
    /** Set from EMI's reload thread, or on disconnect. Acted on by the next client tick. */
    private static volatile boolean resetPending;

    // Build cursor.
    private static EmiRecipeManager manager;
    private static List<EmiRecipeCategory> categories = List.of();
    private static int categoryCursor;
    private static List<EmiRecipe> currentRecipes;
    private static int recipeCursor;
    private static EmiRecipeCategory currentCategory;
    private static String currentCategoryId;
    private static Set<String> currentProcessTokens = Set.of();

    private static List<FacetSource> sources = List.of();
    private static Facets.ItemClassRules itemClassRules =
            new Facets.ItemClassRules(Set.of(), Set.of(), Set.of());

    /** Scratch for the per-recipe token cross product. Build path only. */
    private static final Scan SCAN = new Scan();
    private static final Set<String> COMBINED = new HashSet<>();
    private static final Set<String> WARNED = new HashSet<>();

    // Live build counters. The finished totals live on the snapshot instead, so that reusing a
    // cached index reports the build it actually came from.
    private static int recipesIndexed;
    private static int categoriesIndexed;
    private static long buildStartNanos;
    /** Wall clock from build start to finish -- mostly time spent waiting for the next tick. */
    private static long elapsedNanos;
    /** Time actually spent indexing. This is the number that says whether the build is expensive. */
    private static long workNanos;
    /** Time spent deciding whether a rebuild was needed at all. */
    private static long fingerprintNanos;
    /** True when the current index came from the cache rather than a build. */
    private static boolean reused;

    private ProcessIndex() {}

    // ------------------------------------------------------------ lookup (search thread)

    /** @return the published index, or null while it is still being built. */
    public static Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Called from EMI's search thread when a facet query found no index. Blocking there to build one
     * is not an option, so the next client tick starts the build and re-runs the search when it
     * lands.
     */
    public static void noteColdQuery() {
        coldQuery = true;
    }

    public static boolean coldQueryPending() {
        return coldQuery;
    }

    /**
     * Marks the index for a reset from another thread.
     *
     * <p>EMI rebuilds every recipe object on its own reload thread, which makes a held index not
     * merely stale but a set of pointers to recipes that no longer exist. Clearing the maps from
     * there would race the build, so the next client tick does it.
     */
    public static void invalidate() {
        resetPending = true;
    }

    public static boolean resetPending() {
        return resetPending;
    }

    // ------------------------------------------------------------ lifecycle

    public static State state() {
        return state;
    }

    public static boolean isReady() {
        return state == State.READY;
    }

    public static void reset() {
        MADE_BY.clear();
        USED_IN.clear();
        MACHINE_FOR.clear();
        ITEM_CLASS.clear();
        CATEGORY_TOKENS.clear();
        CATEGORY_RECIPES.clear();
        CATEGORY_NAMES.clear();
        CATEGORY_WITH_FACETS.clear();
        WARNED.clear();
        snapshot = null;
        manager = null;
        categories = List.of();
        categoryCursor = 0;
        currentRecipes = null;
        currentCategory = null;
        currentCategoryId = null;
        currentProcessTokens = Set.of();
        sources = List.of();
        recipesIndexed = 0;
        categoriesIndexed = 0;
        elapsedNanos = 0;
        workNanos = 0;
        fingerprintNanos = 0;
        reused = false;
        buildingFingerprint = null;
        resetPending = false;
        state = State.IDLE;
    }

    /**
     * The world-change path: keep the finished index aside instead of throwing it away, so the next
     * join can reuse it if the recipes turn out to be identical.
     *
     * <p>It stops being published, deliberately. During the gap a facet query sees a cold index and
     * takes the existing {@link #noteColdQuery()} path, which means there is no window in which a
     * search could be answered out of the world the player just left.
     *
     * <p>The non-null guard matters: a reload landing mid-build leaves no snapshot, and overwriting
     * a good cached one with nothing would discard it for no reason.
     */
    public static void retire() {
        if (!ProcessSearchConfig.reuseIndexAcrossWorlds()) {
            dropCache();
            return;
        }
        Snapshot current = snapshot;
        if (current != null) {
            retained = current;
            retainedFingerprint = buildingFingerprint;
        }
        reset();
    }

    /** Forgets the cached index as well, forcing the next build to be a real one. */
    public static void dropCache() {
        retained = null;
        retainedFingerprint = null;
        reset();
    }

    /** Starts the build if it has not started and EMI is ready to be read. Safe to call per tick. */
    public static void requestBuild() {
        if (state != State.IDLE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // Recipes come from the connected level; without one there is nothing to index.
            return;
        }
        if (!emiLoaded()) {
            // EMI reloads on its own thread. Reading its recipe list mid-reload would index a
            // partial pack and then never notice.
            return;
        }

        EmiRecipeManager m;
        List<EmiRecipeCategory> all;
        try {
            m = EmiApi.getRecipeManager();
            all = m == null ? null : m.getCategories();
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        if (all == null || all.isEmpty()) {
            return;
        }

        SearchHook.probeOnce();
        EmiCompat.reportOnce();

        Set<String> excluded = Set.copyOf(ProcessSearchConfig.excludedCategories());
        List<EmiRecipeCategory> found = new ArrayList<>(all.size());
        for (EmiRecipeCategory category : all) {
            if (category != null && !excluded.contains(category.getId().toString())) {
                found.add(category);
            }
        }

        // Fingerprinted over the filtered list, so what is compared is exactly what would be built.
        long fingerprintStart = System.nanoTime();
        Fingerprint fingerprint = Fingerprint.of(m, found);
        fingerprintNanos = System.nanoTime() - fingerprintStart;

        // Sources and rules are set up either way: the recipe-page filter recomputes one recipe's
        // facets on demand, and it does that whether the index was built or reused.
        List<FacetSource> chain = new ArrayList<>(3);
        chain.add(new VanillaRecipeFacets(mc.level.registryAccess()));
        // Each mod-specific source is behind a mod check so its classes never link without the mod.
        if (ProcessSearchConfig.createFacets() && FabricLoader.getInstance().isModLoaded("create")) {
            chain.add(new CreateFacets());
        }
        if (ProcessSearchConfig.miFacets()
                && FabricLoader.getInstance().isModLoaded("modern_industrialization")) {
            chain.add(new MachineRecipeFacets());
        }
        // No mod check: rules select themselves by matching, so one for a mod that is not installed
        // simply never fires. This is the whole point of them -- coverage without a build change.
        if (ProcessSearchConfig.facetRulesEnabled()) {
            chain.add(new RuleFacets(ProcessSearchConfig.facetRules()));
        }
        sources = List.copyOf(chain);

        itemClassRules = Facets.ItemClassRules.fromConfig();

        if (reuseCached(fingerprint)) {
            return;
        }

        manager = m;
        categories = found;
        categoryCursor = 0;
        currentRecipes = null;
        buildStartNanos = System.nanoTime();
        state = State.BUILDING;
        ProcessSearch.LOGGER.info("Building process index over {} EMI categories", categories.size());
    }

    /**
     * Republishes the cached index when the world just joined turns out to have the same recipes.
     *
     * @return true if the cache was used, in which case no build happens at all
     */
    private static boolean reuseCached(Fingerprint fingerprint) {
        boolean usable = ProcessSearchConfig.reuseIndexAcrossWorlds()
                && retained != null
                && fingerprint != null
                && fingerprint.equals(retainedFingerprint);
        if (!usable) {
            if (retained != null) {
                // Worth one line: "why did it rebuild" is otherwise unanswerable from a log.
                ProcessSearch.LOGGER.info(
                        "Recipe set changed since the last index ({} recipes now, {} before), rebuilding",
                        fingerprint == null ? -1 : fingerprint.recipeCount(),
                        retainedFingerprint == null ? -1 : retainedFingerprint.recipeCount());
            }
            retained = null;
            retainedFingerprint = null;
            buildingFingerprint = fingerprint;
            return false;
        }

        snapshot = retained;
        buildingFingerprint = retainedFingerprint;
        retained = null;
        reused = true;
        state = State.READY;
        ProcessSearch.LOGGER.info(
                "Process index reused: recipe set unchanged ({} recipes, fingerprint took {} ms)",
                fingerprint.recipeCount(), fingerprintNanos / 1_000_000L);
        if (coldQuery) {
            coldQuery = false;
            refreshSearch();
        }
        return true;
    }

    private static boolean emiLoaded() {
        try {
            return EmiReloadManager.isLoaded();
        } catch (RuntimeException | LinkageError e) {
            // If that moved, fall through to the category check instead of refusing to build.
            return true;
        }
    }

    /** Advances the build by at most {@code budgetNanos}. */
    public static void pump(long budgetNanos) {
        if (state != State.BUILDING) {
            return;
        }
        long deadline = System.nanoTime() + budgetNanos;
        long enter = System.nanoTime();
        boolean done = drain(deadline);
        workNanos += System.nanoTime() - enter;
        if (done) {
            finish();
        }
    }

    /** @return true when every category has been consumed. */
    private static boolean drain(long deadlineNanos) {
        int sinceClockCheck = 0;
        while (true) {
            if (currentRecipes == null) {
                if (categoryCursor >= categories.size()) {
                    return true;
                }
                beginCategory(categories.get(categoryCursor++));
                if (currentRecipes == null) {
                    continue;
                }
            }
            while (recipeCursor < currentRecipes.size()) {
                EmiRecipe recipe = currentRecipes.get(recipeCursor++);
                if (recipe != null) {
                    indexRecipe(recipe);
                    recipesIndexed++;
                }
                // Reading the clock every recipe would cost more than the work it guards.
                if ((++sinceClockCheck & 0x3F) == 0 && System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
            currentRecipes = null;
            categoriesIndexed++;
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
        }
    }

    private static void beginCategory(EmiRecipeCategory category) {
        currentCategory = category;
        currentCategoryId = category.getId().toString();

        List<EmiIngredient> workstations = workstationsOf(category);
        currentProcessTokens = processTokens(category, workstations);
        CATEGORY_TOKENS.put(currentCategoryId, currentProcessTokens);

        // The * prefix is this relation read backwards: category -> workstations becomes
        // workstation -> processes, built in the same pass at no extra cost.
        for (EmiIngredient workstation : workstations) {
            for (EmiStack stack : workstation.getEmiStacks()) {
                Object key = Scan.key(stack);
                if (key != null) {
                    MACHINE_FOR.computeIfAbsent(key, k -> new HashSet<>()).addAll(currentProcessTokens);
                }
            }
        }

        recipeCursor = 0;
        try {
            currentRecipes = manager.getRecipes(category);
        } catch (RuntimeException | LinkageError e) {
            warnOnce(currentCategoryId, e);
            currentRecipes = null;
        }
        CATEGORY_RECIPES.put(currentCategoryId,
                currentRecipes == null ? 0 : currentRecipes.size());
        CATEGORY_NAMES.put(currentCategoryId, categoryName(category));
    }

    /** The displayed title, falling back to the id when a category needs a context we lack. */
    private static String categoryName(EmiRecipeCategory category) {
        try {
            return category.getName().getString();
        } catch (RuntimeException | LinkageError e) {
            return category.getId().toString();
        }
    }

    private static List<EmiIngredient> workstationsOf(EmiRecipeCategory category) {
        if (!ProcessSearchConfig.catalystFacets()) {
            return List.of();
        }
        try {
            List<EmiIngredient> workstations = manager.getWorkstations(category);
            return workstations == null ? List.of() : workstations;
        } catch (RuntimeException | LinkageError e) {
            warnOnce(category.getId().toString(), e);
            return List.of();
        }
    }

    /**
     * Tokens every recipe in a category inherits: the category id, its bare path, its displayed
     * title, and the names of the machines that run it.
     */
    private static Set<String> processTokens(EmiRecipeCategory category,
                                             List<EmiIngredient> workstations) {
        Set<String> tokens = new HashSet<>();
        ResourceLocation uid = category.getId();
        add(tokens, uid.toString());
        add(tokens, uid.getPath());
        try {
            add(tokens, category.getName().getString());
        } catch (RuntimeException | LinkageError e) {
            // A category whose title needs a context we do not have here; the id still works.
        }
        for (EmiIngredient workstation : workstations) {
            for (EmiStack stack : workstation.getEmiStacks()) {
                add(tokens, workstationName(stack));
            }
        }
        return Set.copyOf(tokens);
    }

    private static String workstationName(EmiStack stack) {
        if (Scan.key(stack) instanceof Item item) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            return id == null ? null : id.getPath();
        }
        return null;
    }

    private static void add(Set<String> tokens, String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String token = Facets.sanitize(raw);
        if (!token.isEmpty()) {
            tokens.add(token);
        }
    }

    private static void indexRecipe(EmiRecipe recipe) {
        SCAN.reset();
        if (!collectInto(currentCategory, recipe, SCAN, currentCategoryId) || !SCAN.hasRoles()) {
            return;
        }

        // A category earns its keep the moment one of its recipes says something the category name
        // did not. fluid.* and chance.* do not count -- every mod gets those for nothing.
        if (!CATEGORY_WITH_FACETS.contains(currentCategoryId) && earnedFacet(SCAN.tokens)) {
            CATEGORY_WITH_FACETS.add(currentCategoryId);
        }

        // Reused rather than allocated per recipe: this runs once for every recipe in the pack.
        COMBINED.clear();
        Facets.combineInto(COMBINED, currentProcessTokens, SCAN.tokens);

        boolean classified = !SCAN.itemClasses.isEmpty();
        for (Object key : SCAN.outputs) {
            MADE_BY.computeIfAbsent(key, k -> new HashSet<>()).addAll(COMBINED);
            if (classified) {
                // Item classes hang on what came out, not on the recipe: ~dye means "this is a dye
                // product", which is a fact about the item.
                ITEM_CLASS.computeIfAbsent(key, k -> new HashSet<>()).addAll(SCAN.itemClasses);
            }
        }
        for (Object key : SCAN.inputs) {
            USED_IN.computeIfAbsent(key, k -> new HashSet<>()).addAll(COMBINED);
        }
    }

    /** True when any token says more than "this recipe exists and has stacks in it". */
    private static boolean earnedFacet(Set<String> tokens) {
        for (String token : tokens) {
            if (!Facets.isUniversal(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fills {@code scan} with the recipe's role ingredients and its property tokens. Shared by the
     * index build and the recipe-page filter.
     */
    private static boolean collectInto(EmiRecipeCategory category, EmiRecipe recipe, Scan scan,
                                       String categoryId) {
        try {
            List<EmiStack> outputs = recipe.getOutputs();
            if (outputs != null) {
                for (EmiStack output : outputs) {
                    scan.addOutput(output);
                }
            }
            List<EmiIngredient> inputs = recipe.getInputs();
            if (inputs != null) {
                for (EmiIngredient input : inputs) {
                    scan.addInput(input);
                }
            }
            // Catalysts are deliberately not indexed as inputs: a mixer is not something you feed a
            // mixer. They become category-level tokens instead, so >mechanical_mixer finds what the
            // machine makes rather than the machine itself.
        } catch (RuntimeException | LinkageError e) {
            warnOnce(categoryId, e);
            return false;
        }
        scan.finishChance();

        Recipe<?> backing = backingRecipe(recipe);
        for (FacetSource source : sources) {
            try {
                source.collect(category, recipe, backing, scan);
            } catch (RuntimeException | LinkageError e) {
                warnOnce(categoryId + "/" + source.getClass().getSimpleName(), e);
            }
        }
        return true;
    }

    /**
     * The datapack recipe behind an EMI recipe.
     *
     * <p>EMI's default implementation resolves it through the vanilla recipe manager by id, and both
     * Create and MI set their EMI recipe ids from the real recipe -- which is why neither needs a
     * mixin or any reflection to read its heat condition or its EU tier.
     */
    private static Recipe<?> backingRecipe(EmiRecipe recipe) {
        try {
            return recipe.getBackingRecipe();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ------------------------------------------------------------ recipe-page filtering

    /** Whatever is currently typed in EMI's search box, or empty if EMI is not up yet. */
    public static String currentFilterText() {
        try {
            String text = EmiApi.getSearchText();
            return text == null ? "" : text;
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /**
     * Facets for one recipe, computed on demand for the recipe screen.
     *
     * <p>Deliberately not stored during the build. A recipe-to-facets map would be tens of thousands
     * of entries kept alive permanently to serve a filter that is only active while someone is
     * looking at a category page; recomputing one page's worth on the spot is far cheaper, and
     * {@code RecipeFilter} caches the result per (category, query) anyway.
     */
    public static Set<String> facetsForRecipe(EmiRecipeCategory category, EmiRecipe recipe) {
        Snapshot current = snapshot;
        if (current == null) {
            return Set.of();
        }
        String categoryId = category.getId().toString();
        Set<String> processes = current.categoryTokens().getOrDefault(categoryId, Set.of());

        Scan scan = new Scan();
        if (!collectInto(category, recipe, scan, categoryId)) {
            return processes;
        }
        return Facets.combine(processes, scan.tokens);
    }

    // ------------------------------------------------------------ finish

    private static void finish() {
        // Most items share an identical token set -- every plain crafting output has the same one.
        // Collapsing them onto shared immutable instances turns tens of thousands of small sets into
        // a few hundred.
        canonicalize(MADE_BY);
        canonicalize(USED_IN);
        canonicalize(MACHINE_FOR);
        canonicalize(ITEM_CLASS);

        elapsedNanos = System.nanoTime() - buildStartNanos;
        Snapshot.Stats stats = new Snapshot.Stats(recipesIndexed, categoriesIndexed,
                MADE_BY.size(), USED_IN.size(), MACHINE_FOR.size(),
                workNanos / 1_000_000L, elapsedNanos / 1_000_000_000L);

        Snapshot published = new Snapshot(
                Map.copyOf(MADE_BY),
                Map.copyOf(USED_IN),
                Map.copyOf(MACHINE_FOR),
                Map.copyOf(ITEM_CLASS),
                Map.copyOf(CATEGORY_TOKENS),
                gapReport(),
                itemClassRules,
                stats);

        // Release the working maps: everything is served from the snapshot from here on.
        MADE_BY.clear();
        USED_IN.clear();
        MACHINE_FOR.clear();
        ITEM_CLASS.clear();
        CATEGORY_TOKENS.clear();
        CATEGORY_RECIPES.clear();
        CATEGORY_NAMES.clear();
        CATEGORY_WITH_FACETS.clear();
        COMBINED.clear();
        categories = List.of();
        currentCategory = null;
        currentRecipes = null;
        currentProcessTokens = Set.of();

        // buildingFingerprint stays as it is: retire() moves it alongside the snapshot, which keeps
        // the invariant that retainedFingerprint always describes retained.
        snapshot = published;
        reused = false;
        state = State.READY;

        // Both numbers, because only one of them is a cost. The build is capped at a few ms per
        // tick, so elapsed time is mostly waiting between ticks -- what matters is the work total.
        ProcessSearch.LOGGER.info(
                "Process index ready: {} recipes / {} categories, {} made-by + {} used-in + {} machine "
                        + "entries, {} facets, {} ms of work over {} s",
                stats.recipes(), stats.categories(), stats.madeByEntries(), stats.usedInEntries(),
                stats.machineEntries(), published.distinctFacets().size(), stats.workMillis(),
                stats.elapsedSeconds());

        if (coldQuery) {
            coldQuery = false;
            refreshSearch();
        }
    }

    /**
     * Categories that contributed nothing but their own name, biggest first.
     *
     * <p>Computed once at publish and carried on the snapshot, so a reused index still answers
     * {@code /processsearch gaps} -- the build counters it would otherwise need are long gone by
     * then. Empty categories are left out: nothing to write a rule against.
     */
    private static List<Snapshot.CategoryGap> gapReport() {
        List<Snapshot.CategoryGap> gaps = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : CATEGORY_RECIPES.entrySet()) {
            int recipes = entry.getValue();
            if (recipes <= 0 || CATEGORY_WITH_FACETS.contains(entry.getKey())) {
                continue;
            }
            gaps.add(new Snapshot.CategoryGap(entry.getKey(),
                    CATEGORY_NAMES.getOrDefault(entry.getKey(), entry.getKey()), recipes));
        }
        gaps.sort(Comparator.comparingInt(Snapshot.CategoryGap::recipes).reversed()
                .thenComparing(Snapshot.CategoryGap::id));
        return List.copyOf(gaps);
    }

    private static void canonicalize(Map<Object, Set<String>> map) {
        Map<Set<String>, Set<String>> pool = new HashMap<>();
        map.replaceAll((key, tokens) -> pool.computeIfAbsent(tokens, Set::copyOf));
    }

    /**
     * Re-runs the search someone typed before the index existed.
     *
     * <p>This is the EMI counterpart of JEI's {@code rebuildItemFilter}, and it is much cheaper:
     * facet queries are evaluated against the index live rather than baked into a search tree, so
     * nothing has to be rebuilt -- the query simply has an index to consult this time.
     */
    private static void refreshSearch() {
        try {
            EmiSearch.update();
        } catch (RuntimeException | LinkageError e) {
            ProcessSearch.LOGGER.debug("Could not refresh EMI search after the index finished", e);
        }
    }

    private static void warnOnce(String key, Throwable e) {
        if (key != null && WARNED.add(key)) {
            ProcessSearch.LOGGER.warn("Skipping part of '{}' while indexing: {}", key, e.toString());
        }
    }

    // ------------------------------------------------------------ stats

    /** True when the current index was reused from the previous world rather than rebuilt. */
    public static boolean wasReused() {
        return reused;
    }

    /** How long the last "does this need rebuilding at all?" check took. */
    public static long fingerprintMillis() {
        return fingerprintNanos / 1_000_000L;
    }

    /**
     * Finished totals come from the snapshot, live ones from the build counters. A reused index has
     * a snapshot but no build behind it, so reading the counters there would report zeroes.
     */
    private static Snapshot.Stats stats() {
        Snapshot current = snapshot;
        return current == null ? null : current.stats();
    }

    public static int producedEntryCount() {
        Snapshot.Stats stats = stats();
        return stats == null ? 0 : stats.madeByEntries();
    }

    public static int consumedEntryCount() {
        Snapshot.Stats stats = stats();
        return stats == null ? 0 : stats.usedInEntries();
    }

    public static int machineEntryCount() {
        Snapshot.Stats stats = stats();
        return stats == null ? 0 : stats.machineEntries();
    }

    public static int recipesIndexed() {
        Snapshot.Stats stats = stats();
        return stats == null ? recipesIndexed : stats.recipes();
    }

    public static int categoriesIndexed() {
        Snapshot.Stats stats = stats();
        return stats == null ? categoriesIndexed : stats.categories();
    }

    /** Time actually spent indexing, as opposed to waiting between ticks. */
    public static long workMillis() {
        Snapshot.Stats stats = stats();
        return stats == null ? workNanos / 1_000_000L : stats.workMillis();
    }

    public static long elapsedSeconds() {
        Snapshot.Stats stats = stats();
        return stats == null ? elapsedNanos / 1_000_000_000L : stats.elapsedSeconds();
    }

    public static int facetCount() {
        Snapshot current = snapshot;
        return current == null ? 0 : current.distinctFacets().size();
    }

    /** Categories that earned nothing beyond their own name, biggest first. */
    public static List<Snapshot.CategoryGap> gaps() {
        Snapshot current = snapshot;
        return current == null ? List.of() : current.gaps();
    }

    public static List<String> facetsMatching(String contains, int limit) {
        Snapshot current = snapshot;
        if (current == null) {
            return List.of();
        }
        String needle = contains == null ? "" : contains.toLowerCase(Locale.ROOT);
        return current.distinctFacets().stream()
                .filter(f -> needle.isEmpty() || f.contains(needle))
                .sorted()
                .limit(limit)
                .toList();
    }
}
