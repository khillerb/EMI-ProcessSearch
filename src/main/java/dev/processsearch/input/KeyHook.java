package dev.processsearch.input;

/**
 * Records that the process-tree key hook is live.
 *
 * <p>The mixin config fails soft, so an EMI update that moved {@code EmiScreenManager.keyPressed}
 * would make the tree hotkeys quietly stop existing. Unlike the search hook, this one cannot be
 * probed: calling {@code keyPressed} to see whether the injection fires would run EMI's real key
 * handling, and holding control at that moment would open the full recipe list as a side effect.
 *
 * <p>So it reports what it can honestly know -- the hook has been reached, or it has not been
 * reached yet -- and {@code /processsearch stats} says which.
 */
public final class KeyHook {
    private static volatile boolean seen;

    private KeyHook() {}

    /** Called from the mixin, so reaching it at all is the proof. */
    public static void markInstalled() {
        seen = true;
    }

    public static boolean hasFired() {
        return seen;
    }
}
