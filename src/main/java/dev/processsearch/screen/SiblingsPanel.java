package dev.processsearch.screen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.processsearch.index.Scan;
import dev.processsearch.index.tree.ItemNode;
import dev.processsearch.index.tree.ProcessGraph;
import dev.processsearch.index.tree.ProcessGraphBuilder;
import dev.processsearch.index.tree.ProcessNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Everything a {@code +N} chip was standing in for.
 *
 * <p>Uncapped and unfiltered on purpose. The chip exists because something was held back, so a list
 * that re-applied the width caps and the search exclusions would be a dead end.
 *
 * <p>Clicking an item here starts a fresh tree from it, since by definition you have left the branch
 * you were on.
 */
public class SiblingsPanel extends PanelScreen {
    private static final int ROW_H = 20;

    private final ProcessGraph graph;
    private final String title;
    private final List<EmiStack> items;

    private EmiStack hoveredItem;

    /** Everything one machine can produce from the parent item. */
    public static SiblingsPanel forMachine(ProcessGraphScreen parent, ProcessGraph graph,
                                           ProcessNode machine) {
        return new SiblingsPanel(parent, graph, machine.category.getName().getString(),
                ProcessGraphBuilder.allFarSide(machine, graph.direction));
    }

    /** Everything an item leads to, across all of its machines. */
    public static SiblingsPanel forItem(ProcessGraphScreen parent, ProcessGraph graph, ItemNode node) {
        Map<Object, EmiStack> stacks = new LinkedHashMap<>();
        for (ProcessNode machine : node.processes()) {
            for (EmiStack stack : ProcessGraphBuilder.allFarSide(machine, graph.direction)) {
                Object key = Scan.key(stack);
                if (key != null) {
                    stacks.putIfAbsent(key, stack);
                }
            }
        }
        String name;
        try {
            name = node.stack.getName().getString();
        } catch (RuntimeException | LinkageError e) {
            name = "this item";
        }
        return new SiblingsPanel(parent, graph, name, List.copyOf(stacks.values()));
    }

    private SiblingsPanel(ProcessGraphScreen parent, ProcessGraph graph, String title,
                          List<EmiStack> items) {
        super(Component.literal("Process Tree Items"), parent);
        this.graph = graph;
        this.title = title;
        this.items = items;
    }

    @Override
    protected int preferredWidth() {
        return 400;
    }

    @Override
    protected int contentHeight() {
        return items.size() * ROW_H;
    }

    @Override
    protected String heading() {
        return items.size() + " items · " + title;
    }

    @Override
    protected void drawRows(GuiGraphics graphics, int localMouseX, int localMouseY,
                            boolean inList, float delta) {
        hoveredItem = null;
        for (int i = 0; i < items.size(); i++) {
            int y = i * ROW_H;
            if (y + ROW_H < scroll || y > scroll + listHeight()) {
                continue;
            }
            boolean hover = inList && localMouseY >= y && localMouseY < y + ROW_H;
            if (hover) {
                graphics.fill(2, y, panelWidth - 4, y + ROW_H - 1, ROW_HOVER);
                hoveredItem = items.get(i);
            }
            EmiStack stack = items.get(i);
            stack.render(graphics, 6, y + 2, delta);
            String name;
            try {
                name = stack.getName().getString();
            } catch (RuntimeException | LinkageError e) {
                name = "?";
            }
            graphics.drawString(font, font.plainSubstrByWidth(name, panelWidth - 34), 28, y + 6,
                    0xFFE0E0E0, false);
        }
    }

    @Override
    protected void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoveredItem == null) {
            return;
        }
        try {
            EmiRenderHelper.drawTooltip(this, EmiDrawContext.wrap(graphics),
                    hoveredItem.getTooltip(), mouseX, mouseY);
        } catch (RuntimeException | LinkageError e) {
            // Not published API; a missing tooltip keeps the screen usable.
        }
    }

    @Override
    protected void clickRow(double localY, int button) {
        int index = (int) (localY / ROW_H);
        if (index >= 0 && index < items.size()) {
            ProcessGraphScreen.startFresh(items.get(index), graph.direction);
        }
    }
}
