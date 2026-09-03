package dev.processsearch.emi;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the compatibility probes against the vendored EMI jar at build time.
 *
 * <p>This is the check earning its keep twice. In game it tells a player why a feature stopped
 * working; here it fails the build the moment {@code libs/emi-*.jar} is bumped to a version that
 * moved something, which is the cheapest possible moment to find out.
 *
 * <p>It also guards the probes themselves. A mistyped signature would report {@code MISSING} for a
 * member that is perfectly fine -- a compatibility check that cries wolf is worse than none -- and
 * nothing but running it against the real jar would catch that.
 */
class EmiCompatTest {

    @Test
    void everyTargetIsPresentInTheVendoredJar() {
        List<EmiCompat.Check> missing = EmiCompat.checks().stream()
                .filter(c -> c.health() == EmiCompat.Health.MISSING)
                .toList();

        assertTrue(missing.isEmpty(), () -> "EMI targets not found in the vendored jar. Either the "
                + "jar moved on and the mod needs updating, or a probe signature is wrong:\n"
                + missing.stream()
                .map(c -> "  " + c.id() + "  " + c.target() + "  (" + c.detail() + ")")
                .reduce("", (a, b) -> a + b + "\n"));
    }

    @Test
    void theReportCoversEveryHook() {
        List<String> ids = EmiCompat.checks().stream().map(EmiCompat.Check::id).toList();
        // The four mixins are the touch points that fail silently, so none may be left out.
        assertTrue(ids.contains(EmiCompat.HOOK_SEARCH));
        assertTrue(ids.contains(EmiCompat.HOOK_PAGES));
        assertTrue(ids.contains(EmiCompat.HOOK_KEYS));
        assertTrue(ids.contains(EmiCompat.HOOK_RECIPE_NOTICE));
    }

    @Test
    void anUnexercisedHookIsUnprovenRatherThanOk() {
        // Nothing has run in a unit test, so every hook must report the honest middle answer.
        // Claiming OK here is exactly the false confidence this class exists to avoid.
        List<EmiCompat.Check> hooks = EmiCompat.checks().stream()
                .filter(c -> c.id().startsWith("hook."))
                .toList();

        assertFalse(hooks.isEmpty());
        for (EmiCompat.Check hook : hooks) {
            assertTrue(hook.health() == EmiCompat.Health.UNPROVEN
                            || hook.health() == EmiCompat.Health.MISSING,
                    hook.id() + " claimed " + hook.health() + " without ever having fired");
        }
    }

    @Test
    void everyCheckSaysWhatBreaksWhenItGoes() {
        for (EmiCompat.Check check : EmiCompat.checks()) {
            assertFalse(check.what().isBlank(), check.id() + " has no description");
            assertFalse(check.target().isBlank(), check.id() + " names no target");
        }
    }
}
