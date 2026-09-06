package dev.processsearch.index.tree;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiStack;
import dev.processsearch.ProcessSearch;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;
import dev.processsearch.index.Scan;

/**
 * Runs a route search and shapes the answer into something the graph screen already knows how to
 * draw.
 *
 * <p>A route is an alternating item, machine, item chain with a fan-out of exactly one -- which is
 * the structure {@link ProcessGraphScreen} draws for the tree, just narrower. So rather than a
 * second viewer, this fills an ordinary {@link ProcessGraph} and the existing screen renders it,
 * bringing pan, zoom, the compact icon mode and the tooltips along unchanged.
 *
 * <p>The direction is {@link Direction#CONSUMERS}, so the chain grows downward: what you have at
 * the top, what you want at the bottom, each machine between them in the order you would build.
 */
public final class RouteBuilder {
    private RouteBuilder() {}

    /** True while a further press of Deeper would actually raise something. */
    public static boolean canDeepen(int escalation) {
        return Budgets.route(escalation).canRaise();
    }

    /**
     * Runs a search and always hands back something to show.
     *
     * <p>A failure returns a graph too -- just the source, marked with why it stopped. That is what
     * gives the Deeper button somewhere to live: a line of chat saying "raise the limit and try
     * again" is worse advice than a button that does it.
     *
     * @return null only when EMI itself could not be reached, which is not a routing failure
     */
    public static ProcessGraph route(EmiStack source, EmiStack target, int escalation) {
        EmiRecipeManager manager;
        try {
            manager = EmiApi.getRecipeManager();
        } catch (RuntimeException | LinkageError e) {
            manager = null;
        }
        if (manager == null || source == null || target == null) {
            return null;
        }

        EmiNeighbours neighbours = new EmiNeighbours(manager);
        Object from = neighbours.remember(source);
        Object to = neighbours.remember(target);
        if (from == null || to == null) {
            return null;
        }

        Budgets budgets = Budgets.route(escalation);
        long start = System.nanoTime();
        RouteSearch.Route route = RouteSearch.find(from, to, neighbours,
                budgets.limit(), budgets.nodes(), budgets.nanos());
        ProcessSearch.LOGGER.debug("Route search {} in {} ms (escalation {})", route.outcome(),
                (System.nanoTime() - start) / 1_000_000L, escalation);

        return draw(route, neighbours, manager, source, target, escalation);
    }

    private static ProcessGraph draw(RouteSearch.Route route, EmiNeighbours neighbours,
                                     EmiRecipeManager manager, EmiStack sourceStack,
                                     EmiStack targetStack, int escalation) {
        ProcessGraph graph = new ProcessGraph(Direction.CONSUMERS, ProcessIndex.currentFilterText());
        ItemNode current = graph.nodeFor(route.source(), sourceStack, 0);
        graph.setRoot(current);
        // A real walker, so a step can be expanded outward in place: the route stays on screen as
        // the spine and you see what branches off it, rather than the answer being a dead end.
        // ProcessGraphBuilder skips categories a node already carries, so expanding a step does not
        // redraw the machine the route already put there.
        graph.setBuilder(ProcessGraphBuilder.forDirection(manager, Direction.CONSUMERS));

        Workstations icons = new Workstations(manager);
        Set<Object> placed = new HashSet<>();
        placed.add(route.source());
        int depth = 0;
        ItemNode last = current;

        for (RouteSearch.Leg leg : route.legs()) {
            if (!placed.add(leg.key())) {
                // Belt and braces. The search can only ever share one key between its two halves --
                // the meeting point, which is where they are joined -- so a reconstructed route is
                // simple. But nodeFor deduplicates by key, so a repeat would hand back a node that
                // is already someone's ancestor, and the screen's layout recurses through
                // processes(): a cycle there is a stack overflow, not a wrong picture.
                break;
            }
            // Stop rather than skip. Carrying on would attach the next leg to the previous node,
            // producing a chain that silently leaves a step out -- worse than a short answer,
            // because it would read as a complete route that does not work.
            EmiRecipe recipe = leg.recipe() instanceof EmiRecipe r ? r : null;
            EmiRecipeCategory category = recipe == null ? null : category(recipe);
            EmiStack stack = neighbours.stackFor(leg.key());
            if (category == null || stack == null) {
                break;
            }

            ProcessNode process = new ProcessNode(category,
                    icons.iconFor(category),
                    List.of(recipe), current);
            process.group = process;
            graph.countProcess();

            ItemNode next = graph.nodeFor(leg.key(), stack, ++depth);
            process.add(next);
            current.add(process);

            // Every other machine that would do this same step, drawn beside it as a block. A
            // route picks one recipe because it has to pick something, and without this the choice
            // it made silently looks like the only one there was.
            for (EmiRecipe alternative : neighbours.equivalentsFor(recipe, leg.key())) {
                EmiRecipeCategory other = category(alternative);
                if (other == null) {
                    continue;
                }
                ProcessNode swap = new ProcessNode(other,
                        icons.iconFor(other),
                        List.of(alternative), current);
                swap.alternative = true;
                swap.equivalent = category;
                // Same group as the step it stands in for, so the screen lays them out together
                // rather than working it out from what they lead to -- these carry no items of
                // their own, deliberately, since that would be the same subtree again.
                swap.group = process;
                graph.countProcess();
                current.add(swap);
                if (process.equivalent == null) {
                    process.equivalent = other;
                }
            }
            current = next;
            last = next;
        }

        // The drawn depth, not the found length: if a step could not be built the two differ, and
        // the status line has to describe what is actually on screen.
        graph.markRoute(sourceStack, route.found() ? last.stack : targetStack, depth,
                route.outcome(), escalation);
        return graph;
    }

    private static EmiRecipeCategory category(EmiRecipe recipe) {
        try {
            return recipe.getCategory();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Whether two stacks name the same thing, so routing to yourself can be refused. */
    public static boolean sameItem(EmiStack a, EmiStack b) {
        Object ka = Scan.key(a);
        Object kb = Scan.key(b);
        return ka != null && ka.equals(kb);
    }
}
