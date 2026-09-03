package dev.processsearch.emi;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.search.EmiSearch;
import dev.processsearch.ProcessSearch;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * Whether the parts of EMI this mod reaches into are still where it left them.
 *
 * <p>Almost everything here is EMI <em>internals</em>, not published API: the private
 * {@code EmiApi.setPages}, {@code EmiSearch$CompiledQuery.addQuery}, {@code EmiScreenManager}'s
 * key handling, the tooltip helper. The mixin config fails soft on purpose -- an EMI update must
 * not brick a live pack -- and the cost of that choice is that a moved target is completely
 * invisible: the prefixes would simply stop matching, the recipe filter would stop filtering, and
 * nothing would say why.
 *
 * <p>So each touch point is checked, and there are three honest answers rather than two:
 *
 * <ul>
 *   <li>{@code OK} -- the target is present, or the hook has actually been reached.</li>
 *   <li>{@code MISSING} -- the class, method or field is gone. This will not work.</li>
 *   <li>{@code UNPROVEN} -- it exists, but the only way to know the hook fires is for it to fire,
 *       and it has not yet. Some cannot be probed at all: calling {@code keyPressed} to see would
 *       run EMI's real key handling, and calling {@code setPages} would open a screen.</li>
 * </ul>
 *
 * <p>Checks are reflective on purpose. The mod compiles against these types, so a missing one would
 * eventually surface as a {@code LinkageError} at the call site -- but by then the feature has
 * already silently failed for the player. Looking the members up by exact signature catches the
 * same drift before anything is used, which is what makes {@code /processsearch compat} worth
 * running.
 */
public final class EmiCompat {

    public enum Health {
        /** Present, or actually exercised. */
        OK,
        /** Gone. Whatever depends on it will not work. */
        MISSING,
        /** Present but unexercised, or not safely probeable. */
        UNPROVEN
    }

    /**
     * @param id     stable identifier, also what a mixin passes to {@link #reached}
     * @param what   the feature that stops working when this breaks
     * @param target the EMI member being depended on
     */
    public record Check(String id, String what, String target, Health health, String detail) {}

    // -- ids the hooks report themselves under

    public static final String HOOK_SEARCH = "hook.search";
    public static final String HOOK_PAGES = "hook.pages";
    public static final String HOOK_KEYS = "hook.keys";
    public static final String HOOK_RECIPE_NOTICE = "hook.recipe_notice";

    /** Written from mixins on several threads, read by the command on another. */
    private static final Set<String> REACHED = ConcurrentHashMap.newKeySet();

    private static boolean reported;

    private EmiCompat() {}

    /** Called from a hook. Reaching it at all is the proof it applied. */
    public static void reached(String id) {
        REACHED.add(id);
    }

    public static boolean hasReached(String id) {
        return REACHED.contains(id);
    }

    /**
     * One warning per session if anything is outright gone.
     *
     * <p>Called once the index starts building, which is the first moment EMI is known to be up.
     * A player who never opens EMI never sees it, and never needed it.
     */
    public static void reportOnce() {
        if (reported) {
            return;
        }
        reported = true;
        List<Check> broken = checks().stream().filter(c -> c.health() == Health.MISSING).toList();
        if (broken.isEmpty()) {
            ProcessSearch.LOGGER.info("EMI compatibility: all {} checked targets present",
                    checks().size());
            return;
        }
        ProcessSearch.LOGGER.error("EMI compatibility: {} of {} targets are MISSING. This build was "
                        + "written against EMI 1.1.24; run /processsearch compat for the list.",
                broken.size(), checks().size());
        for (Check check : broken) {
            ProcessSearch.LOGGER.error("  {} -- {} is gone, so {} will not work",
                    check.id(), check.target(), check.what());
        }
    }

    /** The full report, probes run fresh each call. Cheap, and only ever on demand. */
    public static List<Check> checks() {
        List<Check> checks = new ArrayList<>();

        // -- the search prefixes
        checks.add(hook(HOOK_SEARCH, "the four search prefixes",
                "EmiSearch$CompiledQuery.addQuery(String, boolean, List, Function, Function)",
                () -> EmiSearch.CompiledQuery.class.getDeclaredMethod("addQuery", String.class,
                        boolean.class, List.class, Function.class, Function.class)));
        checks.add(probe("search.query_ctor", "the process tree's item filtering",
                "new EmiSearch$CompiledQuery(String)",
                () -> EmiSearch.CompiledQuery.class.getConstructor(String.class)));
        checks.add(probe("search.query_test", "the process tree's item filtering",
                "EmiSearch$CompiledQuery.test(EmiStack)",
                () -> EmiSearch.CompiledQuery.class.getMethod("test", EmiStack.class)));
        checks.add(probe("search.tokens", "reading the search box the same way EMI does",
                "EmiSearch.TOKENS",
                // Looked up, deliberately not read. Reading it would force EmiSearch's static
                // initialiser, and a compatibility probe has no business initialising the thing it
                // is only supposed to be asking about.
                () -> {
                    java.lang.reflect.Field field = EmiSearch.class.getField("TOKENS");
                    return java.util.regex.Pattern.class.equals(field.getType()) ? field : null;
                }));
        checks.add(probe("search.update", "re-running a search typed before the index existed",
                "EmiSearch.update()",
                () -> EmiSearch.class.getMethod("update")));

        // -- recipe page filtering
        checks.add(hook(HOOK_PAGES, "filtering recipe pages by the search box",
                "EmiApi.setPages(Map, EmiIngredient)",
                () -> EmiApi.class.getDeclaredMethod("setPages", java.util.Map.class,
                        EmiIngredient.class)));
        checks.add(hook(HOOK_RECIPE_NOTICE, "the \"filtered: N of M\" notice",
                "RecipeScreen.render(GuiGraphics, int, int, float)",
                () -> RecipeScreen.class.getMethod("render", GuiGraphics.class, int.class,
                        int.class, float.class)));

        // -- the process tree and routing
        checks.add(hook(HOOK_KEYS, "the process tree and route hotkeys",
                "EmiScreenManager.keyPressed(int, int, int)",
                () -> EmiScreenManager.class.getMethod("keyPressed", int.class, int.class,
                        int.class)));
        checks.add(probe("api.hovered", "knowing which item you are pointing at",
                "EmiApi.getHoveredStack(boolean)",
                () -> EmiApi.class.getMethod("getHoveredStack", boolean.class)));
        checks.add(probe("api.by_input", "walking and routing forward",
                "EmiRecipeManager.getRecipesByInput(EmiStack)",
                () -> EmiRecipeManager.class.getMethod("getRecipesByInput", EmiStack.class)));
        checks.add(probe("api.by_output", "walking and routing backward",
                "EmiRecipeManager.getRecipesByOutput(EmiStack)",
                () -> EmiRecipeManager.class.getMethod("getRecipesByOutput", EmiStack.class)));

        // -- indexing
        checks.add(probe("reload.is_loaded", "waiting for EMI before indexing",
                "EmiReloadManager.isLoaded()",
                () -> EmiReloadManager.class.getMethod("isLoaded")));
        checks.add(probe("api.search_text", "reading the current query",
                "EmiApi.getSearchText()",
                () -> EmiApi.class.getMethod("getSearchText")));

        // -- drawing
        checks.add(probe("render.tooltip", "tooltips on the tree and route screens",
                "EmiRenderHelper.drawTooltip(Screen, EmiDrawContext, List, int, int)",
                () -> EmiRenderHelper.class.getMethod("drawTooltip", Screen.class,
                        EmiDrawContext.class, List.class, int.class, int.class)));
        checks.add(probe("render.context", "tooltips on the tree and route screens",
                "EmiDrawContext.wrap(GuiGraphics)",
                () -> EmiDrawContext.class.getMethod("wrap", GuiGraphics.class)));
        checks.add(probe("render.bounds", "placing the \"filtered\" notice",
                "RecipeScreen.getBounds()",
                () -> RecipeScreen.class.getMethod("getBounds")));

        return List.copyOf(checks);
    }

    /** A target that can only be confirmed by being reached, plus a probe of the target itself. */
    private static Check hook(String id, String what, String target, Lookup lookup) {
        Check probed = probe(id, what, target, lookup);
        if (probed.health() == Health.MISSING) {
            return probed;
        }
        if (REACHED.contains(id)) {
            return new Check(id, what, target, Health.OK, "hook has fired");
        }
        // The distinction that matters: the target is fine, so the hook probably applied -- but
        // nothing has exercised it yet, and saying "OK" would be a guess.
        return new Check(id, what, target, Health.UNPROVEN, "target present, hook not yet exercised");
    }

    private static Check probe(String id, String what, String target, Lookup lookup) {
        try {
            Object found = lookup.get();
            return found == null
                    ? new Check(id, what, target, Health.MISSING, "resolved to null")
                    : new Check(id, what, target, Health.OK, "present");
        } catch (Throwable t) {
            // Throwable on purpose: a missing member is NoSuchMethodException, but a missing or
            // renamed class is NoClassDefFoundError, and both mean the same thing here.
            return new Check(id, what, target, Health.MISSING, t.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface Lookup {
        Object get() throws Throwable;
    }
}
