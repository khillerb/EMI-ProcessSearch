package dev.processsearch.command;

import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.emi.EmiCompat;
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
    /** Chat is a narrow place to read a ranking in; the tail is never the interesting part. */
    private static final int MAX_GAPS = 25;

    private ProcessSearchCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("processsearch")
                .then(ClientCommandManager.literal("stats").executes(ProcessSearchCommands::stats))
                .then(ClientCommandManager.literal("rebuild").executes(ProcessSearchCommands::rebuild))
                .then(ClientCommandManager.literal("gaps").executes(ProcessSearchCommands::gaps))
                .then(ClientCommandManager.literal("compat").executes(ProcessSearchCommands::compat))
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

        if (ProcessSearchConfig.facetRulesEnabled()) {
            send(source, Component.literal("  " + ProcessSearchConfig.facetRules().size()
                    + " facet rules loaded").withStyle(ChatFormatting.GRAY));
        }

        if (ProcessSearchConfig.processTree()) {
            send(source, Component.literal("  ")
                    .append(Component.literal(ProcessSearchConfig.treeConsumersKey().describe()
                                    + " / " + ProcessSearchConfig.treeProducersKey().describe())
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("  process tree, over a hovered item")
                            .withStyle(ChatFormatting.GRAY)));
            send(source, Component.literal("  ")
                    .append(Component.literal(ProcessSearchConfig.treeRouteKey().describe())
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("  route: press on what you have, then on what you want")
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
                            "  search hook: MISSING -- the prefixes will not match anything. "
                                    + "Run /processsearch compat.")
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
                int gapCount = ProcessIndex.gaps().size();
                if (gapCount > 0) {
                    send(source, Component.literal("  " + gapCount
                            + " categories with no facets of their own -- /processsearch gaps")
                            .withStyle(ChatFormatting.DARK_GRAY));
                }
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

    /**
     * Whether the EMI internals this mod hooks are still where it left them.
     *
     * <p>The mixin config fails soft so an EMI update cannot brick a live pack, and the cost of
     * that is that a moved target is silent: the prefixes just stop matching. This is the answer
     * to "it stopped working and I do not know why".
     */
    private static int compat(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        List<EmiCompat.Check> checks = EmiCompat.checks();

        long missing = checks.stream().filter(c -> c.health() == EmiCompat.Health.MISSING).count();
        long unproven = checks.stream().filter(c -> c.health() == EmiCompat.Health.UNPROVEN).count();

        send(source, Component.literal("EMI compatibility").withStyle(ChatFormatting.GOLD));
        send(source, Component.literal("Built against EMI 1.1.24. " + checks.size() + " targets: "
                + (checks.size() - missing - unproven) + " ok, " + unproven + " unproven, "
                + missing + " missing").withStyle(ChatFormatting.DARK_GRAY));

        for (EmiCompat.Check check : checks) {
            ChatFormatting colour = switch (check.health()) {
                case OK -> ChatFormatting.GREEN;
                case UNPROVEN -> ChatFormatting.YELLOW;
                case MISSING -> ChatFormatting.RED;
            };
            send(source, Component.literal("  ")
                    .append(Component.literal(check.health().name().toLowerCase(Locale.ROOT))
                            .withStyle(colour))
                    .append(Component.literal("  " + check.what()).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("  " + check.target())
                            .withStyle(ChatFormatting.DARK_GRAY)));
        }

        if (missing > 0) {
            send(source, Component.literal("Something EMI moved. The features named above will not "
                    + "work; the rest still will.").withStyle(ChatFormatting.RED));
        } else if (unproven > 0) {
            // Worth saying, because "unproven" reads as a problem and mostly is not.
            send(source, Component.literal("\"Unproven\" means the target is present but the hook "
                    + "has not run yet. Use the feature, then re-run this.")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return checks.size();
    }

    /**
     * Which categories earned nothing but their own name.
     *
     * <p>The counterpart to {@code facets}: that one says what the pack <em>can</em> be asked, this
     * one says what it cannot. A rule is only worth writing where there is something to fix, and in
     * a pack of this size the answer is not guessable -- so it is measured instead.
     */
    private static int gaps(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        if (!ProcessIndex.isReady()) {
            send(source, Component.literal("Process Search: index not ready yet")
                    .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        List<ProcessIndex.Snapshot.CategoryGap> gaps = ProcessIndex.gaps();
        if (gaps.isEmpty()) {
            send(source, Component.literal("Every category earned at least one facet. Nothing to fix.")
                    .withStyle(ChatFormatting.GREEN));
            return 1;
        }

        send(source, Component.literal("Categories with no facets beyond their own name")
                .withStyle(ChatFormatting.GOLD));
        send(source, Component.literal("Biggest first. Add rules in config/processsearch/facet_rules/.")
                .withStyle(ChatFormatting.DARK_GRAY));

        int shown = Math.min(gaps.size(), MAX_GAPS);
        for (int i = 0; i < shown; i++) {
            ProcessIndex.Snapshot.CategoryGap gap = gaps.get(i);
            send(source, Component.literal("  ")
                    .append(Component.literal(String.valueOf(gap.recipes()))
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("  " + gap.name() + "  "))
                    .append(Component.literal(gap.id()).withStyle(ChatFormatting.DARK_GRAY)));
        }
        if (gaps.size() > shown) {
            send(source, Component.literal("... and " + (gaps.size() - shown) + " more")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return shown;
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
