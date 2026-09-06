package dev.processsearch.index.tree;

import java.util.ArrayDeque;
import java.util.Deque;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.processsearch.ProcessSearch;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;
import dev.processsearch.index.Scan;
import dev.processsearch.screen.ProcessGraphScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Holds the built graph so moving between the overview and the recipe list costs nothing.
 *
 * <p>A walk over a 445-mod pack is not something to repeat because someone clicked Back, so the
 * graph is kept until the world changes or the search text does. Either of those makes it wrong
 * rather than merely stale.
 */
public final class ProcessTreeNavigation {
    private static ProcessGraph current;
    /** The inventory screen the player was on, to return to when the tree closes. */
    private static AbstractContainerScreen<?> origin;
    /** Previous roots, so re-rooting is undoable. */
    private static final Deque<ProcessGraph> HISTORY = new ArrayDeque<>();
    private static final int MAX_HISTORY = 16;

    private ProcessTreeNavigation() {}

    /**
     * Entry point from the hotkey.
     *
     * @return true if a tree screen was opened
     */
    public static boolean open(EmiIngredient hovered, Direction direction) {
        if (!ProcessSearchConfig.processTree() || hovered == null || hovered.isEmpty()) {
            return false;
        }
        EmiStack stack = Scan.firstKeyable(hovered);
        if (stack == null) {
            return false;
        }
        rememberOrigin();
        HISTORY.clear();
        return show(stack, direction);
    }

    /**
     * Shows a found answer -- a route or a plan -- keeping whatever was open on the back stack.
     *
     * <p>Either is an ordinary graph as far as everything downstream is concerned, so Back, the
     * history stack and the recipe drill-down all work without knowing the difference.
     */
    public static boolean show(ProcessGraph graph) {
        if (graph == null) {
            return false;
        }
        rememberOrigin();
        if (current != null) {
            push(current);
        }
        current = graph;
        openGraphScreen();
        return true;
    }

    /**
     * Runs the current route again with raised budgets.
     *
     * <p>Replaces the graph rather than stacking it: Deeper is a better answer to the same
     * question, not a new one, so Back should still go wherever you were before routing.
     *
     * @return true when a new attempt was made
     */
    public static boolean deepen() {
        if (current == null || !current.isFixed()) {
            return false;
        }
        int next = current.routeEscalation() + 1;
        ProcessGraph deeper = current.isPlan()
                ? PlanBuilder.plan(current.routeTo(), next)
                : RouteBuilder.route(current.routeFrom(), current.routeTo(), next);
        if (deeper == null) {
            return false;
        }
        current = deeper;
        openGraphScreen();
        return true;
    }

    /** Re-roots the graph at another node, keeping the old one on the back stack. */
    public static boolean reroot(EmiStack stack, Direction direction) {
        if (current != null) {
            push(current);
        }
        return show(stack, direction);
    }

    /**
     * Walks the current root again from scratch.
     *
     * <p>For when the rules changed under it -- the category dropdown edits config, and the cache is
     * keyed on root, direction and query, so it would otherwise hand back the graph built under the
     * old exclusions.
     */
    public static boolean rebuildCurrent() {
        if (current == null || current.root() == null) {
            return false;
        }
        EmiStack stack = current.root().stack;
        Direction direction = current.direction;
        current = null;
        return show(stack, direction);
    }

    /** @return true when there was somewhere to go back to */
    public static boolean back() {
        ProcessGraph previous = HISTORY.pollLast();
        if (previous == null) {
            return false;
        }
        current = previous;
        openGraphScreen();
        return true;
    }

    public static boolean canGoBack() {
        return !HISTORY.isEmpty();
    }

    private static boolean show(EmiStack stack, Direction direction) {
        ProcessGraph graph = reusable(stack, direction);
        if (graph == null) {
            long start = System.nanoTime();
            graph = ProcessGraphBuilder.build(stack, direction);
            if (graph == null) {
                return false;
            }
            ProcessSearch.LOGGER.debug("Built process graph: {} nodes in {} ms",
                    graph.nodeCount(), (System.nanoTime() - start) / 1_000_000L);
        }
        current = graph;
        openGraphScreen();
        return true;
    }

    /** The cached graph, when it still describes the same question under the same search. */
    private static ProcessGraph reusable(EmiStack stack, Direction direction) {
        if (current == null || current.direction != direction) {
            return null;
        }
        ItemNode root = current.root();
        Object key = Scan.key(stack);
        if (root == null || key == null || !key.equals(root.key)) {
            return null;
        }
        return current.query.equals(ProcessIndex.currentFilterText()) ? current : null;
    }

    public static ProcessGraph graph() {
        return current;
    }

    /** Reopens the overview from cache, which is what Back from the recipe list does. */
    public static void openGraphScreen() {
        if (current == null) {
            close();
            return;
        }
        Minecraft.getInstance().setScreen(new ProcessGraphScreen(current));
    }

    /** Returns to whatever the player was looking at before the tree opened. */
    public static void close() {
        Minecraft.getInstance().setScreen(origin);
    }

    /** Dropped when the world changes: the graph points at recipe objects that no longer exist. */
    public static void invalidate() {
        current = null;
        origin = null;
        HISTORY.clear();
        // The anchor names a stack from the world being left, so it cannot outlive it either.
        RouteAnchor.clear();
    }

    private static void push(ProcessGraph graph) {
        HISTORY.addLast(graph);
        while (HISTORY.size() > MAX_HISTORY) {
            HISTORY.pollFirst();
        }
    }

    private static void rememberOrigin() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof AbstractContainerScreen<?> container) {
            origin = container;
            return;
        }
        if (origin != null) {
            // Already know where we came from -- a route opened from inside the tree must still
            // return to the inventory, not to the tree screen it replaced.
            return;
        }
        try {
            origin = EmiApi.getHandledScreen();
        } catch (RuntimeException | LinkageError e) {
            origin = null;
        }
    }

}
