package dev.processsearch;

import dev.processsearch.command.ProcessSearchCommands;
import dev.processsearch.index.ProcessIndex;
import dev.processsearch.index.tree.ProcessTreeNavigation;
import dev.processsearch.recipe.RecipeFilter;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Drives the index build: started on the first EMI-bearing screen, advanced a slice per tick.
 *
 * <p>Everything that touches the index happens here, on the client thread. EMI reloads recipes on
 * its own thread and searches on another, so both of those only ever raise a flag that this reads.
 */
public final class ClientEvents {
    private ClientEvents() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientEvents::onClientTick);

        // Recipes belong to the connection. Holding the index across a disconnect would serve
        // answers from the previous world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ProcessIndex.invalidate());

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> ProcessSearchCommands.register(dispatcher));
    }

    private static void onClientTick(Minecraft client) {
        if (ProcessIndex.resetPending()) {
            // retire, not reset: the index is kept aside so the next join can reuse it if the
            // recipes turn out to be identical, which is the usual case going from a singleplayer
            // world to a server running the same pack.
            ProcessIndex.retire();
            RecipeFilter.invalidate();
            // The graph holds EmiRecipe objects, which an EMI reload replaces wholesale -- unlike
            // the index, it cannot be carried across.
            ProcessTreeNavigation.invalidate();
        }

        switch (ProcessIndex.state()) {
            case IDLE -> {
                // coldQueryPending means someone typed a facet query before the index existed;
                // building for that is the whole reason the search thread bothers to flag it.
                if (ProcessIndex.coldQueryPending() || isEmiBearing(client.screen)) {
                    ProcessIndex.requestBuild();
                }
            }
            case BUILDING -> ProcessIndex.pump(ProcessSearchConfig.buildBudgetMillis() * 1_000_000L);
            case READY -> {
                // Nothing to do.
            }
        }
    }

    /**
     * EMI's overlay rides on top of every container screen, and its own recipe screen is a plain
     * {@link Screen} in the {@code dev.emi.emi} package. Either one means the player is somewhere
     * they could type a search.
     */
    private static boolean isEmiBearing(Screen screen) {
        if (screen == null) {
            return false;
        }
        return screen instanceof AbstractContainerScreen<?>
                || screen.getClass().getName().startsWith("dev.emi.emi.");
    }
}
