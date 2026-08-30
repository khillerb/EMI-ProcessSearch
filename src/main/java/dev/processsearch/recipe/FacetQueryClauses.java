package dev.processsearch.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.emi.emi.search.EmiSearch;
import dev.processsearch.ProcessSearchConfig;

/**
 * Reads the facet half of EMI's search box.
 *
 * <p>Shared by the recipe-page filter and the process tree, so the two cannot drift apart -- a query
 * that hides a recipe page must hide the same recipe in the tree.
 *
 * <p>The grammar is EMI's own, deliberately: tokenised with EMI's own pattern, {@code |} separates
 * alternatives, whitespace is AND within an alternative, and a leading {@code -} negates.
 */
public final class FacetQueryClauses {
    /**
     * EMI's tokenizer, used verbatim so this cannot drift from what the item list does. The local
     * copy is only reached if a future EMI drops the constant.
     */
    private static final Pattern FALLBACK_TOKENS = Pattern.compile(
            "-?[@#$]?(\\/(\\\\.|[^\\\\\\/])+\\/|\\\"(\\.|[^\\\"])+\\\"|[^\\s|]+|\\||\\&)");

    private static Pattern tokens;

    private FacetQueryClauses() {}

    /** One alternative from an OR-separated query. */
    public record Clause(List<String> required, List<String> excluded) {
        public boolean matches(Set<String> facets) {
            for (String token : required) {
                if (!contains(facets, token)) {
                    return false;
                }
            }
            for (String token : excluded) {
                if (contains(facets, token)) {
                    return false;
                }
            }
            return true;
        }

        /** Substring, to match how the item list behaves. */
        private static boolean contains(Set<String> facets, String token) {
            for (String facet : facets) {
                if (facet.contains(token)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** @return true when the facets satisfy any alternative. An empty clause list matches nothing. */
    public static boolean matchesAny(List<Clause> clauses, Set<String> facets) {
        for (Clause clause : clauses) {
            if (clause.matches(facets)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses the query into OR-ed alternatives.
     *
     * <p>If any alternative carries no facet token at all then that alternative matches everything,
     * so the whole OR matches everything and filtering would be wrong. Plain text search, and a
     * query using only the item-class prefix, land here and return nothing -- meaning "do not
     * filter recipes".
     */
    public static List<Clause> parse(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<Clause> clauses = new ArrayList<>(2);
        List<String> required = new ArrayList<>(2);
        List<String> excluded = new ArrayList<>(2);
        int tokensInClause = 0;
        int facetsInClause = 0;

        Matcher matcher = tokens().matcher(query);
        while (matcher.find()) {
            String raw = matcher.group();
            if (raw.equals("&")) {
                // EMI's explicit AND, which is what whitespace already means here.
                continue;
            }
            if (raw.equals("|")) {
                if (tokensInClause > 0) {
                    if (facetsInClause == 0) {
                        return List.of();
                    }
                    clauses.add(new Clause(List.copyOf(required), List.copyOf(excluded)));
                }
                required.clear();
                excluded.clear();
                tokensInClause = 0;
                facetsInClause = 0;
                continue;
            }

            boolean negated = raw.startsWith("-");
            String token = negated ? raw.substring(1) : raw;
            if (token.isEmpty()) {
                continue;
            }
            tokensInClause++;
            if (!ProcessSearchConfig.isRecipePrefix(token.charAt(0))) {
                continue;
            }
            String facet = token.substring(1).replace("\"", "").toLowerCase(Locale.ROOT);
            if (facet.isEmpty()) {
                continue;
            }
            facetsInClause++;
            (negated ? excluded : required).add(facet);
        }

        if (tokensInClause > 0) {
            if (facetsInClause == 0) {
                return List.of();
            }
            clauses.add(new Clause(List.copyOf(required), List.copyOf(excluded)));
        }
        return clauses;
    }

    /**
     * The negated item terms, joined with {@code |}, ready to be compiled as a test for things that
     * should never appear.
     *
     * <p>{@code -a -b} means {@code NOT (a OR b)}, hence the join. This is the half of the search
     * that has to bite hard: retention alone kept a Chipped block whenever anything downstream
     * matched, which meant {@code -~decorative} never actually removed one.
     *
     * <p>Recipe prefixes are left out because {@link #parse} already handles those against recipes.
     * {@code ~} stays in: it classifies the item, and the search hook turns it into a query that
     * tests items correctly.
     *
     * @return null when there is nothing to exclude
     */
    public static String itemExclusions(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<String> terms = new ArrayList<>(2);
        Matcher matcher = tokens().matcher(query);
        while (matcher.find()) {
            String raw = matcher.group();
            if (raw.equals("|") || raw.equals("&") || !raw.startsWith("-")) {
                continue;
            }
            String token = raw.substring(1);
            if (token.isEmpty() || ProcessSearchConfig.isRecipePrefix(token.charAt(0))) {
                continue;
            }
            terms.add(token);
        }
        return terms.isEmpty() ? null : String.join(" | ", terms);
    }

    /**
     * The positive item terms, with the {@code |} structure preserved, for deciding which branches
     * are worth keeping.
     *
     * @return null when what is left would match everything, in which case there is nothing to
     *         retain against
     */
    public static String itemRetention(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<String> alternatives = new ArrayList<>(2);
        StringBuilder current = new StringBuilder();
        int kept = 0;

        Matcher matcher = tokens().matcher(query);
        while (matcher.find()) {
            String raw = matcher.group();
            if (raw.equals("&")) {
                continue;
            }
            if (raw.equals("|")) {
                if (kept == 0) {
                    // An alternative with nothing left matches everything, so the whole OR does.
                    return null;
                }
                alternatives.add(current.toString().trim());
                current.setLength(0);
                kept = 0;
                continue;
            }
            if (raw.startsWith("-")) {
                // Handled as a hard exclusion instead.
                continue;
            }
            if (!raw.isEmpty() && ProcessSearchConfig.isRecipePrefix(raw.charAt(0))) {
                continue;
            }
            current.append(raw).append(' ');
            kept++;
        }
        if (kept == 0) {
            return null;
        }
        alternatives.add(current.toString().trim());
        return String.join(" | ", alternatives);
    }

    /**
     * True when the query asks a question only the process index can answer, so a caller can refuse
     * to pretend it filtered when the index is not built yet.
     */
    public static boolean mentionsItemClass(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        char prefix = ProcessSearchConfig.itemClassPrefix();
        Matcher matcher = tokens().matcher(query);
        while (matcher.find()) {
            String raw = matcher.group();
            String token = raw.startsWith("-") ? raw.substring(1) : raw;
            if (!token.isEmpty() && token.charAt(0) == prefix) {
                return true;
            }
        }
        return false;
    }

    public static Pattern tokens() {
        Pattern pattern = tokens;
        if (pattern == null) {
            try {
                pattern = EmiSearch.TOKENS;
            } catch (RuntimeException | LinkageError e) {
                pattern = null;
            }
            pattern = pattern == null ? FALLBACK_TOKENS : pattern;
            tokens = pattern;
        }
        return pattern;
    }
}
