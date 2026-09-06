package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiStack;
import dev.processsearch.ProcessSearch;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;

/**
 * Runs a build plan and shapes it into something the graph screen can draw.
 *
 * <p>Direction is {@link Direction#PRODUCERS}, so the tree grows upward: what you want at the
 * bottom, everything it needs stacked above, read from the top down as the order you would build.
 *
 * <p>The summary is derived in the same pass, because it is the actually actionable half. A player
 * does not work from a tree; they work from "build these four machines and go and mine that".
 */
public final class PlanBuilder {
    private PlanBuilder() {}

    /** True while a further press of Deeper would actually raise something. */
    public static boolean canDeepen(int escalation) {
        return Budgets.plan(escalation).canRaise();
    }

    /**
     * @param escalation how many times Deeper has been pressed for this target
     * @return null only when EMI itself could not be reached, which is not a planning failure
     */
    public static ProcessGraph plan(EmiStack target, int escalation) {
        EmiRecipeManager manager;
        try {
            manager = EmiApi.getRecipeManager();
        } catch (RuntimeException | LinkageError e) {
            manager = null;
        }
        if (manager == null || target == null) {
            return null;
        }

        EmiProducers producers = new EmiProducers(manager);
        Object key = producers.remember(target);
        if (key == null) {
            return null;
        }

        Budgets budgets = Budgets.plan(escalation);
        long start = System.nanoTime();
        PlanSearch.Plan plan = PlanSearch.resolve(key, producers,
                budgets.limit(), budgets.nodes(), budgets.nanos());
        ProcessSearch.LOGGER.debug("Build plan {} in {} ms (escalation {})", plan.outcome(),
                (System.nanoTime() - start) / 1_000_000L, escalation);

        return draw(plan, producers, manager, target, escalation);
    }

    private static ProcessGraph draw(PlanSearch.Plan plan, EmiProducers producers,
                                     EmiRecipeManager manager, EmiStack targetStack,
                                     int escalation) {
        ProcessGraph graph = new ProcessGraph(Direction.PRODUCERS, ProcessIndex.currentFilterText());
        // No builder attached: a plan is a closed answer. Expanding a node outward would graft a
        // walk's "any of these" onto a tree that means "all of these", which is the one confusion
        // this feature cannot afford.

        Summary summary = new Summary();
        ItemNode root = plan.root() == null
                ? graph.newNode(producers.remember(targetStack), targetStack, 0)
                : place(graph, plan.root(), producers, manager, 0, summary,
                        new Workstations(manager));
        graph.setRoot(root);

        graph.markPlan(targetStack, plan.outcome(), new ProcessGraph.PlanSummary(
                List.copyOf(summary.machines.values()),
                List.copyOf(summary.raw.values()),
                List.copyOf(summary.missing.values()),
                Map.copyOf(summary.alternatives),
                summary.deepest), escalation);
        return graph;
    }

    /** Recursive, and safely so: {@code PlanSearch} guarantees the step tree is finite and acyclic. */
    private static ItemNode place(ProcessGraph graph, PlanSearch.Step step, EmiProducers producers,
                                  EmiRecipeManager manager, int depth, Summary summary,
                                  Workstations icons) {
        EmiStack stack = producers.stackFor(step.key());
        ItemNode node = graph.newNode(step.key(), stack, depth);
        summary.deepest = Math.max(summary.deepest, depth);

        switch (step.kind()) {
            case RAW -> summary.raw.putIfAbsent(step.key(), stack);
            case UNRESOLVED -> {
                node.unresolved = true;
                summary.missing.putIfAbsent(step.key(), stack);
            }
            case MADE -> {
                EmiRecipe recipe = step.recipe() instanceof EmiRecipe r ? r : null;
                EmiRecipeCategory category = recipe == null ? null : categoryOf(recipe);
                if (category == null) {
                    // Nothing to hang the inputs off, so this reads as raw rather than pretending
                    // to a step we cannot name.
                    summary.raw.putIfAbsent(step.key(), stack);
                    break;
                }
                summary.machines.putIfAbsent(category, category);

                ProcessNode process = new ProcessNode(category,
                        icons.iconFor(category),
                        List.of(recipe), node);
                process.group = process;
                graph.countProcess();
                for (PlanSearch.Step input : step.inputs()) {
                    process.add(place(graph, input, producers, manager, depth + 1, summary, icons));
                }
                node.add(process);

                // Drawn beside the chosen machine, deliberately without their own inputs.
                // Those would be the same subtree again -- duplicated work, and several copies of
                // the same picture. An item's machines read as alternatives anyway, so these say
                // "or one of these" without claiming another set of materials.
                for (EmiRecipe alternative : producers.equivalentsFor(recipe, step.key())) {
                    EmiRecipeCategory other = categoryOf(alternative);
                    if (other == null) {
                        continue;
                    }
                    ProcessNode swap = new ProcessNode(other,
                            icons.iconFor(other),
                            List.of(alternative), node);
                    swap.alternative = true;
                    swap.equivalent = category;
                    swap.group = process;
                    graph.countProcess();
                    node.add(swap);
                    if (process.equivalent == null) {
                        process.equivalent = other;
                        summary.alternatives.putIfAbsent(category, other);
                    }
                }
            }
            default -> {
                // Unreachable; the enum has three constants.
            }
        }
        // Nothing in a plan expands further, so no node should offer a "+" promising more.
        node.expanded = true;
        return node;
    }

    /** Insertion-ordered, so the panel lists things roughly in the order the plan met them. */
    private static final class Summary {
        final Map<EmiRecipeCategory, EmiRecipeCategory> machines = new LinkedHashMap<>();
        /** Chosen machine to its interchangeable runner-up, for the summary's "or" note. */
        final Map<EmiRecipeCategory, EmiRecipeCategory> alternatives = new LinkedHashMap<>();
        final Map<Object, EmiStack> raw = new LinkedHashMap<>();
        final Map<Object, EmiStack> missing = new LinkedHashMap<>();
        int deepest;
    }

    private static EmiRecipeCategory categoryOf(EmiRecipe recipe) {
        try {
            return recipe.getCategory();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Everything a plan needs, flattened for the summary panel. */
    public static List<EmiStack> stacks(List<EmiStack> from) {
        List<EmiStack> out = new ArrayList<>(from.size());
        for (EmiStack stack : from) {
            if (stack != null && !stack.isEmpty()) {
                out.add(stack);
            }
        }
        return List.copyOf(out);
    }
}
