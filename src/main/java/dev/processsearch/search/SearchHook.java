package dev.processsearch.search;

import dev.emi.emi.search.EmiSearch;
import dev.processsearch.ProcessSearch;
import dev.processsearch.ProcessSearchConfig;

/**
 * Says out loud whether the search mixin actually applied.
 *
 * <p>The mixin config fails soft on purpose -- an EMI update must not brick a live pack -- and the
 * cost of that is a missed hook being completely invisible: the prefixes would just quietly stop
 * existing. So the hook reports itself, and {@code /processsearch stats} prints it.
 *
 * <p>The probe compiles one throwaway query. If the hook is in place it is cancelled before EMI
 * constructs anything, so the probe costs an allocation-free no-op; if it is not, EMI builds one
 * name query for a string nothing matches, which is equally harmless.
 */
public final class SearchHook {
    private static final String PROBE = "processsearch_hook_probe";

    private static volatile boolean installed;
    private static boolean probed;

    private SearchHook() {}

    /** Called from the mixin, so reaching it at all is the proof. */
    public static void markInstalled() {
        installed = true;
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static void probeOnce() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            new EmiSearch.CompiledQuery(ProcessSearchConfig.madeByPrefix() + PROBE);
        } catch (RuntimeException | LinkageError e) {
            ProcessSearch.LOGGER.warn("Could not probe the EMI search hook: {}", e.toString());
            return;
        }
        if (installed) {
            ProcessSearch.LOGGER.info("EMI search hook installed: prefixes {} {} {} {}",
                    ProcessSearchConfig.madeByPrefix(), ProcessSearchConfig.usedInPrefix(),
                    ProcessSearchConfig.machineForPrefix(), ProcessSearchConfig.itemClassPrefix());
        } else {
            ProcessSearch.LOGGER.error(
                    "EMI search hook did NOT apply -- the search prefixes will do nothing. This build "
                            + "targets EMI 1.1.24; a newer EMI may have moved "
                            + "EmiSearch$CompiledQuery.addQuery.");
        }
    }

    /** Lets a rebuild re-run the probe, so the command can be used to re-check after a config edit. */
    public static void resetProbe() {
        probed = false;
    }
}
