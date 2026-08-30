package dev.processsearch.search;

import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.search.Query;
import dev.processsearch.index.ProcessIndex;
import dev.processsearch.index.Role;
import dev.processsearch.index.Scan;

/**
 * One {@code >mixing/heat.heated}-shaped token, as an EMI query.
 *
 * <p>Subclassing {@link Query} rather than mixing into EMI's own query types is what makes the four
 * prefixes behave like native ones: EMI's parser composes whatever queries it is handed, so
 * {@code -} NOT, {@code |} OR, whitespace AND and combination with {@code @mod}, {@code #tooltip}
 * and {@code $tag} all come for free.
 *
 * <p>Two contracts from EMI matter here:
 * <ul>
 *   <li>{@code negated} is applied by the enclosing {@code LogicalAndQuery}, which compares this
 *       result against it. So this must return the plain, un-negated truth and never invert.</li>
 *   <li>{@code matchesUnbaked} defaults to {@code matches}, and this index does not depend on EMI's
 *       bake at all, so there is nothing to override.</li>
 * </ul>
 *
 * <p>Called on EMI's search thread. It only ever reads the published immutable snapshot.
 */
public final class FacetQuery extends Query {
    private final Role role;
    private final String facet;

    public FacetQuery(Role role, String facet) {
        this.role = role;
        this.facet = facet;
    }

    @Override
    public boolean matches(EmiStack stack) {
        ProcessIndex.Snapshot snapshot = ProcessIndex.snapshot();
        if (snapshot == null) {
            // Typed before the index existed. Blocking this thread to build one is not an option, so
            // ask the client thread to build and re-run the search when it lands.
            ProcessIndex.noteColdQuery();
            return false;
        }
        Object key = Scan.key(stack);
        if (key == null) {
            return false;
        }
        // Substring, so >mixing matches the >mixing/heat.heated compound and >ing matches both.
        for (String token : snapshot.tokensFor(role, key)) {
            if (token.contains(facet)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "FacetQuery[" + role + " " + facet + "]";
    }
}
