package dev.processsearch.index.rules;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bucketing and the memo -- the part that has to be right for the index build to stay inside
 * its few milliseconds a tick, and the part where a wrong answer is a facet that silently never
 * appears.
 */
class FacetRulesTest {

    static class Backing {}

    static class OtherBacking {}

    private static FacetRules rules(String... ruleJson) {
        return new FacetRules(FacetRuleLoader.parse(
                "{\"rules\":[" + String.join(",", ruleJson) + "]}", "test"));
    }

    @Test
    void offersOnlyRulesThatCanMatchTheCategory() {
        FacetRules rules = rules(
                "{\"id\":\"create\",\"categoryNamespace\":\"create\",\"tokens\":[\"a\"]}",
                "{\"id\":\"mi\",\"categoryNamespace\":\"mi\",\"tokens\":[\"b\"]}");

        List<FacetRule> found = rules.candidates("create:mixing", Backing.class);
        assertEquals(1, found.size());
        assertEquals("create", found.get(0).id());
    }

    @Test
    void rulesWithNoCategoryConditionReachEveryCategory() {
        FacetRules rules = rules(
                "{\"id\":\"anywhere\",\"requiresFluidInput\":true,\"tokens\":[\"a\"]}");
        assertEquals(1, rules.candidates("create:mixing", Backing.class).size());
        assertEquals(1, rules.candidates("techreborn:grinder", OtherBacking.class).size());
    }

    @Test
    void filtersOnTheBackingClassBeforeTheCallerSeesTheRule() {
        FacetRules rules = rules("{\"id\":\"typed\",\"recipeClass\":\""
                + Backing.class.getName() + "\",\"tokens\":[\"a\"]}");

        assertEquals(1, rules.candidates("m:c", Backing.class).size());
        assertTrue(rules.candidates("m:c", OtherBacking.class).isEmpty());
        assertTrue(rules.candidates("m:c", null).isEmpty());
    }

    @Test
    void aRuleKeyedByBothIdAndNamespaceIsOfferedOnce() {
        // Filed under two buckets, so without de-duplication it would emit its tokens twice.
        FacetRules rules = rules("{\"id\":\"both\",\"categoryId\":\"create:mixing\","
                + "\"categoryNamespace\":\"create\",\"tokens\":[\"a\"]}");
        assertEquals(1, rules.candidates("create:mixing", Backing.class).size());
    }

    @Test
    void aCategoryAndClassPairIsResolvedOnce() {
        FacetRules rules = rules("{\"id\":\"a\",\"categoryNamespace\":\"create\",\"tokens\":[\"x\"]}");
        List<FacetRule> first = rules.candidates("create:mixing", Backing.class);
        List<FacetRule> second = rules.candidates("create:mixing", Backing.class);
        assertSame(first, second, "the memo should hand back the same list, not rebuild it");
    }

    @Test
    void theNoBackingCaseIsKeyedSeparatelyFromARealClass() {
        FacetRules rules = rules(
                "{\"id\":\"typed\",\"recipeClass\":\"" + Backing.class.getName() + "\",\"tokens\":[\"a\"]}",
                "{\"id\":\"untyped\",\"categoryNamespace\":\"m\",\"tokens\":[\"b\"]}");

        assertEquals(2, rules.candidates("m:c", Backing.class).size());
        // Same category, no backing recipe: the typed rule must drop out and stay dropped.
        List<FacetRule> none = rules.candidates("m:c", null);
        assertEquals(1, none.size());
        assertEquals("untyped", none.get(0).id());
    }

    @Test
    void anEmptySetShortCircuits() {
        assertTrue(FacetRules.EMPTY.isEmpty());
        assertTrue(FacetRules.EMPTY.candidates("m:c", Backing.class).isEmpty());
    }

    @Test
    void namespaceIsParsedFromTheCategoryIdWithoutAColon() {
        FacetRules rules = rules("{\"id\":\"a\",\"categoryNamespace\":\"bare\",\"tokens\":[\"x\"]}");
        assertEquals(1, rules.candidates("bare", Backing.class).size());
    }
}
