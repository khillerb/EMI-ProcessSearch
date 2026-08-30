package dev.processsearch.screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.tree.Direction;
import dev.processsearch.index.tree.ItemNode;
import dev.processsearch.index.tree.ProcessGraph;
import dev.processsearch.index.tree.ProcessNode;
import dev.processsearch.index.tree.ProcessTreeNavigation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;

/**
 * The overview: a focus neighbourhood several layers deep, not the whole graph.
 *
 * <p>The graph underneath stays intact and grows as you go. What is drawn is the focused item, where
 * you came from, and a few alternating layers of machines and items outward from it, with detail
 * decaying the further from the focus you get. Anything a layer budget cuts becomes a {@code +N}
 * chip rather than disappearing.
 *
 * <p>Depth runs vertically in the direction of the question, so {@code <} grows downward into what
 * an item becomes and {@code >} grows upward into what makes it.
 */
public class ProcessGraphScreen extends Screen {
    private static final int NODE_W = 130;
    private static final int NODE_H = 20;
    private static final int BREADTH_STRIDE = NODE_W + 10;
    private static final int LAYER_STRIDE = 56;
    private static final int GRID_ROW_STRIDE = NODE_H + 4;
    /** Three across, two down: six is as much as one machine can show and stay scannable. */
    private static final int GRID_COLS = 3;
    /** A "+N" chip says one number. Giving it a whole node's width was pure waste. */
    private static final int CHIP_W = 56;
    private static final int GROUP_GAP = 20;
    private static final int HEADER_H = 26;
    private static final int CRUMB_H = 18;
    private static final int CHROME_H = HEADER_H + CRUMB_H;
    private static final int MARGIN = 20;
    private static final float LABEL_ZOOM = 0.55F;
    /** Icon-only node size at the threshold; it grows from here as you keep zooming out. */
    private static final int COMPACT_W = 30;
    private static final int COMPACT_MAX = 160;
    /**
     * How hard the icon fights the zoom. At 1.0 it would hold a constant size on screen, which
     * cancels the zoom out entirely -- the tree would stop revealing more. 0.7 keeps icons legible
     * roughly twice as far out as before while the tree still visibly shrinks.
     */
    private static final float ICON_GROWTH = 0.85F;
    /** Open a little closer than a pure fit: an overview nobody can read is not an overview. */
    private static final float FIT_ZOOM = 1.45F;
    /** Fan-out past the focus's own neighbourhood: detail has to decay or five layers explode. */
    private static final int DEEP_FANOUT = 2;

    private static final int BACKDROP = 0xF00E0E0E;
    private static final int CHROME_BG = 0xFF141414;
    private static final int CHROME_LINE = 0xFF3C3C3C;
    private static final int ITEM_BG = 0xE0303030;
    private static final int ITEM_BG_MATCH = 0xE0284A28;
    private static final int FOCUS_BG = 0xE0454F2E;
    private static final int PARENT_BG = 0xC02A2A2A;
    private static final int PROCESS_BG = 0xE0263349;
    private static final int CLUSTER_BG = 0xC03A3020;
    private static final int BORDER = 0xFF5A5A5A;
    private static final int BORDER_FOCUS = 0xFFD8D8A0;
    private static final int BORDER_HOVER = 0xFFFFFFFF;
    private static final int EDGE = 0xFF6A6A6A;
    /** Against a wall of icons rather than a wall of labelled boxes, the darker grey disappears. */
    private static final int EDGE_COMPACT = 0xFF9A9A9A;
    private static final int GRID_BG = 0x30FFFFFF;

    private static boolean autoOpenedFilters;

    private final ProcessGraph graph;
    /** Root first, focus last -- the nodes actually clicked through, not a tree walk. */
    private final List<ItemNode> path = new ArrayList<>();
    private final Set<ProcessNode> openMachines =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean showAllMachines;

    private final List<Placed> placed = new ArrayList<>();
    private final List<Crumb> crumbs = new ArrayList<>();

    private double offsetX;
    private double offsetY;
    private float scale = 1.0F;
    private boolean dragging;
    private double lastDragX;
    private double lastDragY;
    private Placed hovered;
    private boolean framed;
    /**
     * Below {@link #LABEL_ZOOM} the whole layout changes, not just the drawing: boxes shrink to an
     * icon, the icon grows to fill them, and the tree narrows by about four times. Dropping the
     * label alone left a graph that was smaller but no more readable.
     */
    private boolean compact;
    /**
     * The compact box edge for the current zoom. It is not a constant: snapping once at the
     * threshold and then holding still meant every further scroll click shrank the icons like
     * everything else, so the whole effect was one step and then nothing.
     */
    private int compactSize = COMPACT_W;

    public ProcessGraphScreen(ProcessGraph graph) {
        super(Component.literal("Process Tree"));
        this.graph = graph;
        if (graph.root() != null) {
            path.add(graph.root());
        }
    }

    private ItemNode focus() {
        return path.isEmpty() ? null : path.get(path.size() - 1);
    }

    @Override
    protected void init() {
        int x = 4;
        int y = 3;
        Direction other = graph.direction.opposite();
        addRenderableWidget(Button.builder(
                        Component.literal(other.symbol + " " + other.description),
                        b -> ProcessTreeNavigation.reroot(graph.root().stack, other))
                .bounds(x, y, 110, 20).build());
        x += 114;
        addRenderableWidget(Button.builder(Component.literal("Fit"), b -> frameView())
                .bounds(x, y, 30, 20).build());
        x += 34;
        addRenderableWidget(Button.builder(Component.literal("Root"), b -> focusIndex(0))
                .bounds(x, y, 38, 20).build());
        x += 42;
        addRenderableWidget(Button.builder(Component.literal("Filters"), b -> openFilters())
                .bounds(x, y, 50, 20).build());
        x += 54;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> goBack())
                .bounds(x, y, 40, 20).build());
        x += 44;
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(x, y, 44, 20).build());
        buttonsRight = x + 44;

        rebuildView();
        if (!framed) {
            frameView();
            framed = true;
        }

        if (!autoOpenedFilters && ProcessSearchConfig.treeIncludedCategories().isEmpty()) {
            autoOpenedFilters = true;
            openFilters();
        }
    }

    /** Where the button strip ends, so the status text can never be drawn on top of it. */
    private int buttonsRight;

    void openFilters() {
        minecraft.setScreen(new CategoryFilterPanel(this, graph));
    }

    // ------------------------------------------------------------ the visible set

    private enum Kind { ITEM, MACHINE, CLUSTER }

    private static final class Placed {
        Kind kind;
        ItemNode item;
        ProcessNode process;
        String label;
        Runnable action;
        /** For a cell in a machine's item block: which machine it belongs to. */
        Placed owner;
        Placed parent;
        int w = NODE_W;
        /** Nothing hangs below this one, so clicking it starts a new tree rather than drilling. */
        boolean leaf;
        int row;
        int gridRow;
        int x;
        int y;
        boolean isFocus;
        boolean isParent;
    }

    private record Crumb(ItemNode node, int index, int left, int right) {}

    private int viewLayers() {
        return ProcessSearchConfig.treeViewLayers();
    }

    private int nodeW() {
        return compact ? compactSize : NODE_W;
    }

    private int nodeH() {
        return compact ? compactSize : NODE_H;
    }

    private int chipW() {
        return compact ? compactSize : CHIP_W;
    }

    private int breadthStride() {
        return nodeW() + (compact ? 6 : 10);
    }

    private int layerStride() {
        return compact ? nodeH() * 3 / 2 : LAYER_STRIDE;
    }

    private int gridRowStride() {
        return nodeH() + 4;
    }

    /**
     * The gap between one machine's block and the next: a quarter of the icon while the icons are
     * small, then fixed once they are big.
     *
     * <p>The cap is what makes the far end of the zoom worth having. With {@link #ICON_GROWTH} this
     * close to 1 the icon nearly holds its size on screen, so a gap that kept scaling with it would
     * leave the whole tree the same width no matter how far out you went. Capped, the far end packs
     * into a dense grid of near-touching icons instead.
     */
    private int groupGap() {
        return compact ? Math.min(16, Math.max(6, nodeW() / 4)) : GROUP_GAP;
    }

    /**
     * Edge thickness in graph units that lands at roughly 1.25 <em>screen</em> pixels at any zoom.
     *
     * <p>A 1px line under a 0.3 scale is 0.3px, which the rasteriser keeps only where a pixel centre
     * happens to fall -- that is why edges came and went, and why the survivors looked misplaced.
     */
    private int edgeWidth() {
        return Math.max(1, Math.round(1.25F / scale));
    }

    private int edgeColour() {
        return compact ? EDGE_COMPACT : EDGE;
    }

    /**
     * The compact box edge a given zoom calls for. Quantised to 4px so a slow scroll triggers a
     * handful of relayouts across the whole range rather than one per frame.
     */
    private static int sizeFor(float zoom) {
        float grown = COMPACT_W * (float) Math.pow(LABEL_ZOOM / Math.max(zoom, 0.01F), ICON_GROWTH);
        int quantised = Math.round(grown / 4F) * 4;
        return Math.max(COMPACT_W, Math.min(COMPACT_MAX, quantised));
    }

    /**
     * Brings {@link #compact} and {@link #compactSize} in line with the current zoom.
     *
     * @return true if either moved, meaning the layout has to be rebuilt
     */
    private boolean syncZoomMode() {
        boolean wantCompact = scale < LABEL_ZOOM;
        int wantSize = wantCompact ? sizeFor(scale) : COMPACT_W;
        if (wantCompact == compact && wantSize == compactSize) {
            return false;
        }
        compact = wantCompact;
        compactSize = wantSize;
        return true;
    }

    /** Screen row for a view layer, allowing for the parent context row above the focus. */
    private int rowFor(int viewLayer) {
        return (path.size() > 1 ? 1 : 0) + (viewLayer - 1);
    }

    /**
     * Rebuilds what is on screen: where you came from, where you are, and several layers outward
     * with the fan-out decaying as it goes.
     */
    private void rebuildView() {
        placed.clear();
        ItemNode focus = focus();
        if (focus == null) {
            return;
        }

        // One global allowance per layer past the focus's own item layer. Without it, twelve
        // machines with twelve items each would be a hundred and forty-four nodes, and their
        // machines thousands.
        int[] remaining = new int[viewLayers() + 3];
        for (int i = 0; i < remaining.length; i++) {
            remaining[i] = ProcessSearchConfig.treeVisiblePerLayer();
        }

        Placed placedFocus = layoutItem(focus, 1, 0, remaining);
        placedFocus.isFocus = true;

        if (path.size() > 1) {
            ItemNode parentNode = path.get(path.size() - 2);
            Placed placedParent = item(parentNode, 0);
            placedParent.isParent = true;
            placedParent.x = placedFocus.x;

            int siblings = siblingCount(parentNode, focus);
            if (siblings > 0) {
                Placed chip = cluster("+" + siblings, 0,
                        () -> minecraft.setScreen(SiblingsPanel.forItem(this, graph, parentNode)));
                chip.x = placedFocus.x + breadthStride();
            }
        }

        boolean down = graph.direction.growsDown();
        for (Placed node : placed) {
            int layerY = node.row * layerStride() + node.gridRow * gridRowStride();
            node.y = down ? layerY : -layerY;
        }
    }

    /**
     * Places one item and everything below it, returning the node so a caller can centre on it.
     *
     * <p>Classic tidy layout: children take the next free columns, the parent centres over their
     * span. It is only affordable because the layer budgets keep the widest layer to a few dozen
     * nodes rather than a few hundred.
     */
    private Placed layoutItem(ItemNode node, int viewLayer, int left, int[] remaining) {
        Placed self = item(node, rowFor(viewLayer));

        if (viewLayer >= viewLayers()) {
            self.leaf = true;
            self.x = left;
            return self;
        }

        List<ProcessNode> machines = node.processes();
        int machineCap = viewLayer == 1
                ? (showAllMachines ? machines.size() : ProcessSearchConfig.treeVisibleMachines())
                : DEEP_FANOUT;
        int shownMachines = Math.min(machines.size(), machineCap);
        if (viewLayer > 1) {
            shownMachines = Math.min(shownMachines, Math.max(0, remaining[viewLayer + 1]));
            remaining[viewLayer + 1] -= shownMachines;
        }

        int cursor = left;
        List<Placed> machineNodes = new ArrayList<>(shownMachines);
        for (int i = 0; i < shownMachines; i++) {
            cursor = layoutMachine(machines.get(i), viewLayer + 1, cursor, remaining, machineNodes);
        }

        int hiddenMachines = machines.size() - shownMachines;
        if (hiddenMachines > 0) {
            ItemNode holder = node;
            // At the focus there is room to simply show them; deeper, the way to see them all is
            // to make that item the focus.
            Placed chip = cluster("+" + hiddenMachines, rowFor(viewLayer + 1),
                    viewLayer == 1 ? () -> showAllMachines = true : () -> focusOn(holder));
            chip.parent = self;
            chip.x = cursor;
            machineNodes.add(chip);
            cursor += chipW() + groupGap();
        }

        if (machineNodes.isEmpty()) {
            self.x = left;
            return self;
        }
        for (Placed machine : machineNodes) {
            machine.parent = self;
        }
        self.x = centreOver(machineNodes, self.w);
        return self;
    }

    /** The x that centres a box of width {@code w} over everything in {@code children}. */
    private static int centreOver(List<Placed> children, int w) {
        int leftmost = Integer.MAX_VALUE;
        int rightmost = Integer.MIN_VALUE;
        for (Placed child : children) {
            leftmost = Math.min(leftmost, child.x);
            rightmost = Math.max(rightmost, child.x + child.w);
        }
        return (leftmost + rightmost - w) / 2;
    }

    /** @return the next free x after this machine's block */
    private int layoutMachine(ProcessNode node, int viewLayer, int left, int[] remaining,
                              List<Placed> out) {
        Placed self = machine(node, rowFor(viewLayer));
        out.add(self);

        List<ItemNode> items = ordered(node.items());
        int itemCap;
        if (viewLayer == 2) {
            itemCap = openMachines.contains(node)
                    ? items.size()
                    : ProcessSearchConfig.treeVisibleItemsPerMachine();
        } else {
            itemCap = DEEP_FANOUT;
        }
        int shown = Math.min(items.size(), itemCap);
        shown = Math.min(shown, Math.max(0, remaining[viewLayer + 1]));
        remaining[viewLayer + 1] -= shown;
        int hidden = items.size() - shown;

        boolean lastLayer = viewLayer + 1 >= viewLayers();
        int cursor = left;
        List<Placed> children = new ArrayList<>(shown);

        if (lastLayer) {
            // Nothing hangs below these, so pack them as a block instead of a long row.
            int cols = Math.min(GRID_COLS, Math.max(1, shown));
            for (int i = 0; i < shown; i++) {
                Placed cell = item(items.get(i), rowFor(viewLayer + 1));
                cell.owner = self;
                cell.leaf = true;
                cell.x = left + (i % cols) * breadthStride();
                cell.gridRow = 1 + i / cols;
                children.add(cell);
            }
            if (hidden > 0) {
                ProcessNode owner = node;
                Placed chip = cluster("+" + hidden, rowFor(viewLayer + 1),
                        () -> minecraft.setScreen(SiblingsPanel.forMachine(this, graph, owner)));
                chip.owner = self;
                chip.x = left + (shown % cols) * breadthStride();
                chip.gridRow = 1 + shown / cols;
                children.add(chip);
            }
            cursor = left + Math.max(1, cols) * breadthStride();
        } else {
            // Deliberately no owner here, unlike the grid above. These children are spread by the
            // width of their own subtrees, so the single grey bar drawItemBlock paints across an
            // owned group would be almost entirely empty. Ordinary connectors instead.
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    cursor += groupGap();
                }
                Placed child = layoutItem(items.get(i), viewLayer + 1, cursor, remaining);
                children.add(child);
                cursor = furthestRight(child);
            }
            if (hidden > 0) {
                ProcessNode owner = node;
                Placed chip = cluster("+" + hidden, rowFor(viewLayer + 1),
                        () -> minecraft.setScreen(SiblingsPanel.forMachine(this, graph, owner)));
                if (!children.isEmpty()) {
                    cursor += groupGap();
                }
                chip.x = cursor;
                children.add(chip);
                cursor += chipW();
            }
        }

        if (children.isEmpty()) {
            self.x = left;
            return left + breadthStride() + groupGap();
        }
        for (Placed child : children) {
            child.parent = self;
        }
        // Min/max rather than first/last: a grid block wraps, so its last cell is often back in
        // column 0 and centring on it would put the machine over the left edge of its own block.
        self.x = centreOver(children, self.w);
        // The one gap between this block and the next; the loop above spaces the children.
        return Math.max(cursor, self.x + breadthStride()) + groupGap();
    }

    /** The rightmost extent of a placed subtree, so siblings do not overlap it. */
    private int furthestRight(Placed root) {
        int right = root.x + root.w;
        for (Placed node : placed) {
            if (node.x + node.w > right && descendsFrom(node, root)) {
                right = node.x + node.w;
            }
        }
        return right;
    }

    private boolean descendsFrom(Placed node, Placed ancestor) {
        for (Placed at = node; at != null; at = at.parent) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** Search matches first, then whatever order the walk produced. A stable sort keeps both. */
    private List<ItemNode> ordered(List<ItemNode> items) {
        List<ItemNode> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing((ItemNode node) -> !node.matchesFilter()));
        return sorted;
    }

    private int siblingCount(ItemNode parent, ItemNode focus) {
        int count = 0;
        for (ProcessNode process : parent.processes()) {
            for (ItemNode child : process.items()) {
                if (child != focus) {
                    count++;
                }
            }
        }
        return count;
    }

    private Placed item(ItemNode node, int row) {
        Placed p = new Placed();
        p.kind = Kind.ITEM;
        p.item = node;
        p.w = nodeW();
        p.row = row;
        placed.add(p);
        return p;
    }

    private Placed machine(ProcessNode node, int row) {
        Placed p = new Placed();
        p.kind = Kind.MACHINE;
        p.process = node;
        p.w = nodeW();
        p.row = row;
        placed.add(p);
        return p;
    }

    private Placed cluster(String label, int row, Runnable action) {
        Placed p = new Placed();
        p.kind = Kind.CLUSTER;
        p.label = label;
        p.row = row;
        p.action = action;
        p.w = chipW();
        placed.add(p);
        return p;
    }

    /**
     * Leaves the current tree and starts a new one from this item.
     *
     * <p>The machine choice reopens with it: a different item is a different question, and the
     * machines that mattered for the last root are usually not the ones that matter for this.
     */
    static void startFresh(EmiStack stack, Direction direction) {
        if (!ProcessTreeNavigation.reroot(stack, direction)) {
            return;
        }
        if (Minecraft.getInstance().screen instanceof ProcessGraphScreen fresh) {
            fresh.openFilters();
        }
    }

    // ------------------------------------------------------------ navigation

    private void focusOn(ItemNode node) {
        if (node == null || node == focus()) {
            return;
        }
        graph.expand(node, ProcessSearchConfig.treeWalkHops());
        path.add(node);
        afterNavigation();
    }

    private void focusIndex(int index) {
        if (index < 0 || index >= path.size() || index == path.size() - 1) {
            return;
        }
        while (path.size() > index + 1) {
            path.remove(path.size() - 1);
        }
        afterNavigation();
    }

    private void afterNavigation() {
        openMachines.clear();
        showAllMachines = false;
        rebuildView();
        frameView();
        framed = true;
    }

    private void goBack() {
        if (path.size() > 1) {
            focusIndex(path.size() - 2);
            return;
        }
        if (!ProcessTreeNavigation.back()) {
            onClose();
        }
    }

    // ------------------------------------------------------------ camera

    private int viewTop() {
        return CHROME_H + MARGIN;
    }

    private int viewHeight() {
        return Math.max(1, height - viewTop() - MARGIN);
    }

    private int[] bounds() {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Placed node : placed) {
            minX = Math.min(minX, node.x);
            maxX = Math.max(maxX, node.x + node.w);
            minY = Math.min(minY, node.y);
            maxY = Math.max(maxY, node.y + nodeH());
        }
        return new int[] {minX, maxX, minY, maxY};
    }

    /**
     * Frames the visible set at {@link #FIT_ZOOM} times the scale where it would exactly fit, so it
     * opens legible and overflows the edges a little rather than fitting and being unreadable.
     *
     * <p>It iterates, because the scale decides the compact box size and the box size decides the
     * bounds the scale is computed from. Sizes are quantised so this settles in two rounds on any
     * real graph; the cap is what guarantees it cannot oscillate if one ever does not.
     */
    private void frameView() {
        for (int pass = 0; pass < 3; pass++) {
            if (placed.isEmpty()) {
                scale = 1.0F;
                centreOn(nodeW() / 2.0, nodeH() / 2.0);
                return;
            }
            int[] box = bounds();
            int viewW = Math.max(1, width - MARGIN * 2);
            float fit = Math.min(viewW / (float) Math.max(1, box[1] - box[0]),
                    viewHeight() / (float) Math.max(1, box[3] - box[2]));
            scale = Math.max(ProcessSearchConfig.treeMinZoom(),
                    Math.min(2.0F, fit * FIT_ZOOM));

            if (!syncZoomMode()) {
                centreOn((box[0] + box[1]) / 2.0, (box[2] + box[3]) / 2.0);
                return;
            }
            rebuildView();
        }
        int[] box = bounds();
        centreOn((box[0] + box[1]) / 2.0, (box[2] + box[3]) / 2.0);
    }

    private Placed focusPlaced() {
        for (Placed node : placed) {
            if (node.isFocus) {
                return node;
            }
        }
        return placed.isEmpty() ? null : placed.get(0);
    }

    /**
     * Changes zoom across the compact threshold without the graph appearing to jump: the focused
     * node is pinned to the screen position it already had, and everything reflows around it.
     */
    private void applyScaleAcrossModes(float next) {
        Placed before = focusPlaced();
        double screenX = before == null ? width / 2.0 : offsetX + (before.x + before.w / 2.0) * scale;
        double screenY = before == null ? height / 2.0 : offsetY + (before.y + nodeH() / 2.0) * scale;

        scale = next;
        if (syncZoomMode()) {
            rebuildView();
        }

        Placed after = focusPlaced();
        if (after != null) {
            offsetX = screenX - (after.x + after.w / 2.0) * scale;
            offsetY = screenY - (after.y + nodeH() / 2.0) * scale;
        }
    }

    private void centreOn(double graphX, double graphY) {
        offsetX = width / 2.0 - graphX * scale;
        offsetY = viewTop() + viewHeight() / 2.0 - graphY * scale;
    }

    // ------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        hovered = hitTest(mouseX, mouseY);
        drawCanvas(graphics, delta);
        drawChrome(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, delta);
        if (hovered != null) {
            drawTooltip(graphics, hovered, mouseX, mouseY);
        }
    }

    /**
     * Draws the graph as the backdrop for a panel sitting over it.
     *
     * <p>Pushed behind z 0, because item stacks render 150 deep in their own right and their batch
     * is flushed at the end of the frame: a panel drawn afterwards at z 0 was both farther and
     * earlier than every icon on the graph, so the icons punched straight through it.
     */
    void renderBackdrop(GuiGraphics graphics, float delta) {
        Placed keep = hovered;
        hovered = null;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, -250);
        drawCanvas(graphics, delta);
        drawChrome(graphics, -1, -1);
        graphics.pose().popPose();
        // Force the item batch out now, so draw order agrees with the depth ordering rather than
        // fighting it.
        graphics.flush();
        hovered = keep;
    }

    private void drawCanvas(GuiGraphics graphics, float delta) {
        // Opaque, not a dim: a graph tool covering the screen should cover it, and the pack's quest
        // HUD was otherwise bleeding through the chrome.
        graphics.fill(0, 0, width, height, BACKDROP);

        graphics.enableScissor(0, CHROME_H, width, height);
        graphics.pose().pushPose();
        graphics.pose().translate(offsetX, offsetY, 0);
        graphics.pose().scale(scale, scale, 1.0F);

        for (Placed node : placed) {
            if (node.parent != null && node.owner == null) {
                connect(graphics, node.parent, node);
            }
        }
        for (Placed node : placed) {
            if (node.kind == Kind.MACHINE) {
                drawItemBlock(graphics, node);
            }
        }
        for (Placed node : placed) {
            drawNode(graphics, node, delta);
        }

        graphics.pose().popPose();
        graphics.disableScissor();

        if (placed.size() <= 1) {
            drawEmptyHint(graphics);
        }
    }

    private void drawEmptyHint(GuiGraphics graphics) {
        String line = ProcessSearchConfig.treeIncludedCategories().isEmpty()
                ? "No machines enabled yet — press Filters to choose which ones to follow"
                : "No enabled machine touches this item — try Filters, or a different item";
        graphics.drawCenteredString(font, line, width / 2, CHROME_H + viewHeight() / 2 + 40, 0xFFB0B0B0);
    }

    private void drawItemBlock(GuiGraphics graphics, Placed machine) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        boolean any = false;
        for (Placed node : placed) {
            if (node.owner != machine) {
                continue;
            }
            any = true;
            minX = Math.min(minX, node.x);
            maxX = Math.max(maxX, node.x + node.w);
            minY = Math.min(minY, node.y);
            maxY = Math.max(maxY, node.y + nodeH());
        }
        if (!any) {
            return;
        }
        graphics.fill(minX - 3, minY - 3, maxX + 3, maxY + 3, GRID_BG);

        boolean down = graph.direction.growsDown();
        int t = edgeWidth();
        int x = machine.x + machine.w / 2;
        int from = down ? machine.y + nodeH() : machine.y;
        int to = down ? minY - 3 : maxY + 3;
        vEdge(graphics, x, from, to, t);
        // A spine along the block's near edge. Without it the one stem lands over the middle column
        // and the outer columns of a 3-wide block read as attached to nothing.
        hEdge(graphics, minX, maxX, to, t);
    }

    private void connect(GuiGraphics graphics, Placed from, Placed to) {
        boolean down = graph.direction.growsDown();
        int t = edgeWidth();
        int x1 = from.x + from.w / 2;
        int x2 = to.x + to.w / 2;
        int y1 = down ? from.y + nodeH() : from.y;
        int y2 = down ? to.y : to.y + nodeH();
        int midY = (y1 + y2) / 2;
        vEdge(graphics, x1, y1, midY, t);
        hEdge(graphics, x1, x2, midY, t);
        vEdge(graphics, x2, midY, y2, t);
    }

    /** A vertical run of the given thickness, centred on {@code x}. */
    private void vEdge(GuiGraphics graphics, int x, int a, int b, int t) {
        int left = x - t / 2;
        graphics.fill(left, Math.min(a, b), left + t, Math.max(a, b), edgeColour());
    }

    /** A horizontal run, overhanging each end by half a thickness so the elbows meet cleanly. */
    private void hEdge(GuiGraphics graphics, int a, int b, int y, int t) {
        int top = y - t / 2;
        graphics.fill(Math.min(a, b) - t / 2, top, Math.max(a, b) + t - t / 2, top + t, edgeColour());
    }

    private void drawNode(GuiGraphics graphics, Placed node, float delta) {
        int x = node.x;
        int y = node.y;
        int background = switch (node.kind) {
            case MACHINE -> PROCESS_BG;
            case CLUSTER -> CLUSTER_BG;
            case ITEM -> node.isFocus ? FOCUS_BG
                    : node.isParent ? PARENT_BG
                    : node.item.matchesFilter() ? ITEM_BG_MATCH : ITEM_BG;
        };
        int w = node.w;
        int h = nodeH();
        graphics.fill(x, y, x + w, y + h, background);
        // In compact, only focus and hover are outlined: a border on every box is noise at the zoom
        // where the icon is meant to be carrying it. Edge thickness, so it survives the scale.
        boolean outlined = node == hovered || node.isFocus;
        if (!compact || outlined) {
            int border = node == hovered ? BORDER_HOVER : node.isFocus ? BORDER_FOCUS : BORDER;
            int t = compact ? edgeWidth() : 1;
            graphics.fill(x, y, x + w, y + t, border);
            graphics.fill(x, y + h - t, x + w, y + h, border);
            graphics.fill(x, y, x + t, y + h, border);
            graphics.fill(x + w - t, y, x + w, y + h, border);
        }

        if (node.kind == Kind.CLUSTER) {
            graphics.drawCenteredString(font, node.label, x + w / 2, y + (h - 8) / 2, 0xFFE8C87A);
            return;
        }

        drawIcon(graphics, node, x, y, w, h, delta);

        if (compact) {
            // The icon is the label now; the name is one hover away.
            return;
        }

        String label = node.kind == Kind.ITEM
                ? name(node.item)
                : node.process.category.getName().getString();
        int room = w - 23;
        String suffix = null;
        if (node.kind == Kind.MACHINE) {
            suffix = String.valueOf(node.process.recipeCount());
        } else if (!node.isFocus && !node.isParent && node.item.canExpand()) {
            suffix = "+";
        }
        if (suffix != null) {
            room -= font.width(suffix) + 4;
            graphics.drawString(font, suffix, x + w - 3 - font.width(suffix), y + 6,
                    node.kind == Kind.MACHINE ? 0xFFB0C4DE : 0xFF909090, false);
        }
        graphics.drawString(font, font.plainSubstrByWidth(label, room), x + 21, y + 6,
                0xFFE0E0E0, false);
    }

    /** Fills the box in compact mode, sits in the corner beside the label otherwise. */
    private void drawIcon(GuiGraphics graphics, Placed node, int x, int y, int w, int h, float delta) {
        EmiIngredient icon = node.kind == Kind.ITEM ? node.item.stack : node.process.icon;
        boolean drawable = icon != null && !icon.isEmpty();
        if (!compact) {
            if (drawable) {
                icon.render(graphics, x + 2, y + 2, delta);
            } else if (node.kind == Kind.MACHINE) {
                node.process.category.render(graphics, x + 2, y + 2, delta);
            }
            return;
        }
        // EmiIngredient always draws at 16x16, so growing it means scaling the matrix around it.
        float grown = (w - 4) / 16.0F;
        graphics.pose().pushPose();
        graphics.pose().translate(x + 2, y + (h - (w - 4)) / 2.0, 0);
        graphics.pose().scale(grown, grown, 1.0F);
        if (drawable) {
            icon.render(graphics, 0, 0, delta);
        } else if (node.kind == Kind.MACHINE) {
            node.process.category.render(graphics, 0, 0, delta);
        }
        graphics.pose().popPose();
    }

    /** Two rows: buttons and status, then the breadcrumb. Neither may draw on the other. */
    private void drawChrome(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(0, 0, width, CHROME_H, CHROME_BG);
        graphics.hLine(0, width, HEADER_H, 0xFF262626);
        graphics.hLine(0, width, CHROME_H, CHROME_LINE);

        StringBuilder status = new StringBuilder();
        status.append(graph.nodeCount()).append(" walked");
        if (graph.budgetExhausted()) {
            status.append(", capped");
        }
        if (!graph.indexReady()) {
            status.append(", index not ready — filters skipped");
        } else if (graph.excludedApplied()) {
            status.append(", excluding \"").append(graph.query).append('"');
        } else if (graph.facetsApplied() || graph.itemFilterApplied()) {
            status.append(", highlighting \"").append(graph.query).append('"');
        }
        // Truncated against where the buttons end, so the two can never collide.
        int room = Math.max(0, width - buttonsRight - 12);
        String text = font.plainSubstrByWidth(status.toString(), room);
        graphics.drawString(font, text, width - font.width(text) - 4, 8, 0xFF909090, false);

        drawBreadcrumb(graphics, mouseX, mouseY);
    }

    private void drawBreadcrumb(GuiGraphics graphics, int mouseX, int mouseY) {
        crumbs.clear();
        int x = 6;
        int y = HEADER_H + 5;

        String prefix = String.valueOf(graph.direction.symbol);
        graphics.drawString(font, prefix, x, y, 0xFF6A9AD0, false);
        x += font.width(prefix) + 6;

        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                graphics.drawString(font, ">", x, y, 0xFF707070, false);
                x += font.width(">") + 4;
            }
            String label = font.plainSubstrByWidth(name(path.get(i)), 120);
            int w = font.width(label);
            if (x + w > width - 8) {
                graphics.drawString(font, "…", x, y, 0xFF909090, false);
                break;
            }
            boolean last = i == path.size() - 1;
            boolean hover = mouseY >= HEADER_H && mouseY < CHROME_H && mouseX >= x && mouseX < x + w;
            graphics.drawString(font, label, x, y,
                    last ? 0xFFFFFFFF : hover ? 0xFFFFFFAA : 0xFF9A9A9A, false);
            crumbs.add(new Crumb(path.get(i), i, x, x + w));
            x += w + 4;
        }

        if (path.size() == 1 && placed.size() > 1) {
            // The only affordance otherwise is a small "+", which is not enough of a hint.
            String hint = "click an item to follow it · click a machine for its recipes";
            int hintWidth = font.width(hint);
            if (x + 16 + hintWidth < width - 4) {
                graphics.drawString(font, hint, width - hintWidth - 6, y, 0xFF5E5E5E, false);
            }
        }
    }

    private void drawTooltip(GuiGraphics graphics, Placed node, int mouseX, int mouseY) {
        List<ClientTooltipComponent> lines = new ArrayList<>();
        switch (node.kind) {
            case CLUSTER -> {
                lines.add(line(Component.literal(node.label)));
                lines.add(line(Component.literal("Click to list them all, unfiltered")
                        .withStyle(ChatFormatting.DARK_GRAY)));
            }
            case MACHINE -> {
                lines.add(line(node.process.category.getName()));
                lines.add(line(Component.literal(node.process.recipeCount() + " recipes "
                        + graph.direction.verb() + " " + name(node.process.parent))
                        .withStyle(ChatFormatting.GRAY)));
                lines.add(line(Component.literal("Click to list the recipes")
                        .withStyle(ChatFormatting.DARK_GRAY)));
            }
            case ITEM -> {
                try {
                    lines.addAll(node.item.stack.getTooltip());
                } catch (RuntimeException | LinkageError e) {
                    lines.add(line(Component.literal(name(node.item))));
                }
                if (node.isFocus) {
                    lines.add(line(Component.literal("You are here")
                            .withStyle(ChatFormatting.DARK_GRAY)));
                } else if (node.isParent) {
                    lines.add(line(Component.literal("Click to go back up")
                            .withStyle(ChatFormatting.DARK_GRAY)));
                } else if (node.leaf) {
                    lines.add(line(Component.literal("Click to start a new tree here")
                            .withStyle(ChatFormatting.DARK_GRAY)));
                } else {
                    lines.add(line(Component.literal("Click to follow, right-click to re-root")
                            .withStyle(ChatFormatting.DARK_GRAY)));
                }
                if (node.item.hiddenProcesses() > 0) {
                    lines.add(line(Component.literal("+" + node.item.hiddenProcesses()
                            + " machines beyond the walk cap").withStyle(ChatFormatting.YELLOW)));
                }
            }
        }
        try {
            EmiRenderHelper.drawTooltip(this, EmiDrawContext.wrap(graphics), lines, mouseX, mouseY);
        } catch (RuntimeException | LinkageError e) {
            // EMI's tooltip helper is not published API; falling back keeps the screen usable.
        }
    }

    private static ClientTooltipComponent line(Component text) {
        return ClientTooltipComponent.create(text.getVisualOrderText());
    }

    private static String name(ItemNode node) {
        try {
            return node.stack.getName().getString();
        } catch (RuntimeException | LinkageError e) {
            return String.valueOf(node.key);
        }
    }

    // ------------------------------------------------------------ input

    private Placed hitTest(int mouseX, int mouseY) {
        if (mouseY < CHROME_H) {
            return null;
        }
        double gx = (mouseX - offsetX) / scale;
        double gy = (mouseY - offsetY) / scale;
        for (Placed node : placed) {
            if (gx >= node.x && gx <= node.x + node.w && gy >= node.y && gy <= node.y + nodeH()) {
                return node;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (mouseY >= HEADER_H && mouseY < CHROME_H) {
            for (Crumb crumb : crumbs) {
                if (mouseX >= crumb.left() && mouseX < crumb.right()) {
                    focusIndex(crumb.index());
                    return true;
                }
            }
            return true;
        }

        Placed target = hitTest((int) mouseX, (int) mouseY);
        if (target != null) {
            switch (target.kind) {
                case CLUSTER -> {
                    if (target.action != null) {
                        target.action.run();
                        // Some chips open a panel instead of changing the graph. Reframing under
                        // one of those would throw away the pan you come back to.
                        if (minecraft.screen == this) {
                            rebuildView();
                            frameView();
                        }
                    }
                }
                case MACHINE -> minecraft.setScreen(
                        new ProcessRecipeListScreen(this, graph, target.process));
                case ITEM -> {
                    if (button == 1) {
                        ProcessTreeNavigation.reroot(target.item.stack, graph.direction);
                    } else if (target.isParent) {
                        focusIndex(path.size() - 2);
                    } else if (target.leaf) {
                        // There is nothing below a leaf to drill into, so following it means
                        // asking the question again from there.
                        startFresh(target.item.stack, graph.direction);
                    } else if (!target.isFocus) {
                        focusOn(target.item);
                    }
                }
            }
            return true;
        }
        if (mouseY >= CHROME_H) {
            dragging = true;
            lastDragX = mouseX;
            lastDragY = mouseY;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            offsetX += mouseX - lastDragX;
            offsetY += mouseY - lastDragY;
            lastDragX = mouseX;
            lastDragY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        double gx = (mouseX - offsetX) / scale;
        double gy = (mouseY - offsetY) / scale;
        float next = (float) Math.max(ProcessSearchConfig.treeMinZoom(),
                Math.min(2.0, scale + amount * 0.1));
        if (next == scale) {
            return true;
        }
        boolean wantCompact = next < LABEL_ZOOM;
        if (wantCompact != compact || (wantCompact && sizeFor(next) != compactSize)) {
            // The layout is about to change shape, so anchoring on the cursor is meaningless --
            // pin the focus instead and let everything reflow around it.
            applyScaleAcrossModes(next);
            return true;
        }
        scale = next;
        offsetX = mouseX - gx * scale;
        offsetY = mouseY - gy * scale;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case 256 -> {
                onClose();
                return true;
            }
            case 259 -> {
                goBack();
                return true;
            }
            case 70 -> {
                frameView();
                return true;
            }
            case 268 -> {
                focusIndex(0);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    @Override
    public void onClose() {
        ProcessTreeNavigation.close();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
