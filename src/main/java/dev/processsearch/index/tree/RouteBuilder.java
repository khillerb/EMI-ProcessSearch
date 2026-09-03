package dev.processsearch.index.tree;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
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

    /**
     * How far a retry has escalated past the configured budgets.
     *
     * <p>Each press of Deeper adds four steps and doubles the node and time budgets, stopping at
     * the ceilings in {@link ProcessSearchConfig}. Retrying is what makes a step cap survivable;
     * the ceiling is what stops the retry becoming a hang with extra clicks.
     */
    private static int steps(int escalation) {
        return Math.min(ProcessSearchConfig.MAX_ROUTE_STEPS,
                ProcessSearchConfig.routeMaxSteps() + escalation * 4);
    }

    private static int nodes(int escalation) {
        long scaled = (long) ProcessSearchConfig.routeMaxNodes() << Math.min(escalation, 16);
        return (int) Math.min(ProcessSearchConfig.MAX_ROUTE_NODES, scaled);
    }

    private static long nanos(int escalation) {
        long scaled = (long) ProcessSearchConfig.routeMaxMillis() << Math.min(escalation, 16);
        return Math.min(ProcessSearchConfig.MAX_ROUTE_MILLIS, scaled) * 1_000_000L;
    }

    /** True while a further retry would actually raise something. */
    public static boolean canDeepen(int escalation) {
        return steps(escalation) < ProcessSearchConfig.MAX_ROUTE_STEPS
                || nodes(escalation) < ProcessSearchConfig.MAX_ROUTE_NODES
                || nanos(escalation) < ProcessSearchConfig.MAX_ROUTE_MILLIS * 1_000_000L;
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

        long start = System.nanoTime();
        RouteSearch.Route route = RouteSearch.find(from, to, neighbours,
                steps(escalation), nodes(escalation), nanos(escalation));
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

        Map<EmiRecipeCategory, EmiIngredient> icons = new HashMap<>();
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
                    icons.computeIfAbsent(category, c -> iconFor(manager, c)),
                    List.of(recipe), current);
            graph.countProcess();

            ItemNode next = graph.nodeFor(leg.key(), stack, ++depth);
            process.add(next);
            current.add(process);
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

    /** The workstation, so a step reads "Crushing Wheels" rather than "create:crushing". */
    private static EmiIngredient iconFor(EmiRecipeManager manager, EmiRecipeCategory category) {
        try {
            List<EmiIngredient> workstations = manager.getWorkstations(category);
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
    }

    /** Whether two stacks name the same thing, so routing to yourself can be refused. */
    public static boolean sameItem(EmiStack a, EmiStack b) {
        Object ka = Scan.key(a);
        Object kb = Scan.key(b);
        return ka != null && ka.equals(kb);
    }
}
