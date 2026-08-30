package dev.processsearch.emi;

import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.processsearch.ProcessSearch;
import dev.processsearch.index.ProcessIndex;

/**
 * Registered with EMI only to be told when it reloads.
 *
 * <p>This mod registers nothing with EMI -- it adds no categories, recipes or stacks. It implements
 * {@link EmiPlugin} purely to be told when EMI reloads, which is the moment every recipe object the
 * index points at is replaced.
 *
 * <p>The build itself deliberately does not start here: it waits until EMI is actually opened, so a
 * player who never searches by machine never pays for it.
 *
 * <p>Runs on EMI's reload thread, so it only raises a flag.
 */
public class ProcessSearchEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        ProcessIndex.invalidate();
        ProcessSearch.LOGGER.debug("EMI reload: process index dropped, rebuilds on the next EMI open");
    }
}
