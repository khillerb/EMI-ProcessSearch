package dev.processsearch.index.rules;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The matcher, over synthetic inputs.
 *
 * <p>Everything here is a pure function of strings, ints and class objects, which is exactly why
 * the rule engine was built to take those rather than an {@code EmiRecipe}: it means the half of
 * the mod most likely to be edited by someone who is not a Java programmer is the half that can be
 * tested without a game.
 */
class FacetRuleTest {

    private static FacetRule one(String json) {
        List<FacetRule> rules = FacetRuleLoader.parse("{\"rules\":[" + json + "]}", "test");
        assertEquals(1, rules.size(), "expected exactly one rule from: " + json);
        return rules.get(0);
    }

    // -- the type hierarchy, which is what lets one rule cover a whole mod family

    interface Marker {}

    interface SubMarker extends Marker {}

    static class Base {}

    static class Middle extends Base implements SubMarker {}

    static class Leaf extends Middle {}

    @Test
    void matchesTheNamedClassItself() {
        FacetRule rule = one("{\"id\":\"a\",\"recipeClass\":\"" + Base.class.getName() + "\",\"tokens\":[\"x\"]}");
        assertTrue(rule.matchesClass(Base.class));
    }

    @Test
    void matchesSubclassesByDefault() {
        FacetRule rule = one("{\"id\":\"a\",\"recipeClass\":\"" + Base.class.getName() + "\",\"tokens\":[\"x\"]}");
        assertTrue(rule.matchesClass(Leaf.class), "a rule on the base must reach every subclass");
    }

    @Test
    void matchesInheritedInterfacesTransitively() {
        FacetRule rule = one("{\"id\":\"a\",\"recipeClass\":\"" + Marker.class.getName() + "\",\"tokens\":[\"x\"]}");
        // Leaf -> Middle implements SubMarker extends Marker: two hops up and one sideways.
        assertTrue(rule.matchesClass(Leaf.class));
    }

    @Test
    void matchSubclassesFalseIsExact() {
        FacetRule rule = one("{\"id\":\"a\",\"recipeClass\":\"" + Base.class.getName()
                + "\",\"matchSubclasses\":false,\"tokens\":[\"x\"]}");
        assertTrue(rule.matchesClass(Base.class));
        assertFalse(rule.matchesClass(Leaf.class));
    }

    @Test
    void classRuleNeverMatchesARecipeWithNoBackingRecipe() {
        FacetRule rule = one("{\"id\":\"a\",\"recipeClass\":\"" + Base.class.getName() + "\",\"tokens\":[\"x\"]}");
        assertFalse(rule.matchesClass(null));
    }

    @Test
    void ruleWithNoClassConditionAcceptsAnything() {
        FacetRule rule = one("{\"id\":\"a\",\"categoryNamespace\":\"create\",\"tokens\":[\"x\"]}");
        assertTrue(rule.matchesClass(null));
        assertTrue(rule.matchesClass(Leaf.class));
    }

    // -- categories

    @Test
    void categoryIdIsExactAndCategoryListsAreOr() {
        FacetRule rule = one("{\"id\":\"a\",\"categoryId\":[\"create:mixing\",\"create:pressing\"],"
                + "\"tokens\":[\"x\"]}");
        assertTrue(rule.matchesCategory("create:mixing", "create"));
        assertTrue(rule.matchesCategory("create:pressing", "create"));
        assertFalse(rule.matchesCategory("create:milling", "create"));
    }

    @Test
    void namespaceMatchesEveryCategoryOfThatMod() {
        FacetRule rule = one("{\"id\":\"a\",\"categoryNamespace\":\"techreborn\",\"tokens\":[\"x\"]}");
        assertTrue(rule.matchesCategory("techreborn:grinder", "techreborn"));
        assertFalse(rule.matchesCategory("create:mixing", "create"));
    }

    @Test
    void conditionsAcrossFieldsAreAnded() {
        FacetRule rule = one("{\"id\":\"a\",\"categoryId\":\"create:mixing\","
                + "\"categoryNamespace\":\"techreborn\",\"tokens\":[\"x\"]}");
        // Nothing can be both, so this matches nothing -- which is the honest reading of an AND.
        assertFalse(rule.matchesCategory("create:mixing", "create"));
        assertFalse(rule.matchesCategory("techreborn:grinder", "techreborn"));
    }

    // -- the per-recipe half

    @Test
    void recipeIdPatternIsASearchNotAFullMatch() {
        FacetRule rule = one("{\"id\":\"a\",\"recipeIdPattern\":\"_dye$\",\"tokens\":[\"dye\"]}");
        assertTrue(rule.needsRecipeId());
        assertTrue(rule.matchesRecipe("somemod:fan_blue_dye", false, false, 1, 1));
        assertFalse(rule.matchesRecipe("somemod:fan_blue_dye_extra", false, false, 1, 1));
    }

    @Test
    void patternRuleSkipsARecipeWhoseIdCouldNotBeRead() {
        FacetRule rule = one("{\"id\":\"a\",\"recipeIdPattern\":\"anything\",\"tokens\":[\"x\"]}");
        assertFalse(rule.matchesRecipe(null, false, false, 1, 1),
                "a null id must fail closed, not match everything");
    }

    @Test
    void fluidConditionsDistinguishTrueFromAbsent() {
        FacetRule requires = one("{\"id\":\"a\",\"requiresFluidInput\":true,\"tokens\":[\"x\"]}");
        assertTrue(requires.matchesRecipe(null, true, false, 1, 1));
        assertFalse(requires.matchesRecipe(null, false, false, 1, 1));

        FacetRule forbids = one("{\"id\":\"b\",\"requiresFluidInput\":false,\"tokens\":[\"x\"]}");
        assertFalse(forbids.matchesRecipe(null, true, false, 1, 1));
        assertTrue(forbids.matchesRecipe(null, false, false, 1, 1));
    }

    @Test
    void countRangesAreInclusive() {
        FacetRule rule = one("{\"id\":\"a\",\"inputCount\":{\"min\":2,\"max\":4},\"tokens\":[\"x\"]}");
        assertFalse(rule.matchesRecipe(null, false, false, 1, 1));
        assertTrue(rule.matchesRecipe(null, false, false, 2, 1));
        assertTrue(rule.matchesRecipe(null, false, false, 4, 1));
        assertFalse(rule.matchesRecipe(null, false, false, 5, 1));
    }

    @Test
    void bareCountMeansExactly() {
        FacetRule rule = one("{\"id\":\"a\",\"outputCount\":1,\"tokens\":[\"x\"]}");
        assertTrue(rule.matchesRecipe(null, false, false, 9, 1));
        assertFalse(rule.matchesRecipe(null, false, false, 9, 2));
    }

    @Test
    void anOpenRangeIsNotAConditionOnTheOtherEnd() {
        FacetRule rule = one("{\"id\":\"a\",\"inputCount\":{\"min\":3},\"tokens\":[\"x\"]}");
        assertFalse(rule.matchesRecipe(null, false, false, 2, 0));
        assertTrue(rule.matchesRecipe(null, false, false, 3, 0));
        assertTrue(rule.matchesRecipe(null, false, false, 9999, 0));
    }
}
