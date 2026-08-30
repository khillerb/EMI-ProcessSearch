package dev.processsearch;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process Search adds four prefixes to EMI's search box:
 *
 * <pre>
 *   &gt;process[/property]   what MAKES this item
 *   &lt;process[/property]   what CONSUMES it
 *   *process              which MACHINE runs the process
 *   ~class                what KIND of item this is
 * </pre>
 *
 * <p>Facets are recipe categories ({@code >mixing}), the machines that run them
 * ({@code >mechanical_mixer}), and recipe-level properties that are not categories at all --
 * notably Create's heat requirement, so {@code >mixing/heat.heated} answers a question category
 * browsing cannot.
 *
 * <p>Client-only. It indexes recipes EMI already has and never talks to the server.
 */
public final class ProcessSearch implements ClientModInitializer {
    public static final String MOD_ID = "processsearch";
    public static final Logger LOGGER = LoggerFactory.getLogger("Process Search");

    @Override
    public void onInitializeClient() {
        ProcessSearchConfig.load();
        ClientEvents.register();
    }
}
