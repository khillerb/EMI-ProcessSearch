package dev.processsearch.screen;

import java.util.List;

import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.processsearch.index.tree.ProcessGraph;
import dev.processsearch.index.tree.ProcessNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Every machine that does one particular step, when more than nine of them do.
 *
 * <p>The graph draws interchangeable machines three across and three down, which is as many as
 * reads as a choice. Past that the block would be a list pretending to be a decision, so the rest
 * collapse into a {@code +N} chip and land here -- the same bargain {@link SiblingsPanel} makes for
 * the items a machine can reach.
 *
 * <p>Clicking one opens its recipes, so this is a way through rather than a dead end.
 */
public class MachineChoicePanel extends PanelScreen {
    private static final int ROW_H = 20;

    private final ProcessGraph graph;
    private final List<ProcessNode> machines;

    public static MachineChoicePanel forGroup(ProcessGraphScreen parent, ProcessGraph graph,
                                              List<ProcessNode> machines) {
        return new MachineChoicePanel(parent, graph, machines);
    }

    private MachineChoicePanel(ProcessGraphScreen parent, ProcessGraph graph,
                               List<ProcessNode> machines) {
        super(Component.literal("Machine Choices"), parent);
        this.graph = graph;
        this.machines = List.copyOf(machines);
    }

    @Override
    protected int preferredWidth() {
        return 360;
    }

    @Override
    protected int contentHeight() {
        return machines.size() * ROW_H;
    }

    @Override
    protected String heading() {
        return machines.size() + " machines do this step";
    }

    @Override
    protected void drawRows(GuiGraphics graphics, int localMouseX, int localMouseY,
                            boolean inList, float delta) {
        for (int i = 0; i < machines.size(); i++) {
            int y = i * ROW_H;
            if (y + ROW_H < scroll || y > scroll + listHeight()) {
                continue;
            }
            drawRow(graphics, machines.get(i), y,
                    inList && localMouseY >= y && localMouseY < y + ROW_H, delta);
        }
    }

    private void drawRow(GuiGraphics graphics, ProcessNode machine, int y, boolean hover,
                         float delta) {
        if (hover) {
            graphics.fill(2, y, panelWidth - 4, y + ROW_H - 1, ROW_HOVER);
        }
        EmiRecipeCategory category = machine.category;
        category.render(graphics, 6, y + 2, delta);

        String count = String.valueOf(machine.recipeCount());
        int countWidth = font.width(count);
        graphics.drawString(font, count, panelWidth - 12 - countWidth, y + 6, 0xFF909090, false);

        graphics.drawString(font,
                font.plainSubstrByWidth(category.getName().getString(),
                        panelWidth - 40 - countWidth), 28, y + 6, 0xFFE0E0E0, false);
    }

    @Override
    protected void clickRow(double localY, int button) {
        int index = (int) (localY / ROW_H);
        if (index >= 0 && index < machines.size()) {
            minecraft.setScreen(new ProcessRecipeListScreen(parent, graph, machines.get(index)));
        }
    }
}
