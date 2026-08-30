package dev.processsearch.mixin;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import dev.emi.emi.search.Query;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.Role;
import dev.processsearch.search.FacetQuery;
import dev.processsearch.search.SearchHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the four process-search prefixes to EMI's search grammar.
 *
 * <pre>
 *   &gt;process[/property]   what MAKES this item
 *   &lt;process[/property]   what CONSUMES this item
 *   *process               which MACHINE runs this process
 *   ~class                 what KIND of item this is
 * </pre>
 *
 * <p>JEI keeps its prefixes in an extensible {@code char -> PrefixInfo} map. EMI keeps them in
 * {@code QueryType}, which is a Java enum -- there is no way to add a constant at runtime. So the
 * hook goes one level down instead, at the private helper EMI calls once per parsed token.
 *
 * <p>{@code addQuery} receives the token with EMI's own prefix already stripped. Our tokens are
 * typed {@code DEFAULT}, whose prefix is the empty string, so the character we care about is still
 * on the front. Claiming those and cancelling means EMI never builds a name query for them, which is
 * what makes the prefixes require-prefix by construction: without one, a token never reaches us.
 *
 * <p>Everything else about the grammar is inherited for free, because this runs inside EMI's own
 * parse loop: {@code -} NOT (already applied to the {@code negated} flag by the time we are called),
 * {@code |} OR, whitespace AND, explicit {@code &}, and composition with {@code @mod},
 * {@code #tooltip} and {@code $tag}.
 */
@Mixin(targets = "dev.emi.emi.search.EmiSearch$CompiledQuery", remap = false)
public class CompiledQueryMixin {

    @Inject(method = "addQuery(Ljava/lang/String;ZLjava/util/List;Ljava/util/function/Function;"
            + "Ljava/util/function/Function;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void processsearch$claimPrefixedToken(String text, boolean negated,
                                                         List<Query> queries,
                                                         Function<String, Query> queryConstructor,
                                                         Function<String, Query> regexConstructor,
                                                         CallbackInfo ci) {
        // Reaching this at all is the proof the mixin applied; /processsearch stats reports it,
        // because the mixin config fails soft and a missed hook would otherwise be invisible.
        SearchHook.markInstalled();

        if (text == null || text.length() < 2) {
            return;
        }
        Role role = ProcessSearchConfig.roleFor(text.charAt(0));
        if (role == null) {
            return;
        }
        String facet = text.substring(1).toLowerCase(Locale.ROOT);
        if (facet.isEmpty()) {
            return;
        }

        Query query = new FacetQuery(role, facet);
        // EMI's LogicalAndQuery compares each child's result against its own negated flag, so this
        // is where negation is recorded and FacetQuery itself must never invert.
        query.negated = negated;
        queries.add(query);
        ci.cancel();
    }
}
