package dev.processsearch.index.tree;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.Scan;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * One press over an item, and you get the whole plan for it.
 *
 * <p>Simpler than routing on purpose: a route needs two ends, but a plan needs only the thing you
 * want. Where you start is not a question, because a plan always runs all the way down to what
 * nothing makes.
 */
public final class PlanAnchor {
    private PlanAnchor() {}

    /**
     * @return true when the press was consumed, so the mixin can stop EMI seeing the key
     */
    public static boolean press(EmiIngredient hovered) {
        if (!ProcessSearchConfig.processTree() || hovered == null || hovered.isEmpty()) {
            return false;
        }
        EmiStack stack = Scan.firstKeyable(hovered);
        if (stack == null) {
            return false;
        }

        ProcessGraph graph = PlanBuilder.plan(stack, 0);
        if (graph == null) {
            overlay(Component.literal("EMI is not ready yet").withStyle(ChatFormatting.GRAY));
            return true;
        }
        // Shown whether or not everything resolved. A plan that got most of the way is worth
        // reading, and the screen marks where it stopped.
        return ProcessTreeNavigation.show(graph);
    }

    private static void overlay(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) {
            mc.gui.setOverlayMessage(message, false);
        }
    }

}
