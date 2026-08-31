package dev.processsearch.index.rules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing and validation.
 *
 * <p>The loader's contract is that it never throws: one typo in a pack's rule file has to cost that
 * one rule and nothing else. Most of what is checked here is that bad input is dropped rather than
 * propagated, because the failure that matters is a malformed file quietly taking the search
 * prefixes down with it.
 */
class FacetRuleLoaderTest {

    private static List<FacetRule> parse(String json) {
        return FacetRuleLoader.parse(json, "test");
    }

    @Test
    void readsAWellFormedRule() {
        List<FacetRule> rules = parse("""
                {"rules":[{"id":"a","categoryNamespace":"create","tokens":["heated","fast"]}]}""");
        assertEquals(1, rules.size());
        assertEquals("a", rules.get(0).id());
        assertEquals(List.of("heated", "fast"), rules.get(0).tokens());
    }

    @Test
    void acceptsAStringWhereAListIsAllowed() {
        List<FacetRule> rules = parse("""
                {"rules":[{"id":"a","categoryId":"create:mixing","tokens":"solo"}]}""");
        assertEquals(1, rules.size());
        assertTrue(rules.get(0).matchesCategory("create:mixing", "create"));
        assertEquals(List.of("solo"), rules.get(0).tokens());
    }

    @Test
    void tokensGoThroughTheSharedSanitiser() {
        // Whitespace and | would make a token impossible to type, since EMI splits on both.
        List<FacetRule> rules = parse("""
                {"rules":[{"id":"a","categoryNamespace":"m","tokens":["Heated Mixing"]}]}""");
        assertEquals(List.of("heated_mixing"), rules.get(0).tokens());
    }

    @Test
    void duplicateTokensCollapse() {
        List<FacetRule> rules = parse("""
                {"rules":[{"id":"a","categoryNamespace":"m","tokens":["a b","a  b","c"]}]}""");
        assertEquals(List.of("a_b", "c"), rules.get(0).tokens());
    }

    @Test
    void itemClassesAreCarriedSeparatelyFromTokens() {
        List<FacetRule> rules = parse("""
                {"rules":[{"id":"a","categoryId":"m:c","tokens":["dye"],"itemClasses":["dye"]}]}""");
        assertEquals(List.of("dye"), rules.get(0).tokens());
        assertEquals(List.of("dye"), rules.get(0).itemClasses());
    }

    @Test
    void aRuleEmittingOnlyItemClassesIsValid() {
        List<FacetRule> rules = parse("""
                {"rules":[{"id":"a","categoryNamespace":"chipped","itemClasses":["decorative"]}]}""");
        assertEquals(1, rules.size());
        assertTrue(rules.get(0).tokens().isEmpty());
    }

    // -- everything below must be dropped rather than thrown

    @Test
    void rejectsARuleWithNoConditions() {
        // It would tag every recipe in the pack, which is never what anyone meant to write.
        assertTrue(parse("""
                {"rules":[{"id":"a","tokens":["x"]}]}""").isEmpty());
    }

    @Test
    void rejectsARuleThatEmitsNothing() {
        assertTrue(parse("""
                {"rules":[{"id":"a","categoryNamespace":"create"}]}""").isEmpty());
    }

    @Test
    void rejectsAnInvalidRegexRatherThanWideningTheRule() {
        assertTrue(parse("""
                {"rules":[{"id":"a","recipeIdPattern":"[unclosed","tokens":["x"]}]}""").isEmpty());
    }

    @Test
    void rejectsAnInvertedCountRange() {
        assertTrue(parse("""
                {"rules":[{"id":"a","inputCount":{"min":5,"max":2},"tokens":["x"]}]}""").isEmpty());
    }

    @Test
    void rejectsAMalformedCountRange() {
        assertTrue(parse("""
                {"rules":[{"id":"a","inputCount":"lots","tokens":["x"]}]}""").isEmpty());
    }

    @Test
    void oneBadRuleDoesNotTakeTheFileWithIt() {
        List<FacetRule> rules = parse("""
                {"rules":[
                  {"id":"bad","tokens":["x"]},
                  {"id":"good","categoryNamespace":"create","tokens":["y"]}
                ]}""");
        assertEquals(1, rules.size());
        assertEquals("good", rules.get(0).id());
    }

    @Test
    void survivesJsonThatIsNotJson() {
        assertTrue(parse("this is not json at all {{{").isEmpty());
    }

    @Test
    void survivesAMissingRulesArray() {
        assertTrue(parse("{\"notrules\":[]}").isEmpty());
        assertTrue(parse("{\"rules\":\"nope\"}").isEmpty());
    }

    @Test
    void survivesANonObjectRoot() {
        assertTrue(parse("[1,2,3]").isEmpty());
    }

    @Test
    void ignoresUnknownKeysSoFilesCanCarryComments() {
        List<FacetRule> rules = parse("""
                {"_comment":["hello"],"rules":[
                  {"id":"a","categoryNamespace":"m","tokens":["x"],"futureField":42}
                ]}""");
        assertEquals(1, rules.size());
    }

    // -- loading from disk, and the override rule

    @Test
    void laterFilesOverrideEarlierRulesOfTheSameId(@TempDir Path dir) throws IOException {
        write(dir, "a_first.json", """
                {"rules":[{"id":"shared","categoryNamespace":"m","tokens":["original"]}]}""");
        write(dir, "b_second.json", """
                {"rules":[{"id":"shared","categoryNamespace":"m","tokens":["replacement"]}]}""");

        FacetRules rules = FacetRuleLoader.load(dir, List.of());
        List<FacetRule> shared = rules.all().stream().filter(r -> r.id().equals("shared")).toList();
        assertEquals(1, shared.size(), "the id must appear once, not twice");
        assertEquals(List.of("replacement"), shared.get(0).tokens());
    }

    @Test
    void aBrokenFileDoesNotStopTheGoodOneBesideIt(@TempDir Path dir) throws IOException {
        write(dir, "broken.json", "{{{ not json");
        write(dir, "fine.json", """
                {"rules":[{"id":"fine","categoryNamespace":"m","tokens":["x"]}]}""");

        FacetRules rules = FacetRuleLoader.load(dir, List.of());
        assertTrue(rules.all().stream().anyMatch(r -> r.id().equals("fine")));
    }

    @Test
    void aMissingDirectoryIsNotAnError() {
        FacetRules rules = FacetRuleLoader.load(Path.of("does", "not", "exist"), List.of());
        assertFalse(rules.isEmpty(), "the bundled defaults should still be there");
    }

    @Test
    void nonJsonFilesAreIgnored(@TempDir Path dir) throws IOException {
        write(dir, "notes.txt", "this is not a rule file");
        write(dir, "rules.json", """
                {"rules":[{"id":"only","categoryNamespace":"m","tokens":["x"]}]}""");

        FacetRules rules = FacetRuleLoader.load(dir, List.of());
        assertTrue(rules.all().stream().anyMatch(r -> r.id().equals("only")));
    }

    @Test
    void bundledDefaultsParseCleanly() {
        // Guards the shipped JSON: a typo in one of those is a silent loss of coverage.
        FacetRules rules = FacetRuleLoader.load(null, List.of());
        assertFalse(rules.isEmpty());
        assertTrue(rules.all().stream().anyMatch(r -> r.id().equals("create_processing")));
        assertTrue(rules.all().stream().anyMatch(r -> r.id().equals("mi_machine")));
        assertTrue(rules.all().stream().anyMatch(r -> r.id().equals("vanilla_smelting")));
    }

    private static void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }
}
