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
public class CategoryFilterPanel extends PanelScreen {
    private static final int ROW_H = 20;
    private static final int BOX = 10;

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> included;
    private final Set<String> original;

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
        super(Component.literal("Process Tree Filters"), parent);
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

    @Override
    protected int preferredWidth() {
        return 320;
    }

    @Override
    protected int contentHeight() {
        return entries.size() * ROW_H;
    }

    @Override
    protected String heading() {
        return "Machines to follow  (" + included.size() + " on)";
    }

    @Override
    protected void addHeaderButtons(int x, int y) {
        addRenderableWidget(Button.builder(Component.literal("None"), b -> setAll(false))
                .bounds(x, y, 44, 20).build());
        addRenderableWidget(Button.builder(Component.literal("All"), b -> setAll(true))
                .bounds(x + 48, y, 40, 20).build());
    }

    private void setAll(boolean on) {
        included.clear();
        if (on) {
            for (Entry entry : entries) {
                included.add(entry.id);
            }
        }
    }

    @Override
    protected void drawRows(GuiGraphics graphics, int localMouseX, int localMouseY,
                            boolean inList, float delta) {
        for (int i = 0; i < entries.size(); i++) {
            int y = i * ROW_H;
            if (y + ROW_H < scroll || y > scroll + listHeight()) {
                continue;
            }
            drawRow(graphics, entries.get(i), y,
                    inList && localMouseY >= y && localMouseY < y + ROW_H, delta);
        }
    }

    private void drawRow(GuiGraphics graphics, Entry entry, int y, boolean hover, float delta) {
        if (hover) {
            graphics.fill(2, y, panelWidth - 4, y + ROW_H - 1, ROW_HOVER);
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
        graphics.drawString(font, count, panelWidth - 8 - countWidth, y + 6, 0xFF909090, false);

        int room = panelWidth - 52 - countWidth;
        String name = font.plainSubstrByWidth(entry.name, room);
        graphics.drawString(font, name, 42, y + 6, on ? 0xFFE0E0E0 : 0xFF707070, false);
        if (!entry.qualifier.isEmpty()) {
            int used = font.width(name) + 4;
            String qualifier = font.plainSubstrByWidth(entry.qualifier, Math.max(0, room - used));
            graphics.drawString(font, qualifier, 42 + used, y + 6, 0xFF5E5E5E, false);
        }
    }

    @Override
    protected void clickRow(double localY, int button) {
        int index = (int) (localY / ROW_H);
        if (index >= 0 && index < entries.size()) {
            String id = entries.get(index).id;
            if (!included.remove(id)) {
                included.add(id);
            }
        }
    }

    /**
     * Saves on the way out, whichever way out was taken.
     *
     * <p>Every dismissal lands here -- the Back button, Escape, and clicking the graph behind --
     * so ticking a machine and pressing Escape does what it looks like it does rather than
     * discarding the change.
     */
    @Override
    protected void back() {
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
    public void onClose() {
        back();
    }
}
