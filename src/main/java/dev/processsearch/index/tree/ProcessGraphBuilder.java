package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.search.EmiSearch;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;
import dev.processsearch.index.Scan;
import dev.processsearch.recipe.FacetQueryClauses;
import dev.processsearch.recipe.FacetQueryClauses.Clause;

/**
 * Walks EMI's recipe graph outward from one stack.
 *
 * <p>EMI does the expensive half already: {@code EmiRecipes$Manager} keeps pre-built
 * {@code byInput} and {@code byOutput} hash maps, so each step is one lookup, and tag ingredients
 * are already expanded into them -- which would have been miserable to replicate.
 *
 * <p>Everything here is about staying finite. Breadth-first under a node budget, a visited set that
 * makes repeat items link rather than branch, and width caps that report what they dropped instead
 * of hiding it.
 */
public final class ProcessGraphBuilder {
    private final EmiRecipeManager manager;
    private final Direction direction;
    /** Facet clauses from the search box; empty means "follow every recipe". */
    private final List<Clause> clauses;
    /** Negated item terms: anything matching these never becomes a node at all. */
    private final EmiSearch.CompiledQuery exclusionQuery;
    /** Positive item terms: branches that lead to one of these are worth keeping. */
    private final EmiSearch.CompiledQuery retentionQuery;

    /** Opt-in: a machine is followed only if it is in here. Empty means the graph is just the root. */
    private final Set<String> includedCategories = Set.copyOf(ProcessSearchConfig.treeIncludedCategories());
    private final boolean hideIdentity = ProcessSearchConfig.treeHideIdentityRecipes();
    /** Identity detection resolves tag ingredients, so a node with hundreds of recipes memoises. */
    private final Map<EmiRecipe, Boolean> identityCache = new IdentityHashMap<>();

    private static final int MAX_IDENTITY_OUTPUTS = 2;
    private static final int MAX_IDENTITY_INPUT_STACKS = 64;

    private final int maxProcesses = ProcessSearchConfig.treeMaxProcessesPerItem();
    private final int maxItems = ProcessSearchConfig.treeMaxItemsPerProcess();
    private final int maxNodes = ProcessSearchConfig.treeMaxNodes();

    private final Map<EmiRecipeCategory, EmiIngredient> icons = new HashMap<>();

    private ProcessGraphBuilder(EmiRecipeManager manager, Direction direction, List<Clause> clauses,
                                EmiSearch.CompiledQuery exclusionQuery,
                                EmiSearch.CompiledQuery retentionQuery) {
        this.manager = manager;
        this.direction = direction;
        this.clauses = clauses;
        this.exclusionQuery = exclusionQuery;
        this.retentionQuery = retentionQuery;
    }

    /** @return the graph, or null when EMI has no recipe manager or the stack is not keyable */
    public static ProcessGraph build(EmiStack rootStack, Direction direction) {
        EmiRecipeManager manager;
        try {
            manager = EmiApi.getRecipeManager();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        if (manager == null || rootStack == null) {
            return null;
        }
        String query = ProcessIndex.currentFilterText();
        List<Clause> clauses = FacetQueryClauses.parse(query);

        // A ~ token, or any recipe facet, can only be answered by the process index. Applying those
        // without it is worse than not applying them: a negated class filter with no index quietly
        // admits everything, because "did not match" and "could not tell" look identical to EMI's
        // negation. So refuse, and let the screen say so.
        boolean needsIndex = !clauses.isEmpty() || FacetQueryClauses.mentionsItemClass(query);
        boolean filtersUsable = ProcessIndex.isReady() || !needsIndex;

        ProcessGraphBuilder builder = new ProcessGraphBuilder(manager, direction,
                filtersUsable ? clauses : List.of(),
                filtersUsable ? compile(FacetQueryClauses.itemExclusions(query)) : null,
                filtersUsable ? compile(FacetQueryClauses.itemRetention(query)) : null);
        ProcessGraph graph = builder.walk(rootStack, query, ProcessSearchConfig.treeWalkHops());
        if (graph != null && !filtersUsable) {
            graph.markIndexNotReady();
        }
        return graph;
    }

    /**
     * The item half of the search box, as something that can be tested against a stack.
     *
     * <p>Built from the query with the recipe prefixes stripped: left in, {@code >mixing} would be
     * asked of every <em>item</em> in the graph -- "is this made by mixing?" -- and prune everything.
     */
    private static EmiSearch.CompiledQuery compile(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        try {
            EmiSearch.CompiledQuery compiled = new EmiSearch.CompiledQuery(query);
            // An empty CompiledQuery answers true to everything, which would be the opposite of
            // what either half of this is for.
            return compiled.isEmpty() ? null : compiled;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private ProcessGraph walk(EmiStack rootStack, String query, int depth) {
        Object rootKey = Scan.key(rootStack);
        if (rootKey == null) {
            return null;
        }
        ProcessGraph graph = new ProcessGraph(direction, query);
        graph.setBuilder(this);
        ItemNode root = graph.nodeFor(rootKey, rootStack, 0);
        graph.setRoot(root);

        List<ItemNode> frontier = new ArrayList<>();
        frontier.add(root);
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            List<ItemNode> next = new ArrayList<>();
            for (ItemNode node : frontier) {
                if (graph.overBudget(maxNodes)) {
                    break;
                }
                expandOne(graph, node, next);
            }
            frontier = next;
        }

        applyItemHighlighting(graph);
        return graph;
    }

    /**
     * Walks outward from one node, far enough to fill the view.
     *
     * <p>Breadth-first for the same reason the initial walk is: a depth-first dive would spend the
     * node budget on the first branch and leave the rest of the layer empty, which is exactly the
     * starvation that made enabled machines fail to appear.
     */
    boolean expandFrom(ProcessGraph graph, ItemNode node, int hops) {
        if (node == null) {
            return false;
        }
        int before = graph.nodeCount();
        List<ItemNode> frontier = new ArrayList<>();
        frontier.add(node);
        for (int hop = 0; hop < hops && !frontier.isEmpty(); hop++) {
            List<ItemNode> next = new ArrayList<>();
            for (ItemNode current : frontier) {
                if (graph.overBudget(maxNodes)) {
                    break;
                }
                expandOne(graph, current, next);
            }
            frontier = next;
        }
        return graph.nodeCount() != before;
    }

    // ------------------------------------------------------------ the walk

    private void expandOne(ProcessGraph graph, ItemNode node, List<ItemNode> discovered) {
        if (node.expanded) {
            return;
        }
        node.expanded = true;

        List<EmiRecipe> recipes = lookup(node.stack);
        if (recipes.isEmpty()) {
            return;
        }
        if (!clauses.isEmpty()) {
            List<EmiRecipe> kept = new ArrayList<>(recipes.size());
            for (EmiRecipe recipe : recipes) {
                if (FacetQueryClauses.matchesAny(clauses, facetsOf(recipe))) {
                    kept.add(recipe);
                }
            }
            recipes = kept;
            graph.markFacetsApplied();
            if (recipes.isEmpty()) {
                return;
            }
        }

        Map<EmiRecipeCategory, List<EmiRecipe>> byCategory = new LinkedHashMap<>();
        for (EmiRecipe recipe : recipes) {
            EmiRecipeCategory category = recipe == null ? null : recipe.getCategory();
            if (category == null) {
                continue;
            }
            // Counted before the skips, and this is what makes an empty allowlist survivable: the
            // Filters panel lists every machine that touches what has been walked, so there is
            // always something to tick in even when nothing is enabled yet.
            graph.countEncountered(category, 1);
            if (!includedCategories.contains(category.getId().toString())) {
                continue;
            }
            if (hideIdentity && isIdentity(recipe)) {
                continue;
            }
            byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(recipe);
        }
        if (byCategory.isEmpty()) {
            return;
        }

        // Biggest first, so that when the width cap bites it keeps the processes that matter and
        // the "+N more" is a tail of one-offs rather than the interesting half.
        List<Map.Entry<EmiRecipeCategory, List<EmiRecipe>>> ordered = new ArrayList<>(byCategory.entrySet());
        ordered.sort(Comparator
                .comparingInt((Map.Entry<EmiRecipeCategory, List<EmiRecipe>> e) -> -e.getValue().size())
                .thenComparing(e -> e.getKey().getId().toString()));

        int shown = Math.min(ordered.size(), maxProcesses);
        node.hiddenProcesses = ordered.size() - shown;
        for (int i = 0; i < shown; i++) {
            if (graph.overBudget(maxNodes)) {
                node.hiddenProcesses = ordered.size() - i;
                break;
            }
            Map.Entry<EmiRecipeCategory, List<EmiRecipe>> entry = ordered.get(i);
            ProcessNode process = new ProcessNode(entry.getKey(), iconFor(entry.getKey()),
                    List.copyOf(entry.getValue()), node);
            graph.countProcess();
            addFarSide(graph, process, node, discovered);
            node.add(process);
        }
    }

    /** The other end of the step: outputs when following consumers, inputs when following producers. */
    private void addFarSide(ProcessGraph graph, ProcessNode process, ItemNode parent,
                            List<ItemNode> discovered) {
        Map<Object, EmiStack> stacks = new LinkedHashMap<>();
        Map<Object, Integer> frequency = new HashMap<>();
        for (EmiRecipe recipe : process.recipes) {
            for (EmiStack stack : farSide(recipe)) {
                Object key = Scan.key(stack);
                if (key == null || key.equals(parent.key)) {
                    // Dropping the parent kills the trivial self-loop every reversible recipe has.
                    continue;
                }
                if (isExcluded(stack)) {
                    // A negated search term means "do not show me this", so it never becomes a node
                    // at all -- not drawn, not expanded, and not kept as a stepping stone.
                    graph.markExcludedApplied();
                    continue;
                }
                stacks.putIfAbsent(key, stack);
                frequency.merge(key, 1, Integer::sum);
            }
        }
        if (stacks.isEmpty()) {
            return;
        }

        List<Object> keys = new ArrayList<>(stacks.keySet());
        keys.sort(Comparator.comparingInt((Object key) -> -frequency.getOrDefault(key, 0)));

        int shown = Math.min(keys.size(), maxItems);
        process.hiddenItems = keys.size() - shown;
        for (int i = 0; i < shown; i++) {
            if (graph.overBudget(maxNodes)) {
                process.hiddenItems = keys.size() - i;
                break;
            }
            Object key = keys.get(i);
            boolean fresh = !graph.isKnown(key);
            ItemNode child = graph.nodeFor(key, stacks.get(key), parent.depth + 1);
            process.add(child);
            if (fresh) {
                discovered.add(child);
            }
        }
    }

    private List<EmiRecipe> lookup(EmiStack stack) {
        try {
            List<EmiRecipe> found = direction == Direction.CONSUMERS
                    ? manager.getRecipesByInput(stack)
                    : manager.getRecipesByOutput(stack);
            return found == null ? List.of() : found;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /**
     * Every item on the far side of a machine, uncapped and unfiltered.
     *
     * <p>Deliberately ignores the width caps <em>and</em> the search exclusions, because this backs
     * the "+N more" chip: the whole point of clicking it is to see what was held back, and a list
     * that re-applied the very rules that hid them would be a dead end.
     */
    public static List<EmiStack> allFarSide(ProcessNode process, Direction direction) {
        Map<Object, EmiStack> stacks = new LinkedHashMap<>();
        Object parentKey = process.parent == null ? null : process.parent.key;
        for (EmiRecipe recipe : process.recipes) {
            for (EmiStack stack : sideOf(recipe, direction)) {
                Object key = Scan.key(stack);
                if (key != null && !key.equals(parentKey)) {
                    stacks.putIfAbsent(key, stack);
                }
            }
        }
        return List.copyOf(stacks.values());
    }

    private static List<EmiStack> sideOf(EmiRecipe recipe, Direction direction) {
        if (recipe == null) {
            return List.of();
        }
        try {
            if (direction == Direction.CONSUMERS) {
                List<EmiStack> outputs = recipe.getOutputs();
                return outputs == null ? List.of() : outputs;
            }
            List<EmiIngredient> inputs = recipe.getInputs();
            if (inputs == null) {
                return List.of();
            }
            List<EmiStack> flat = new ArrayList<>(inputs.size());
            for (EmiIngredient ingredient : inputs) {
                if (ingredient != null) {
                    flat.addAll(ingredient.getEmiStacks());
                }
            }
            return flat;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    private List<EmiStack> farSide(EmiRecipe recipe) {
        if (recipe == null) {
            return List.of();
        }
        try {
            if (direction == Direction.CONSUMERS) {
                List<EmiStack> outputs = recipe.getOutputs();
                return outputs == null ? List.of() : outputs;
            }
            List<EmiIngredient> inputs = recipe.getInputs();
            if (inputs == null) {
                return List.of();
            }
            List<EmiStack> flat = new ArrayList<>(inputs.size());
            for (EmiIngredient ingredient : inputs) {
                if (ingredient != null) {
                    // A tag ingredient flattens to everything it accepts; the width cap trims it.
                    flat.addAll(ingredient.getEmiStacks());
                }
            }
            return flat;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    private Set<String> facetsOf(EmiRecipe recipe) {
        try {
            return ProcessIndex.facetsForRecipe(recipe.getCategory(), recipe);
        } catch (RuntimeException | LinkageError e) {
            return Set.of();
        }
    }

    private EmiIngredient iconFor(EmiRecipeCategory category) {
        return icons.computeIfAbsent(category, c -> {
            try {
                List<EmiIngredient> workstations = manager.getWorkstations(c);
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
        });
    }

    private boolean isExcluded(EmiStack stack) {
        if (exclusionQuery == null) {
            return false;
        }
        try {
            return exclusionQuery.test(stack);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * True when every output is something the recipe also consumes.
     *
     * <p>Which is precisely anvil repairing, grindstone and enchanting: steps that hand back an item
     * of the same kind and so loop tools endlessly back on themselves. Because keys come from
     * {@link Scan#key}, an enchanted sword and a plain one are the same item here, which is what
     * makes enchanting fall out of this rule rather than needing to be named.
     */
    private boolean isIdentity(EmiRecipe recipe) {
        Boolean cached = identityCache.get(recipe);
        if (cached != null) {
            return cached;
        }
        boolean identity = computeIdentity(recipe);
        identityCache.put(recipe, identity);
        return identity;
    }

    private boolean computeIdentity(EmiRecipe recipe) {
        try {
            List<EmiStack> outputs = recipe.getOutputs();
            List<EmiIngredient> inputs = recipe.getInputs();
            if (outputs == null || outputs.isEmpty() || inputs == null || inputs.isEmpty()) {
                return false;
            }
            // Two cheap gates before the expensive part. A repair-shaped recipe hands back a single
            // item and takes a handful of specific ones; anything with several outputs, or an input
            // that is a large tag, is a real transformation and not worth flattening to confirm.
            if (outputs.size() > MAX_IDENTITY_OUTPUTS) {
                return false;
            }
            Set<Object> inputKeys = new HashSet<>();
            for (EmiIngredient ingredient : inputs) {
                if (ingredient == null) {
                    continue;
                }
                List<EmiStack> stacks = ingredient.getEmiStacks();
                if (stacks.size() > MAX_IDENTITY_INPUT_STACKS) {
                    return false;
                }
                for (EmiStack stack : stacks) {
                    Object key = Scan.key(stack);
                    if (key != null) {
                        inputKeys.add(key);
                    }
                }
            }
            if (inputKeys.isEmpty()) {
                return false;
            }
            boolean sawOutput = false;
            for (EmiStack output : outputs) {
                Object key = Scan.key(output);
                if (key == null) {
                    continue;
                }
                sawOutput = true;
                if (!inputKeys.contains(key)) {
                    return false;
                }
            }
            return sawOutput;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    // ------------------------------------------------------------ item highlighting

    /**
     * Marks which items match the positive half of the search.
     *
     * <p>It used to prune to branches that led to a match, which was written for a deep pre-walk.
     * Once the walk went lazy every child was a leaf, "leads to a match" degenerated into "is itself
     * a match", and a search for {@code cobblestone} deleted every machine whose immediate output
     * was not named cobblestone -- crushing, milling, blasting, all of them. Marking is all this
     * does now: the screen tints matches and prefers them when deciding what to draw deeper, so the
     * search shapes the view instead of censoring it. Removal is what a leading {@code -} is for.
     */
    private void applyItemHighlighting(ProcessGraph graph) {
        ItemNode root = graph.root();
        if (retentionQuery == null || root == null) {
            return;
        }
        graph.markItemFilterApplied();
        mark(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private void mark(ItemNode node, Set<ItemNode> seen) {
        if (!seen.add(node)) {
            return;
        }
        node.matchesFilter = test(node.stack);
        for (ProcessNode process : node.processes()) {
            for (ItemNode child : process.items()) {
                mark(child, seen);
            }
        }
    }

    private boolean test(EmiStack stack) {
        try {
            return retentionQuery.test(stack);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
