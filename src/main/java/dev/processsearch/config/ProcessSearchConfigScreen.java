package dev.processsearch.config;

import dev.processsearch.ProcessSearchConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Cloth Config screen behind the main menu's config button.
 *
 * <p>Edits a {@link ProcessSearchConfig.Draft} rather than the live config, and applies it once when
 * Cloth reports a save. Everything takes effect immediately -- the tree reads its numbers per frame
 * and the index fingerprint includes the config generation, so a changed item-class list rebuilds
 * rather than being served from cache.
 *
 * <p>{@code treeIncludedCategories} is not here on purpose. It is a list of whatever machines the
 * graph you are looking at happens to touch, so the Filters button on the graph is the only place
 * that can offer it meaningfully.
 */
public final class ProcessSearchConfigScreen {
    private ProcessSearchConfigScreen() {}

    public static Screen create(Screen parent) {
        ProcessSearchConfig.Draft draft = ProcessSearchConfig.draft();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Process Search"))
                .setSavingRunnable(() -> ProcessSearchConfig.apply(draft));
        ConfigEntryBuilder entry = builder.entryBuilder();

        search(builder, entry, draft);
        tree(builder, entry, draft);
        itemClasses(builder, entry, draft);
        indexing(builder, entry, draft);

        return builder.build();
    }

    private static void search(ConfigBuilder builder, ConfigEntryBuilder entry,
                               ProcessSearchConfig.Draft draft) {
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("Search"));
        category.addEntry(entry.startStrField(Component.literal("\"Made by\" prefix"), draft.madeByPrefix)
                .setDefaultValue(">")
                .setTooltip(Component.literal("One character. EMI already claims @ # $ and - | &."))
                .setSaveConsumer(v -> draft.madeByPrefix = v)
                .build());
        category.addEntry(entry.startStrField(Component.literal("\"Used in\" prefix"), draft.usedInPrefix)
                .setDefaultValue("<")
                .setSaveConsumer(v -> draft.usedInPrefix = v)
                .build());
        category.addEntry(entry.startStrField(Component.literal("\"Machine for\" prefix"),
                        draft.machineForPrefix)
                .setDefaultValue("*")
                .setSaveConsumer(v -> draft.machineForPrefix = v)
                .build());
        category.addEntry(entry.startStrField(Component.literal("\"Item class\" prefix"),
                        draft.itemClassPrefix)
                .setDefaultValue("~")
                .setSaveConsumer(v -> draft.itemClassPrefix = v)
                .build());

        category.addEntry(entry.startBooleanToggle(Component.literal("Filter recipe pages"),
                        draft.enableRecipePageFilter)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Apply the search box to recipe screens, not just the grid."))
                .setSaveConsumer(v -> draft.enableRecipePageFilter = v)
                .build());
        category.addEntry(entry.startBooleanToggle(Component.literal("Create facets"),
                        draft.enableCreateFacets)
                .setDefaultValue(true)
                .setTooltip(Component.literal("heat.* and speed.* from Create's ProcessingRecipe."))
                .setSaveConsumer(v -> draft.enableCreateFacets = v)
                .build());
        category.addEntry(entry.startBooleanToggle(Component.literal("Modern Industrialization facets"),
                        draft.enableModernIndustrializationFacets)
                .setDefaultValue(true)
                .setTooltip(Component.literal("eu.* voltage tiers and speed.*."))
                .setSaveConsumer(v -> draft.enableModernIndustrializationFacets = v)
                .build());
        category.addEntry(entry.startBooleanToggle(Component.literal("Facet rules"),
                        draft.enableFacetRules)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Data-driven facets from config/processsearch/facet_rules/."),
                        Component.literal("Covers mods with no built-in support; edit the JSON to add"),
                        Component.literal("more. /processsearch gaps shows which mods want one."))
                .setSaveConsumer(v -> draft.enableFacetRules = v)
                .build());
        category.addEntry(entry.startBooleanToggle(Component.literal("Machine name facets"),
                        draft.enableCatalystFacets)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Powers the * prefix and searching by a machine's own name."))
                .setSaveConsumer(v -> draft.enableCatalystFacets = v)
                .build());
    }

    private static void tree(ConfigBuilder builder, ConfigEntryBuilder entry,
                             ProcessSearchConfig.Draft draft) {
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("Process tree"));
        category.addEntry(entry.startBooleanToggle(Component.literal("Enabled"), draft.enableProcessTree)
                .setDefaultValue(true)
                .setSaveConsumer(v -> draft.enableProcessTree = v)
                .build());
        category.addEntry(entry.startStrField(Component.literal("Key for <  (processed into)"),
                        draft.treeConsumersKey)
                .setDefaultValue("shift+comma")
                .setTooltip(Component.literal("Any Minecraft key name, e.g. shift+comma or f6."))
                .setSaveConsumer(v -> draft.treeConsumersKey = v)
                .build());
        category.addEntry(entry.startStrField(Component.literal("Key for >  (produced by)"),
                        draft.treeProducersKey)
                .setDefaultValue("shift+period")
                .setSaveConsumer(v -> draft.treeProducersKey = v)
                .build());

        category.addEntry(entry.startStrField(Component.literal("Key for routing (A to B)"),
                        draft.treeRouteKey)
                .setDefaultValue("shift+r")
                .setTooltip(Component.literal("Press on what you have, then on what you want."),
                        Component.literal("Pressing twice on the same item cancels."))
                .setSaveConsumer(v -> draft.treeRouteKey = v)
                .build());
        category.addEntry(entry.startIntSlider(Component.literal("Plan: max tiers"),
                        draft.planMaxDepth, 1, 24)
                .setDefaultValue(10)
                .setTooltip(Component.literal("How many tiers of prerequisites a plan resolves."),
                        Component.literal("Deeper on the plan screen raises it without editing this."))
                .setSaveConsumer(v -> draft.planMaxDepth = v)
                .build());
        category.addEntry(entry.startIntSlider(Component.literal("Plan: time budget (ms)"),
                        draft.planMaxMillis, 10, 5000)
                .setDefaultValue(150)
                .setSaveConsumer(v -> draft.planMaxMillis = v)
                .build());
        category.addEntry(entry.startIntField(Component.literal("Plan: search budget"),
                        draft.planMaxNodes)
                .setMin(500).setMax(500000)
                .setDefaultValue(20000)
                .setSaveConsumer(v -> draft.planMaxNodes = v)
                .build());

        category.addEntry(entry.startIntSlider(Component.literal("Route: max steps"),
                        draft.routeMaxSteps, 1, 24)
                .setDefaultValue(8)
                .setTooltip(Component.literal("The most machines a route may pass through."),
                        Component.literal("Raise it if a distant target reports the step limit."))
                .setSaveConsumer(v -> draft.routeMaxSteps = v)
                .build());
        category.addEntry(entry.startIntField(Component.literal("Route: search budget"),
                        draft.routeMaxNodes)
                .setMin(500).setMax(500000)
                .setDefaultValue(20000)
                .setTooltip(Component.literal("Items the search may touch before giving up."))
                .setSaveConsumer(v -> draft.routeMaxNodes = v)
                .build());
        category.addEntry(entry.startIntSlider(Component.literal("Route: time budget (ms)"),
                        draft.routeMaxMillis, 10, 5000)
                .setDefaultValue(100)
                .setTooltip(Component.literal("The bound that keeps a search off the frame."),
                        Component.literal("Steps and nodes are poor proxies for cost; what a node"),
                        Component.literal("costs to expand depends on how tag-heavy the pack is."))
                .setSaveConsumer(v -> draft.routeMaxMillis = v)
                .build());
        category.addEntry(entry.startBooleanToggle(Component.literal("Route: only enabled machines"),
                        draft.routeRespectCategoryFilter)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Off: routes may use any machine, and the Filters"),
                        Component.literal("list only breaks ties between equally short routes."),
                        Component.literal("On: routes use only what you ticked in Filters."))
                .setSaveConsumer(v -> draft.routeRespectCategoryFilter = v)
                .build());

        category.addEntry(entry.startIntSlider(Component.literal("Layers drawn"),
                        draft.treeViewLayers, 1, 9)
                .setDefaultValue(7)
                .setTooltip(Component.literal("Rows including the focus. The walk depth follows from it."),
                        Component.literal("Odd numbers only; even ones round down."))
                .setSaveConsumer(v -> draft.treeViewLayers = v)
                .build());
        category.addEntry(entry.startIntSlider(Component.literal("Machine groups at the focus"),
                        draft.treeVisibleMachines, 1, 64)
                .setDefaultValue(12)
                .setTooltip(Component.literal("Machines that do the same job count as one group"),
                        Component.literal("and are drawn as a block, so this is groups, not boxes."))
                .setSaveConsumer(v -> draft.treeVisibleMachines = v)
                .build());
        category.addEntry(entry.startIntSlider(Component.literal("Items drawn per machine"),
                        draft.treeVisibleItemsPerMachine, 1, 64)
                .setDefaultValue(6)
                .setTooltip(Component.literal("Six fills a 3x2 block. The rest become a +N chip."))
                .setSaveConsumer(v -> draft.treeVisibleItemsPerMachine = v)
                .build());
        category.addEntry(entry.startIntSlider(Component.literal("Nodes drawn per deeper layer"),
                        draft.treeVisiblePerLayer, 2, 200)
                .setDefaultValue(72)
                .setTooltip(Component.literal("Raise this if the layers past the focus feel sparse."))
                .setSaveConsumer(v -> draft.treeVisiblePerLayer = v)
                .build());
        category.addEntry(entry.startIntSlider(Component.literal("Minimum zoom (percent)"),
                        (int) Math.round(draft.treeMinZoom * 100), 3, 100)
                .setDefaultValue(8)
                .setTooltip(Component.literal("How far out you can scroll, and how far Fit will go."),
                        Component.literal("Below 55% the tree goes icon-only and the icons grow"),
                        Component.literal("as you keep going, so there is a reason to go low."))
                .setSaveConsumer(v -> draft.treeMinZoom = v / 100.0)
                .build());
        category.addEntry(entry.startBooleanToggle(Component.literal("Hide identity recipes"),
                        draft.treeHideIdentityRecipes)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Drops steps that hand back an item they also consumed:"),
                        Component.literal("anvil repair, grindstone, enchanting."))
                .setSaveConsumer(v -> draft.treeHideIdentityRecipes = v)
                .build());

        category.addEntry(entry.startIntSlider(Component.literal("Walk: machines per item"),
                        draft.treeMaxProcessesPerItem, 1, 128)
                .setDefaultValue(32)
                .setTooltip(Component.literal("A ceiling on what is available to draw, not what is drawn."))
                .setSaveConsumer(v -> draft.treeMaxProcessesPerItem = v)
                .build());
        category.addEntry(entry.startIntSlider(Component.literal("Walk: items per machine"),
                        draft.treeMaxItemsPerProcess, 1, 128)
                .setDefaultValue(32)
                .setSaveConsumer(v -> draft.treeMaxItemsPerProcess = v)
                .build());
        category.addEntry(entry.startIntField(Component.literal("Walk: total node budget"),
                        draft.treeMaxNodes)
                .setMin(50).setMax(50000)
                .setDefaultValue(6000)
                .setSaveConsumer(v -> draft.treeMaxNodes = v)
                .build());
    }

    private static void itemClasses(ConfigBuilder builder, ConfigEntryBuilder entry,
                                    ProcessSearchConfig.Draft draft) {
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("Item classes"));
        category.addEntry(entry.startStrList(Component.literal("~decorative mod ids"),
                        draft.decorativeModIds)
                .setTooltip(Component.literal("Matched on the item's namespace, so they catch items"),
                        Component.literal("with no recipes at all."))
                .setSaveConsumer(v -> draft.decorativeModIds = v)
                .build());
        category.addEntry(entry.startStrList(Component.literal("~trim mod ids"), draft.trimModIds)
                .setSaveConsumer(v -> draft.trimModIds = v)
                .build());
        category.addEntry(entry.startStrList(Component.literal("~compressed mod ids"),
                        draft.compressedModIds)
                .setSaveConsumer(v -> draft.compressedModIds = v)
                .build());
    }

    private static void indexing(ConfigBuilder builder, ConfigEntryBuilder entry,
                                 ProcessSearchConfig.Draft draft) {
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("Index"));
        category.addEntry(entry.startBooleanToggle(Component.literal("Reuse index across worlds"),
                        draft.reuseIndexAcrossWorlds)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Keep the index when leaving a world and reuse it if"),
                        Component.literal("the recipes are unchanged."))
                .setSaveConsumer(v -> draft.reuseIndexAcrossWorlds = v)
                .build());
        category.addEntry(entry.startIntSlider(Component.literal("Build budget (ms per tick)"),
                        draft.buildTimeBudgetMillisPerTick, 1, 50)
                .setDefaultValue(3)
                .setSaveConsumer(v -> draft.buildTimeBudgetMillisPerTick = v)
                .build());
        category.addEntry(entry.startStrList(Component.literal("Excluded categories"),
                        draft.excludedCategories)
                .setTooltip(Component.literal("Full EMI category ids to keep out of the index"),
                        Component.literal("entirely, e.g. emi_loot:chest_loot."))
                .setSaveConsumer(v -> draft.excludedCategories = v)
                .build());
    }
}
