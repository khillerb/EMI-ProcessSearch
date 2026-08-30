package dev.processsearch.command;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;
import dev.processsearch.input.KeyHook;
import dev.processsearch.recipe.RecipeFilter;
import dev.processsearch.search.SearchHook;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * {@code /processsearch} -- client-side, for checking what actually got indexed.
 *
 * <p>{@code facets} exists because the token vocabulary comes from whatever mods are installed and
 * so cannot be documented up front. It is how you find that the token is {@code fan_washing} rather
 * than {@code washing}, or that a heated mixing recipe is reachable as {@code mixing/heat.heated}.
 */
public final class ProcessSearchCommands {
    private static final int MAX_LISTED = 60;

    private ProcessSearchCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("processsearch")
                .then(ClientCommandManager.literal("stats").executes(ProcessSearchCommands::stats))
                .then(ClientCommandManager.literal("rebuild").executes(ProcessSearchCommands::rebuild))
                .then(ClientCommandManager.literal("facets")
                        .executes(ctx -> facets(ctx, ""))
                        .then(ClientCommandManager.argument("contains", StringArgumentType.string())
                                .executes(ctx -> facets(ctx, StringArgumentType.getString(ctx, "contains")))))
                .executes(ProcessSearchCommands::stats));
    }

    private static int stats(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();

        send(source, Component.literal("Process Search").withStyle(ChatFormatting.GOLD));
        send(source, prefixLine(ProcessSearchConfig.madeByPrefix(), "process[/property]", "made by"));
        send(source, prefixLine(ProcessSearchConfig.usedInPrefix(), "process[/property]", "used in"));
        send(source, prefixLine(ProcessSearchConfig.machineForPrefix(), "process", "machine for"));
        send(source, prefixLine(ProcessSearchConfig.itemClassPrefix(), "class", "item class"));

        if (ProcessSearchConfig.processTree()) {
            send(source, Component.literal("  ")
                    .append(Component.literal(ProcessSearchConfig.treeConsumersKey().describe()
                                    + " / " + ProcessSearchConfig.treeProducersKey().describe())
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("  process tree, over a hovered item")
                            .withStyle(ChatFormatting.GRAY)));
            if (KeyHook.hasFired()) {
                send(source, Component.literal("  key hook: installed").withStyle(ChatFormatting.GREEN));
            } else {
                // Unlike the search hook this one cannot be probed without running EMI's real key
                // handling, so the honest answer is "not seen yet".
                send(source, Component.literal("  key hook: not exercised yet -- press any key with "
                        + "EMI open, then re-run this").withStyle(ChatFormatting.YELLOW));
            }
        }

        if (SearchHook.isInstalled()) {
            send(source, Component.literal("  search hook: installed").withStyle(ChatFormatting.GREEN));
        } else {
            // The mixin config fails soft so an EMI update cannot brick a live pack. The cost is
            // that a missed hook is invisible unless something says so out loud.
            send(source, Component.literal(
                            "  search hook: MISSING -- the prefixes will not match anything. Check the log.")
                    .withStyle(ChatFormatting.RED));
        }

        switch (ProcessIndex.state()) {
            case IDLE -> send(source, Component.literal("  index: not built (open EMI, or search with a prefix)")
                    .withStyle(ChatFormatting.YELLOW));
            case BUILDING -> send(source, Component.literal("  index: building, "
                    + ProcessIndex.recipesIndexed() + " recipes so far").withStyle(ChatFormatting.YELLOW));
            case READY -> {
                if (ProcessIndex.wasReused()) {
                    send(source, Component.literal("  index: ready -- reused, no rebuild needed ("
                                    + ProcessIndex.fingerprintMillis() + " ms to check)")
                            .withStyle(ChatFormatting.GREEN));
                } else {
                    send(source, Component.literal("  index: ready -- " + ProcessIndex.workMillis()
                                    + " ms of work, spread over " + ProcessIndex.elapsedSeconds() + " s")
                            .withStyle(ChatFormatting.GREEN));
                }
                send(source, Component.literal("  " + ProcessIndex.recipesIndexed() + " recipes over "
                        + ProcessIndex.categoriesIndexed() + " categories"));
                send(source, Component.literal("  " + ProcessIndex.producedEntryCount() + " made-by, "
                        + ProcessIndex.consumedEntryCount() + " used-in, "
                        + ProcessIndex.machineEntryCount() + " machine entries"));
                send(source, Component.literal("  " + ProcessIndex.facetCount() + " distinct facets"));
            }
        }

        if (RecipeFilter.isActive()) {
            send(source, Component.literal("  recipe pages filtered: " + RecipeFilter.lastKept()
                    + " of " + RecipeFilter.lastTotal()).withStyle(ChatFormatting.AQUA));
        }
        return 1;
    }

    private static Component prefixLine(char prefix, String shape, String meaning) {
        return Component.literal("  ")
                .append(Component.literal(prefix + shape).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  " + meaning).withStyle(ChatFormatting.GRAY));
    }

    private static int rebuild(CommandContext<FabricClientCommandSource> ctx) {
        ProcessSearchConfig.load();
        SearchHook.resetProbe();
        // dropCache, not retire: this command exists to force a real rebuild, so reusing the index
        // it has just decided to discard would defeat the point.
        ProcessIndex.dropCache();
        RecipeFilter.invalidate();
        ProcessIndex.requestBuild();
        send(ctx.getSource(), Component.literal("Process Search: config reloaded, rebuilding index")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int facets(CommandContext<FabricClientCommandSource> ctx, String contains) {
        FabricClientCommandSource source = ctx.getSource();
        if (!ProcessIndex.isReady()) {
            send(source, Component.literal("Process Search: index not ready yet")
                    .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        List<String> matches = ProcessIndex.facetsMatching(contains, MAX_LISTED + 1);
        if (matches.isEmpty()) {
            send(source, Component.literal("No facets matching '" + contains + "'")
                    .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        boolean truncated = matches.size() > MAX_LISTED;
        List<String> shown = truncated ? matches.subList(0, MAX_LISTED) : matches;
        send(source, Component.literal(String.join(", ", shown)).withStyle(ChatFormatting.GRAY));
        if (truncated) {
            send(source, Component.literal("... narrow it with /processsearch facets \"text\"")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return shown.size();
    }

    private static void send(FabricClientCommandSource source, Component message) {
        source.sendFeedback(message);
    }
}
