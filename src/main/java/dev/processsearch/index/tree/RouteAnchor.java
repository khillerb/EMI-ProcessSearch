package dev.processsearch.index.tree;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.Scan;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The two-press gesture behind route finding: mark where you are, then point at where you want to
 * be.
 *
 * <p>Deliberately not a picker screen. The {@code <} and {@code >} keys already work by hovering a
 * stack and pressing, so routing reuses the same motion rather than introducing a search box that
 * would duplicate the one EMI already has three feet away.
 *
 * <p>State is a single stack, client-side and transient. It survives closing and reopening EMI --
 * you often want to go looking for the target -- but not a world change, since the recipes it was
 * anchored against are gone by then.
 */
public final class RouteAnchor {
    private static EmiStack anchor;

    private RouteAnchor() {}

    public static boolean isSet() {
        return anchor != null;
    }

    /** @return the anchored stack, or null. Only for status text. */
    public static EmiStack anchor() {
        return anchor;
    }

    public static void clear() {
        anchor = null;
    }

    /**
     * Handles one press of the route key over a stack.
     *
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

        if (anchor == null) {
            anchor = stack;
            overlay(Component.literal("Route from ")
                    .append(name(stack).copy().withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" — now press ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(ProcessSearchConfig.treeRouteKey().describe())
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" on what you want")
                            .withStyle(ChatFormatting.GRAY)));
            return true;
        }

        if (RouteBuilder.sameItem(anchor, stack)) {
            // Pressing it again on the same item is how you back out, so it must not route an item
            // to itself and open an empty screen.
            anchor = null;
            overlay(Component.literal("Route cancelled").withStyle(ChatFormatting.GRAY));
            return true;
        }

        EmiStack from = anchor;
        anchor = null;
        return open(from, stack);
    }

    private static boolean open(EmiStack from, EmiStack to) {
        ProcessGraph graph = RouteBuilder.route(from, to, 0);
        if (graph == null) {
            // EMI itself was unreachable, which is not a routing failure and has no screen to show.
            overlay(Component.literal("EMI is not ready yet").withStyle(ChatFormatting.GRAY));
            return true;
        }
        // Shown whether or not a path was found: a failed search still gets a screen, because that
        // is where the Deeper button lives.
        return ProcessTreeNavigation.show(graph);
    }

    private static Component name(EmiStack stack) {
        try {
            return stack.getName();
        } catch (RuntimeException | LinkageError e) {
            return Component.literal("that item");
        }
    }

    private static void overlay(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) {
            mc.gui.setOverlayMessage(message, false);
        }
    }

}
