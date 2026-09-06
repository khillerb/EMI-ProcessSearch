package dev.processsearch.screen;

import java.util.ArrayList;
import java.util.List;

import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.processsearch.index.tree.Direction;
import dev.processsearch.index.tree.ProcessGraph;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * What a build plan actually asks of you: machines to build, materials to gather.
 *
 * <p>The tree is the reasoning; this is the answer. Nobody works from a graph -- they work from
 * "build these four things and go and mine that" -- so the summary is the part worth reading twice
 * and the part to keep open in another window.
 *
 * <p>Rows are in the order the plan met them, which is roughly the order you would build in:
 * the deepest prerequisites are found last, so the list reads top-down as the thing you want back
 * towards the ore.
 */
public class PlanSummaryPanel extends PanelScreen {
    private static final int ROW_H = 20;
    private static final int SECTION_H = 14;

    private final ProcessGraph graph;
    private final List<Row> rows = new ArrayList<>();

    private EmiStack hoveredStack;

    /**
     * Either a section heading, or one thing to build or gather.
     *
     * @param heading a section title when nothing else is set, otherwise an "or ..." note
     */
    private record Row(String heading, EmiStack stack, EmiRecipeCategory category, int colour) {
        boolean isHeading() {
            return heading != null && stack == null && category == null;
        }

        int height() {
            return isHeading() ? SECTION_H : ROW_H;
        }
    }

    public PlanSummaryPanel(ProcessGraphScreen parent, ProcessGraph graph) {
        super(Component.literal("Build Plan"), parent);
        this.graph = graph;

        ProcessGraph.PlanSummary summary = graph.planSummary();
        if (summary == null) {
            return;
        }
        if (!summary.machines().isEmpty()) {
            rows.add(new Row("Machines to build  (" + summary.machines().size() + ")",
                    null, null, 0xFFB0C4DE));
            for (EmiRecipeCategory category : summary.machines()) {
                EmiRecipeCategory either = summary.alternatives().get(category);
                rows.add(new Row(either == null ? null
                        : "or " + either.getName().getString(), null, category, 0xFFE0E0E0));
            }
        }
        if (!summary.raw().isEmpty()) {
            rows.add(new Row("Materials to gather  (" + summary.raw().size() + ")",
                    null, null, 0xFF9ACD7A));
            for (EmiStack stack : summary.raw()) {
                rows.add(new Row(null, stack, null, 0xFFE0E0E0));
            }
        }
        if (!summary.missing().isEmpty()) {
            // Named rather than hidden: a plan that quietly stopped short would be worse than one
            // that says which corner it could not work out.
            rows.add(new Row("Could not work out  (" + summary.missing().size() + ")",
                    null, null, 0xFFE0A050));
            for (EmiStack stack : summary.missing()) {
                rows.add(new Row(null, stack, null, 0xFFC0A070));
            }
        }
    }

    @Override
    protected int preferredWidth() {
        return 360;
    }

    @Override
    protected int contentHeight() {
        int total = 0;
        for (Row row : rows) {
            total += row.height();
        }
        return total;
    }

    @Override
    protected String heading() {
        ProcessGraph.PlanSummary summary = graph.planSummary();
        return summary == null ? "Build plan"
                : "Build plan · " + summary.deepest() + (summary.deepest() == 1 ? " tier" : " tiers");
    }

    @Override
    protected void drawRows(GuiGraphics graphics, int localMouseX, int localMouseY,
                            boolean inList, float delta) {
        hoveredStack = null;
        int y = 0;
        for (Row row : rows) {
            if (y + row.height() >= scroll && y <= scroll + listHeight()) {
                drawRow(graphics, row, y,
                        inList && localMouseY >= y && localMouseY < y + row.height(), delta);
            }
            y += row.height();
        }
    }

    private void drawRow(GuiGraphics graphics, Row row, int y, boolean hover, float delta) {
        if (row.isHeading()) {
            graphics.drawString(font, row.heading(), 6, y + 3, row.colour(), false);
            return;
        }
        if (hover) {
            graphics.fill(2, y, panelWidth - 4, y + ROW_H - 1, ROW_HOVER);
        }
        String name;
        if (row.category() != null) {
            row.category().render(graphics, 6, y + 2, delta);
            name = row.category().getName().getString();
        } else {
            row.stack().render(graphics, 6, y + 2, delta);
            if (hover) {
                hoveredStack = row.stack();
            }
            name = safeName(row.stack());
        }
        String shown = font.plainSubstrByWidth(name, panelWidth - 34);
        graphics.drawString(font, shown, 28, y + 6, row.colour(), false);
        if (row.heading() != null) {
            // The interchangeable machine, greyed beside the one the plan costed.
            int used = font.width(shown) + 6;
            graphics.drawString(font,
                    font.plainSubstrByWidth(row.heading(), Math.max(0, panelWidth - 40 - used)),
                    28 + used, y + 6, 0xFF7A7A7A, false);
        }
    }

    @Override
    protected void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoveredStack == null) {
            return;
        }
        try {
            EmiRenderHelper.drawTooltip(this, EmiDrawContext.wrap(graphics),
                    hoveredStack.getTooltip(), mouseX, mouseY);
        } catch (RuntimeException | LinkageError e) {
            // Not published API; a missing tooltip keeps the panel usable.
        }
    }

    @Override
    protected void clickRow(double localY, int button) {
        // Clicking a material plans it in turn, which is how you drill into "and how do I get
        // that?" without going back to the grid.
        int y = 0;
        for (Row row : rows) {
            if (localY >= y && localY < y + row.height()) {
                if (row.stack() != null) {
                    ProcessGraphScreen.startFresh(row.stack(), Direction.PRODUCERS);
                }
                return;
            }
            y += row.height();
        }
    }

    private static String safeName(EmiStack stack) {
        try {
            return stack.getName().getString();
        } catch (RuntimeException | LinkageError e) {
            return "?";
        }
    }
}
