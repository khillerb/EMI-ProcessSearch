package dev.processsearch.screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.processsearch.index.Scan;
import dev.processsearch.index.tree.Direction;
import dev.processsearch.index.tree.ItemNode;
import dev.processsearch.index.tree.ProcessGraph;
import dev.processsearch.index.tree.ProcessGraphBuilder;
import dev.processsearch.index.tree.ProcessNode;
import dev.processsearch.index.tree.ProcessTreeNavigation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
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
public class SiblingsPanel extends Screen {
    private static final int HEADER_H = 34;
    private static final int PANEL_MARGIN = 24;
    private static final int ROW_H = 20;

    private final ProcessGraphScreen parent;
    private final ProcessGraph graph;
    private final String title;
    private final List<EmiStack> items;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private double scroll;
    private EmiStack hoveredItem;

    /** Everything one machine can produce from the parent item. */
    public static SiblingsPanel forMachine(ProcessGraphScreen parent, ProcessGraph graph,
                                           ProcessNode machine) {
        String name = machine.category.getName().getString();
        return new SiblingsPanel(parent, graph, name,
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
        super(Component.literal("Process Tree Items"));
        this.parent = parent;
        this.graph = graph;
        this.title = title;
        this.items = items;
    }

    @Override
    protected void init() {
        if (parent != null) {
            parent.resize(minecraft, width, height);
        }
        panelWidth = Math.min(400, Math.max(220, width - PANEL_MARGIN * 2));
        panelHeight = Math.min(height - PANEL_MARGIN * 2,
                Math.max(120, HEADER_H + items.size() * ROW_H + 8));
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        addRenderableWidget(Button.builder(Component.literal("< Back"), b -> back())
                .bounds(panelLeft + 4, panelTop + HEADER_H - 24, 56, 20).build());
        clampScroll();
    }

    private int listTop() {
        return panelTop + HEADER_H;
    }

    private int listBottom() {
        return panelTop + panelHeight - 4;
    }

    // ------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (parent != null) {
            parent.renderBackdrop(graphics, delta);
            graphics.fill(0, 0, width, height, 0xD0000000);
        } else {
            renderBackground(graphics);
        }
        hoveredItem = null;

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xF01A1A1A);
        drawBorder(graphics);

        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1,
                panelTop + HEADER_H, 0xFF141414);
        String heading = items.size() + " items · " + title;
        graphics.drawString(font, font.plainSubstrByWidth(heading, panelWidth - 8),
                panelLeft + 4, panelTop + 4, 0xFFFFFFFF, false);
        graphics.hLine(panelLeft, panelLeft + panelWidth - 1, panelTop + HEADER_H, 0xFF404040);

        graphics.enableScissor(panelLeft, listTop(), panelLeft + panelWidth, listBottom());
        graphics.pose().pushPose();
        graphics.pose().translate(panelLeft, listTop() - scroll, 0);

        int localMouseY = (int) (mouseY - listTop() + scroll);
        boolean inList = mouseX >= panelLeft && mouseX < panelLeft + panelWidth
                && mouseY >= listTop() && mouseY < listBottom();
        for (int i = 0; i < items.size(); i++) {
            int y = i * ROW_H;
            if (y + ROW_H < scroll || y > scroll + (listBottom() - listTop())) {
                continue;
            }
            boolean hover = inList && localMouseY >= y && localMouseY < y + ROW_H;
            if (hover) {
                graphics.fill(2, y, panelWidth - 4, y + ROW_H - 1, 0x40FFFFFF);
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

        graphics.pose().popPose();
        graphics.disableScissor();
        drawScrollbar(graphics);
        super.render(graphics, mouseX, mouseY, delta);

        if (hoveredItem != null) {
            try {
                EmiRenderHelper.drawTooltip(this, EmiDrawContext.wrap(graphics),
                        hoveredItem.getTooltip(), mouseX, mouseY);
            } catch (RuntimeException | LinkageError e) {
                // Not published API; a missing tooltip keeps the screen usable.
            }
        }
    }

    private void drawBorder(GuiGraphics graphics) {
        int right = panelLeft + panelWidth - 1;
        int bottom = panelTop + panelHeight - 1;
        graphics.hLine(panelLeft, right, panelTop, 0xFF6A6A6A);
        graphics.hLine(panelLeft, right, bottom, 0xFF6A6A6A);
        graphics.vLine(panelLeft, panelTop, bottom, 0xFF6A6A6A);
        graphics.vLine(right, panelTop, bottom, 0xFF6A6A6A);
    }

    private void drawScrollbar(GuiGraphics graphics) {
        int viewHeight = listBottom() - listTop();
        int content = items.size() * ROW_H;
        if (content <= viewHeight) {
            return;
        }
        int barHeight = Math.max(16, viewHeight * viewHeight / content);
        int barTop = listTop() + (int) (scroll * (viewHeight - barHeight) / (content - viewHeight));
        int right = panelLeft + panelWidth - 3;
        graphics.fill(right - 4, listTop(), right, listBottom(), 0x40FFFFFF);
        graphics.fill(right - 4, barTop, right, barTop + barHeight, 0xC0FFFFFF);
    }

    // ------------------------------------------------------------ input

    private void back() {
        if (parent != null) {
            minecraft.setScreen(parent);
        } else {
            ProcessTreeNavigation.openGraphScreen();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        boolean inside = mouseX >= panelLeft && mouseX < panelLeft + panelWidth
                && mouseY >= panelTop && mouseY < panelTop + panelHeight;
        if (!inside) {
            back();
            return true;
        }
        if (mouseY < listTop() || mouseY >= listBottom()) {
            return true;
        }
        int index = (int) ((mouseY - listTop() + scroll) / ROW_H);
        if (index >= 0 && index < items.size()) {
            ProcessGraphScreen.startFresh(items.get(index), graph.direction);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scroll -= amount * 24;
        clampScroll();
        return true;
    }

    private void clampScroll() {
        int max = Math.max(0, items.size() * ROW_H - (listBottom() - listTop()));
        scroll = Math.max(0, Math.min(max, scroll));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            back();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
