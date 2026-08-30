package dev.processsearch.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.tree.ProcessGraph;
import dev.processsearch.index.tree.ProcessTreeNavigation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Which machines the tree is allowed to follow.
 *
 * <p>Opt-in: nothing is followed until you tick it. Every category the walk met is listed, including
 * the ones it skipped -- a panel that only showed what survived would leave an empty allowlist with
 * no way out. Toggling writes {@code treeIncludedCategories} and rebuilds, because the graph cache is
 * keyed on root, direction and query and would otherwise hand back the graph built under the old
 * rules.
 */
public class CategoryFilterPanel extends Screen {
    private static final int HEADER_H = 34;
    private static final int PANEL_W = 320;
    private static final int MARGIN = 24;
    private static final int ROW_H = 20;
    private static final int BOX = 10;

    private final ProcessGraphScreen parent;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> included;
    private final Set<String> original;

    private int panelLeft;
    private int panelTop;
    private int panelHeight;
    private double scroll;

    private static final class Entry {
        final EmiRecipeCategory category;
        final String id;
        final int recipes;
        final String name;
        /** Set only where two categories share a display name, to tell them apart. */
        String qualifier = "";

        Entry(EmiRecipeCategory category, int recipes) {
            this.category = category;
            this.id = category.getId().toString();
            this.recipes = recipes;
            this.name = category.getName().getString();
        }
    }

    public CategoryFilterPanel(ProcessGraphScreen parent, ProcessGraph graph) {
        super(Component.literal("Process Tree Filters"));
        this.parent = parent;
        this.included = new HashSet<>(ProcessSearchConfig.treeIncludedCategories());
        this.original = new HashSet<>(this.included);
        for (Map.Entry<EmiRecipeCategory, Integer> seen : graph.encountered().entrySet()) {
            if (seen.getKey() != null) {
                entries.add(new Entry(seen.getKey(), seen.getValue()));
            }
        }
        // Busiest first: the thing flooding the graph is the thing you came here to switch off.
        entries.sort(Comparator.comparingInt((Entry e) -> -e.recipes).thenComparing(e -> e.id));
        disambiguate();
    }

    @Override
    protected void init() {
        if (parent != null) {
            parent.resize(minecraft, width, height);
        }
        panelHeight = Math.min(height - MARGIN * 2, HEADER_H + entries.size() * ROW_H + 8);
        panelHeight = Math.max(panelHeight, HEADER_H + ROW_H + 8);
        panelLeft = (width - PANEL_W) / 2;
        panelTop = (height - panelHeight) / 2;

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(panelLeft + 4, panelTop + HEADER_H - 24, 44, 20).build());
        addRenderableWidget(Button.builder(Component.literal("None"), b -> setAll(false))
                .bounds(panelLeft + 52, panelTop + HEADER_H - 24, 44, 20).build());
        addRenderableWidget(Button.builder(Component.literal("All"), b -> setAll(true))
                .bounds(panelLeft + 100, panelTop + HEADER_H - 24, 40, 20).build());
    }

    /**
     * Two mods can ship a category with the same display name -- this pack has two called Entropy
     * Manipulator -- and two identical rows with different meanings is worse than a long label.
     */
    private void disambiguate() {
        Map<String, Integer> counts = new HashMap<>();
        for (Entry entry : entries) {
            counts.merge(entry.name, 1, Integer::sum);
        }
        for (Entry entry : entries) {
            if (counts.getOrDefault(entry.name, 0) > 1) {
                entry.qualifier = entry.category.getId().getNamespace();
            }
        }
    }

    private void setAll(boolean on) {
        included.clear();
        if (on) {
            for (Entry entry : entries) {
                included.add(entry.id);
            }
        }
    }

    private int listTop() {
        return panelTop + HEADER_H;
    }

    private int listBottom() {
        return panelTop + panelHeight - 4;
    }

    private int contentHeight() {
        return entries.size() * ROW_H;
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

        graphics.fill(panelLeft, panelTop, panelLeft + PANEL_W, panelTop + panelHeight, 0xF01A1A1A);
        drawBorder(graphics);

        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + PANEL_W - 1, panelTop + HEADER_H,
                0xFF141414);
        graphics.drawString(font, "Machines to follow  (" + included.size() + " on)",
                panelLeft + 4, panelTop + 4, 0xFFFFFFFF, false);
        graphics.hLine(panelLeft, panelLeft + PANEL_W - 1, panelTop + HEADER_H, 0xFF404040);

        graphics.enableScissor(panelLeft, listTop(), panelLeft + PANEL_W, listBottom());
        graphics.pose().pushPose();
        graphics.pose().translate(panelLeft, listTop() - scroll, 0);

        int localMouseY = (int) (mouseY - listTop() + scroll);
        boolean inList = mouseX >= panelLeft && mouseX < panelLeft + PANEL_W
                && mouseY >= listTop() && mouseY < listBottom();
        for (int i = 0; i < entries.size(); i++) {
            int y = i * ROW_H;
            if (y + ROW_H < scroll || y > scroll + (listBottom() - listTop())) {
                continue;
            }
            drawRow(graphics, entries.get(i), y,
                    inList && localMouseY >= y && localMouseY < y + ROW_H, delta);
        }

        graphics.pose().popPose();
        graphics.disableScissor();

        super.render(graphics, mouseX, mouseY, delta);
    }

    private void drawRow(GuiGraphics graphics, Entry entry, int y, boolean hover, float delta) {
        if (hover) {
            graphics.fill(2, y, PANEL_W - 4, y + ROW_H - 1, 0x40FFFFFF);
        }
        boolean on = included.contains(entry.id);

        int boxY = y + (ROW_H - BOX) / 2;
        graphics.fill(6, boxY, 6 + BOX, boxY + BOX, on ? 0xFF3C7A3C : 0xFF2A2A2A);
        graphics.hLine(6, 6 + BOX - 1, boxY, 0xFF8A8A8A);
        graphics.hLine(6, 6 + BOX - 1, boxY + BOX - 1, 0xFF8A8A8A);
        graphics.vLine(6, boxY, boxY + BOX - 1, 0xFF8A8A8A);
        graphics.vLine(6 + BOX - 1, boxY, boxY + BOX - 1, 0xFF8A8A8A);
        if (on) {
            graphics.drawString(font, "x", 8, boxY + 1, 0xFFFFFFFF, false);
        }

        entry.category.render(graphics, 22, y + 2, delta);

        String count = String.valueOf(entry.recipes);
        int countWidth = font.width(count);
        graphics.drawString(font, count, PANEL_W - 8 - countWidth, y + 6, 0xFF909090, false);

        int room = PANEL_W - 52 - countWidth;
        String name = font.plainSubstrByWidth(entry.name, room);
        graphics.drawString(font, name, 42, y + 6, on ? 0xFFE0E0E0 : 0xFF707070, false);
        if (!entry.qualifier.isEmpty()) {
            int used = font.width(name) + 4;
            String qualifier = font.plainSubstrByWidth(entry.qualifier, Math.max(0, room - used));
            graphics.drawString(font, qualifier, 42 + used, y + 6, 0xFF5E5E5E, false);
        }
    }

    private void drawBorder(GuiGraphics graphics) {
        int right = panelLeft + PANEL_W - 1;
        int bottom = panelTop + panelHeight - 1;
        graphics.hLine(panelLeft, right, panelTop, 0xFF6A6A6A);
        graphics.hLine(panelLeft, right, bottom, 0xFF6A6A6A);
        graphics.vLine(panelLeft, panelTop, bottom, 0xFF6A6A6A);
        graphics.vLine(right, panelTop, bottom, 0xFF6A6A6A);
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        boolean insidePanel = mouseX >= panelLeft && mouseX < panelLeft + PANEL_W
                && mouseY >= panelTop && mouseY < panelTop + panelHeight;
        if (!insidePanel) {
            onClose();
            return true;
        }
        if (mouseY < listTop() || mouseY >= listBottom()) {
            return true;
        }
        int index = (int) ((mouseY - listTop() + scroll) / ROW_H);
        if (index >= 0 && index < entries.size()) {
            String id = entries.get(index).id;
            if (!included.remove(id)) {
                included.add(id);
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scroll -= amount * 20;
        int max = Math.max(0, contentHeight() - (listBottom() - listTop()));
        scroll = Math.max(0, Math.min(max, scroll));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (included.equals(original)) {
            // Nothing changed, so nothing to save and nothing to rebuild.
            minecraft.setScreen(parent);
            return;
        }
        ProcessSearchConfig.setTreeIncludedCategories(new ArrayList<>(included));
        if (!ProcessTreeNavigation.rebuildCurrent()) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
