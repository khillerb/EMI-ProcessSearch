package dev.processsearch.screen;

import java.util.ArrayList;
import java.util.List;

import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.processsearch.index.tree.ProcessGraph;
import dev.processsearch.index.tree.ProcessNode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The drill-down: every recipe a machine node on the overview stands for.
 *
 * <p>Drawn as a centred panel <em>over</em> the graph rather than as a replacement for it. The graph
 * screen is kept as {@code parent} and rendered underneath, which makes this read as an overlay and
 * means Back is a single {@code setScreen} with the camera intact by construction -- there is no
 * view state to save or restore.
 *
 * <p>Rows are a compact {@code inputs -> outputs} summary, expandable to the full ingredient list.
 * Drawing a recipe properly is EMI's job, so right-clicking a row hands off to
 * {@code EmiApi.displayRecipe}.
 */
public class ProcessRecipeListScreen extends PanelScreen {
    private static final int ROW_PAD = 3;
    private static final int SLOT = 18;
    private static final int ROW_H = SLOT + ROW_PAD * 2;
    private static final int MAX_COLLAPSED_INPUTS = 5;
    private static final int MAX_COLLAPSED_OUTPUTS = 3;

    private final ProcessGraph graph;
    private final ProcessNode process;
    private final List<Row> rows = new ArrayList<>();

    private EmiIngredient hoveredIngredient;
    private Row hoveredRow;
    /**
     * Fixed once, from the collapsed rows.
     *
     * <p>Recentring the panel every time a row expands would make it jump under the cursor, so the
     * frame is sized at open and the list scrolls inside it thereafter.
     */
    private final int collapsedHeight;

    public ProcessRecipeListScreen(ProcessGraphScreen parent, ProcessGraph graph,
                                   ProcessNode process) {
        super(Component.literal("Process Recipes"), parent);
        this.graph = graph;
        this.process = process;
        for (EmiRecipe recipe : process.recipes) {
            if (recipe != null) {
                rows.add(new Row(recipe));
            }
        }
        this.collapsedHeight = rows.size() * ROW_H;
    }

    private static final class Row {
        final EmiRecipe recipe;
        boolean expanded;
        int y;
        int height = ROW_H;

        Row(EmiRecipe recipe) {
            this.recipe = recipe;
        }
    }

    @Override
    protected int preferredWidth() {
        return 440;
    }

    @Override
    protected int contentHeight() {
        return rows.isEmpty() ? 0
                : rows.get(rows.size() - 1).y + rows.get(rows.size() - 1).height;
    }

    /** Sized from the collapsed rows, so opening one scrolls rather than resizing the frame. */
    @Override
    protected int framedHeight() {
        return collapsedHeight;
    }

    @Override
    protected String heading() {
        return process.category.getName().getString();
    }

    @Override
    protected void addHeaderButtons(int x, int y) {
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(x, y, 44, 20).build());
    }

    @Override
    protected void init() {
        super.init();
        relayout();
        clampScroll();
    }

    private void relayout() {
        int y = 0;
        for (Row row : rows) {
            row.y = y;
            row.height = row.expanded ? expandedHeight(row) : ROW_H;
            y += row.height;
        }
    }

    private int expandedHeight(Row row) {
        int inputs = size(row.recipe.getInputs());
        int outputs = size(row.recipe.getOutputs());
        int lines = ceil(inputs, perLine()) + ceil(outputs, perLine());
        // Two section labels, the gaps around them, and the recipe id line at the bottom.
        return ROW_H + lines * SLOT + 42;
    }

    private int perLine() {
        return Math.max(1, (panelWidth - 24) / SLOT);
    }

    private static int ceil(int value, int per) {
        return value <= 0 ? 0 : (value + per - 1) / per;
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    // ------------------------------------------------------------ render

    @Override
    protected void drawHeaderExtras(GuiGraphics graphics, float delta) {
        int iconX = panelLeft + panelWidth - 22;
        if (process.icon != null && !process.icon.isEmpty()) {
            process.icon.render(graphics, iconX, panelTop + 4, delta);
        } else {
            process.category.render(graphics, iconX, panelTop + 4, delta);
        }

        String subtitle = process.recipeCount() + " recipes " + graph.direction.verb() + " "
                + parentName();
        graphics.drawString(font, font.plainSubstrByWidth(subtitle, panelWidth - 130),
                panelLeft + 112, panelTop + HEADER_H - 18, 0xFF909090, false);
    }

    private String parentName() {
        try {
            return process.parent.stack.getName().getString();
        } catch (RuntimeException | LinkageError e) {
            return "this item";
        }
    }

    @Override
    protected void drawRows(GuiGraphics graphics, int localMouseX, int localMouseY,
                            boolean inList, float delta) {
        hoveredIngredient = null;
        hoveredRow = null;
        for (Row row : rows) {
            if (row.y + row.height < scroll || row.y > scroll + listHeight()) {
                continue;
            }
            boolean hover = inList && localMouseY >= row.y && localMouseY < row.y + row.height;
            if (hover) {
                hoveredRow = row;
            }
            drawRow(graphics, row, localMouseX, localMouseY, hover, delta);
        }
    }

    /** Coordinates here are panel-relative: the pose is already translated to the panel. */
    private void drawRow(GuiGraphics graphics, Row row, int mouseX, int localMouseY, boolean hover,
                         float delta) {
        int top = row.y;
        graphics.fill(4, top, panelWidth - 10, top + row.height - 1, hover ? ROW_HOVER : 0x30000000);

        int x = drawStacks(graphics, row.recipe.getInputs(), 8, top + ROW_PAD,
                row.expanded ? Integer.MAX_VALUE : MAX_COLLAPSED_INPUTS, mouseX, localMouseY, delta);
        graphics.drawString(font, "→", x + 2, top + ROW_PAD + 5, 0xFFAAAAAA, false);
        x += 12;
        drawStacks(graphics, row.recipe.getOutputs(), x, top + ROW_PAD,
                row.expanded ? Integer.MAX_VALUE : MAX_COLLAPSED_OUTPUTS, mouseX, localMouseY, delta);

        if (row.expanded) {
            int y = top + ROW_H + 4;
            y = drawWrapped(graphics, "Inputs", row.recipe.getInputs(), y, mouseX, localMouseY, delta);
            y = drawWrapped(graphics, "Outputs", row.recipe.getOutputs(), y, mouseX, localMouseY, delta);
            ResourceLocation id = safeId(row.recipe);
            if (id != null) {
                graphics.drawString(font, font.plainSubstrByWidth(id.toString(), panelWidth - 20),
                        8, y, 0xFF707070, false);
            }
        }
    }

    /** @return the x just past the last slot drawn */
    private int drawStacks(GuiGraphics graphics, List<? extends EmiIngredient> stacks, int x, int y,
                           int limit, int mouseX, int localMouseY, float delta) {
        if (stacks == null) {
            return x;
        }
        int drawn = 0;
        for (EmiIngredient stack : stacks) {
            if (drawn >= limit) {
                graphics.drawString(font, "+" + (stacks.size() - drawn), x + 2, y + 5, 0xFFFFAA00, false);
                return x + 18;
            }
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            stack.render(graphics, x, y, delta);
            if (mouseX >= x && mouseX < x + 16 && localMouseY >= y && localMouseY < y + 16) {
                hoveredIngredient = stack;
            }
            x += SLOT;
            drawn++;
        }
        return x;
    }

    private int drawWrapped(GuiGraphics graphics, String label, List<? extends EmiIngredient> stacks,
                            int y, int mouseX, int localMouseY, float delta) {
        if (stacks == null || stacks.isEmpty()) {
            return y;
        }
        graphics.drawString(font, label, 8, y, 0xFF909090, false);
        y += 10;
        int perLine = perLine();
        int column = 0;
        int x = 8;
        for (EmiIngredient stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            stack.render(graphics, x, y, delta);
            if (mouseX >= x && mouseX < x + 16 && localMouseY >= y && localMouseY < y + 16) {
                hoveredIngredient = stack;
            }
            x += SLOT;
            if (++column >= perLine) {
                column = 0;
                x = 8;
                y += SLOT;
            }
        }
        return y + SLOT + 2;
    }

    @Override
    protected void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoveredIngredient != null) {
            drawTooltip(graphics, hoveredIngredient.getTooltip(), mouseX, mouseY);
        } else if (hoveredRow != null) {
            List<ClientTooltipComponent> lines = new ArrayList<>();
            lines.add(line(Component.literal(hoveredRow.expanded
                    ? "Click to collapse" : "Click to expand")));
            lines.add(line(Component.literal("Right-click to open in EMI")
                    .withStyle(ChatFormatting.DARK_GRAY)));
            drawTooltip(graphics, lines, mouseX, mouseY);
        }
    }

    private void drawTooltip(GuiGraphics graphics, List<ClientTooltipComponent> lines,
                             int mouseX, int mouseY) {
        try {
            EmiRenderHelper.drawTooltip(this, EmiDrawContext.wrap(graphics), lines, mouseX, mouseY);
        } catch (RuntimeException | LinkageError e) {
            // Not published API; a missing tooltip keeps the screen usable if it moves.
        }
    }

    private static ClientTooltipComponent line(Component text) {
        return ClientTooltipComponent.create(text.getVisualOrderText());
    }

    private static ResourceLocation safeId(EmiRecipe recipe) {
        try {
            return recipe.getId();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ------------------------------------------------------------ input

    @Override
    protected void clickRow(double localY, int button) {
        for (Row row : rows) {
            if (localY >= row.y && localY < row.y + row.height) {
                if (button == 1) {
                    // Hand off to EMI rather than reimplementing a recipe layout.
                    try {
                        EmiApi.displayRecipe(row.recipe);
                    } catch (RuntimeException | LinkageError e) {
                        // Nothing useful to do; the row simply does not open.
                    }
                    return;
                }
                row.expanded = !row.expanded;
                relayout();
                clampScroll();
                return;
            }
        }
    }
}
