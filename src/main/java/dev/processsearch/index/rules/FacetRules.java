package dev.processsearch.index.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The loaded rule set, and the only part of the rule system on a hot path.
 *
 * <p>The index build runs this once per recipe across every recipe in the pack, under a few
 * milliseconds per tick, so testing every rule against every recipe is not affordable. Two things
 * make it cheap:
 *
 * <ul>
 *   <li>Rules keyed to a category are bucketed by id and by namespace, so a recipe only ever sees
 *       rules that could possibly match it. Rules keyed only on type or shape go in a small
 *       always-tested bucket.</li>
 *   <li>The result of both category and class matching is memoised per (category, class) pair.
 *       A category almost always has exactly one backing recipe class, so this collapses the
 *       superclass walk to one map lookup per recipe. Same trick as
 *       {@code ProcessGraphBuilder.identityCache}.</li>
 * </ul>
 *
 * <p>The memo is keyed on the pair rather than on each half separately because the two filters have
 * to be intersected, and intersecting two lists per recipe is exactly the cost being avoided.
 *
 * <p>Immutable once built, but the memo is not: this is only ever touched from the client thread,
 * by the index build and by the recipe-page filter. It is never handed to EMI's search thread --
 * that reads finished tokens out of the published snapshot.
 */
public final class FacetRules {
    public static final FacetRules EMPTY = new FacetRules(List.of());

    /**
     * Stand-in key for "this recipe has no datapack recipe behind it", so the memo can be keyed
     * uniformly instead of needing a null-safe map.
     */
    private static final class NoBacking {}

    private final List<FacetRule> all;
    private final Map<String, List<FacetRule>> byCategoryId = new HashMap<>();
    private final Map<String, List<FacetRule>> byCategoryNamespace = new HashMap<>();
    /** Rules with no category condition: they have to be offered to every category. */
    private final List<FacetRule> unkeyed = new ArrayList<>();

    private final Map<String, Map<Class<?>, List<FacetRule>>> resolved = new HashMap<>();

    public FacetRules(List<FacetRule> rules) {
        this.all = List.copyOf(rules);
        for (FacetRule rule : this.all) {
            if (!rule.hasCategoryKey()) {
                unkeyed.add(rule);
                continue;
            }
            // A rule may name both ids and namespaces; it is filed under each so the candidate
            // lookup finds it either way, and matchesCategory then applies the AND properly.
            for (String id : rule.categoryIds()) {
                byCategoryId.computeIfAbsent(id, k -> new ArrayList<>()).add(rule);
            }
            for (String namespace : rule.categoryNamespaces()) {
                byCategoryNamespace.computeIfAbsent(namespace, k -> new ArrayList<>()).add(rule);
            }
        }
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    public int size() {
        return all.size();
    }

    public List<FacetRule> all() {
        return all;
    }

    /**
     * The rules worth testing against a recipe in this category with this backing type.
     *
     * <p>Everything returned has already passed the category and class conditions; the caller still
     * has to run {@link FacetRule#matchesRecipe}, which is the per-recipe half.
     *
     * @param backing the backing recipe's class, or null when there is none
     */
    public List<FacetRule> candidates(String categoryId, Class<?> backing) {
        if (all.isEmpty()) {
            return List.of();
        }
        Class<?> key = backing == null ? NoBacking.class : backing;
        return resolved
                .computeIfAbsent(categoryId, k -> new IdentityHashMap<>())
                .computeIfAbsent(key, k -> resolve(categoryId, k == NoBacking.class ? null : k));
    }

    private List<FacetRule> resolve(String categoryId, Class<?> backing) {
        String namespace = namespaceOf(categoryId);

        // Deliberately a list built from the three buckets rather than a set: a rule filed under
        // both an id and a namespace would otherwise be tested twice and emit its tokens twice.
        List<FacetRule> considered = new ArrayList<>(unkeyed);
        addUnseen(considered, byCategoryId.get(categoryId));
        addUnseen(considered, byCategoryNamespace.get(namespace));

        List<FacetRule> matched = new ArrayList<>(considered.size());
        for (FacetRule rule : considered) {
            if (rule.matchesCategory(categoryId, namespace) && rule.matchesClass(backing)) {
                matched.add(rule);
            }
        }
        return matched.isEmpty() ? List.of() : List.copyOf(matched);
    }

    private static void addUnseen(List<FacetRule> into, List<FacetRule> more) {
        if (more == null) {
            return;
        }
        for (FacetRule rule : more) {
            // Identity, and linear: these lists hold a handful of rules, so a set would cost more
            // to build than the scan it saves.
            if (!containsIdentical(into, rule)) {
                into.add(rule);
            }
        }
    }

    private static boolean containsIdentical(List<FacetRule> list, FacetRule rule) {
        for (FacetRule at : list) {
            if (at == rule) {
                return true;
            }
        }
        return false;
    }

    private static String namespaceOf(String categoryId) {
        int colon = categoryId.indexOf(':');
        return colon < 0 ? categoryId : categoryId.substring(0, colon);
    }
}
