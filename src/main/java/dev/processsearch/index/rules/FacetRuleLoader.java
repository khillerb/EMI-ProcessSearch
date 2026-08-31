package dev.processsearch.index.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.processsearch.index.Facets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads facet rules from JSON.
 *
 * <p>Two sources, in order: the defaults bundled in the jar, then whatever is in
 * {@code config/processsearch/facet_rules/}. Later files are simply appended, so a pack adds rules
 * by dropping a file in and never has to edit one of ours. A rule sharing an {@code id} with an
 * earlier one replaces it, which is how a pack overrides a bundled default it disagrees with.
 *
 * <p>Nothing here throws. A malformed file, or one bad rule inside a good file, is reported by name
 * and skipped -- the same fail-soft posture the mixins take, and for the same reason: a typo in a
 * pack's rule file must not cost the player their search prefixes.
 *
 * <p>{@link #parse} is deliberately free of Minecraft and Fabric: it takes text and gives back
 * rules, which is what makes the rule engine testable without a game.
 */
public final class FacetRuleLoader {
    /**
     * The same logger name {@code ProcessSearch} uses, reached directly rather than through it.
     * That class implements a Fabric interface, and this one is meant to load in a plain JVM so the
     * parser can be unit tested without a game around it.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("Process Search");

    /**
     * Bundled defaults. Listed rather than discovered, because a directory on the classpath cannot
     * be enumerated portably from inside a jar.
     */
    private static final String[] BUNDLED = {
            "create.json",
            "modern_industrialization.json",
            "vanilla.json",
    };

    private static final String BUNDLED_ROOT = "/data/processsearch/facet_rules/";

    private FacetRuleLoader() {}

    /** The rule set for this session: bundled defaults, then the config directory on top. */
    public static FacetRules load(Path configDir, List<FacetRule> extra) {
        List<FacetRule> rules = new ArrayList<>();
        for (String name : BUNDLED) {
            rules.addAll(readBundled(name));
        }
        rules.addAll(readDirectory(configDir));
        if (extra != null) {
            rules.addAll(extra);
        }
        List<FacetRule> deduped = dedupe(rules);
        if (!deduped.isEmpty()) {
            LOGGER.info("Loaded {} facet rules", deduped.size());
        }
        return new FacetRules(deduped);
    }

    /** Last definition of an id wins, so a config file can replace a bundled rule outright. */
    private static List<FacetRule> dedupe(List<FacetRule> rules) {
        List<FacetRule> out = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            String id = rules.get(i).id();
            boolean superseded = false;
            for (int j = i + 1; j < rules.size(); j++) {
                if (rules.get(j).id().equals(id)) {
                    superseded = true;
                    break;
                }
            }
            if (!superseded) {
                out.add(rules.get(i));
            }
        }
        return out;
    }

    private static List<FacetRule> readBundled(String name) {
        String path = BUNDLED_ROOT + name;
        try (InputStream in = FacetRuleLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                // Not an error worth shouting about: a default file may simply have been dropped.
                LOGGER.debug("No bundled facet rules at {}", path);
                return List.of();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(reader, path);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read bundled facet rules {}: {}", path, e.toString());
            return List.of();
        }
    }

    private static List<FacetRule> readDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> found = Files.list(dir)) {
            found.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not list facet rules in {}: {}", dir, e.toString());
            return List.of();
        }

        List<FacetRule> rules = new ArrayList<>();
        for (Path file : files) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                rules.addAll(parse(reader, file.getFileName().toString()));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Skipping facet rule file {}: {}", file, e.toString());
            }
        }
        return rules;
    }

    /** Convenience for tests and for the legacy-config translation. */
    public static List<FacetRule> parse(String json, String source) {
        return parse(new StringReader(json), source);
    }

    /**
     * Parses one file.
     *
     * @param source a name for log messages -- a file name or a classpath path
     * @return the rules that were valid; never null, and never throws
     */
    public static List<FacetRule> parse(Reader reader, String source) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                LOGGER.warn("Facet rules in {} are not a JSON object; skipping", source);
                return List.of();
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            LOGGER.warn("Facet rules in {} are not valid JSON: {}", source, e.toString());
            return List.of();
        }

        JsonElement rulesElement = root.get("rules");
        if (rulesElement == null || !rulesElement.isJsonArray()) {
            LOGGER.warn("Facet rules in {} have no \"rules\" array; skipping", source);
            return List.of();
        }

        JsonArray array = rulesElement.getAsJsonArray();
        List<FacetRule> rules = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (element == null || !element.isJsonObject()) {
                LOGGER.warn("Facet rule #{} in {} is not an object; skipping", i, source);
                continue;
            }
            FacetRule rule = rule(element.getAsJsonObject(), source, i);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }

    private static FacetRule rule(JsonObject json, String source, int index) {
        String id = string(json, "id");
        String where = (id == null ? "#" + index : "'" + id + "'") + " in " + source;
        if (id == null) {
            id = source + "#" + index;
        }

        List<String> tokens = tokens(json, "tokens");
        List<String> itemClasses = tokens(json, "itemClasses");
        if (tokens.isEmpty() && itemClasses.isEmpty()) {
            LOGGER.warn("Facet rule {} emits no tokens; skipping", where);
            return null;
        }

        Set<String> categoryIds = strings(json, "categoryId");
        Set<String> categoryNamespaces = strings(json, "categoryNamespace");
        String recipeClass = string(json, "recipeClass");
        boolean matchSubclasses = bool(json, "matchSubclasses", true);
        Boolean fluidIn = boxedBool(json, "requiresFluidInput");
        Boolean fluidOut = boxedBool(json, "requiresFluidOutput");

        Pattern pattern = null;
        String patternSource = string(json, "recipeIdPattern");
        if (patternSource != null) {
            try {
                pattern = Pattern.compile(patternSource);
            } catch (PatternSyntaxException e) {
                // The rule may still be meaningful without it, but silently widening what it
                // matches would be worse than dropping it.
                LOGGER.warn("Facet rule {} has an invalid recipeIdPattern '{}': {}",
                        where, patternSource, e.getMessage());
                return null;
            }
        }

        int[] inputs = range(json, "inputCount", where);
        int[] outputs = range(json, "outputCount", where);
        if (inputs == null || outputs == null) {
            return null;
        }

        boolean hasCondition = !categoryIds.isEmpty() || !categoryNamespaces.isEmpty()
                || pattern != null || recipeClass != null
                || fluidIn != null || fluidOut != null
                || inputs[0] > 0 || inputs[1] < Integer.MAX_VALUE
                || outputs[0] > 0 || outputs[1] < Integer.MAX_VALUE;
        if (!hasCondition) {
            // A rule with no condition matches every recipe in the pack, which is never what
            // somebody meant to write.
            LOGGER.warn("Facet rule {} has no conditions, so it would tag every "
                    + "recipe in the pack; skipping", where);
            return null;
        }

        return new FacetRule(id, categoryIds, categoryNamespaces, pattern, recipeClass,
                matchSubclasses, fluidIn, fluidOut, inputs[0], inputs[1], outputs[0], outputs[1],
                tokens, itemClasses);
    }

    /** @return {min, max}, or null if the field is present but malformed */
    private static int[] range(JsonObject json, String key, String where) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return new int[] {0, Integer.MAX_VALUE};
        }
        try {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                // A bare number reads as "exactly this many", which is what it looks like it means.
                int exact = element.getAsInt();
                return new int[] {exact, exact};
            }
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                int min = object.has("min") ? object.get("min").getAsInt() : 0;
                int max = object.has("max") ? object.get("max").getAsInt() : Integer.MAX_VALUE;
                if (min > max) {
                    LOGGER.warn("Facet rule {} has {} min {} above max {}; skipping",
                            where, key, min, max);
                    return null;
                }
                return new int[] {min, max};
            }
        } catch (RuntimeException e) {
            // Fall through to the shared message.
        }
        LOGGER.warn("Facet rule {} has a malformed {}; skipping", where, key);
        return null;
    }

    /** Tokens go through the same sanitiser every other facet does, so they stay typeable. */
    private static List<String> tokens(JsonObject json, String key) {
        List<String> out = new ArrayList<>(2);
        for (String raw : stringList(json, key)) {
            String token = Facets.sanitize(raw);
            if (!token.isEmpty() && !out.contains(token)) {
                out.add(token);
            }
        }
        return List.copyOf(out);
    }

    /** Order does not matter here: these are only ever membership-tested. */
    private static Set<String> strings(JsonObject json, String key) {
        List<String> values = stringList(json, key);
        return values.isEmpty() ? Set.of() : Set.copyOf(values);
    }

    /**
     * Accepts either a single string or an array of them, since both read naturally in a rule file.
     *
     * <p>Returns a list rather than a set so that <em>written</em> order survives -- {@code Set.of}
     * and {@code Set.copyOf} both scramble it deliberately. Tokens are reported back to the player
     * by {@code /processsearch facets}, and a rule author should see them in the order they wrote.
     */
    private static List<String> stringList(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>(4);
        if (element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                addString(out, entry);
            }
        } else {
            addString(out, element);
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static void addString(Set<String> out, JsonElement element) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString().trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
    }

    private static String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        Boolean value = boxedBool(json, key);
        return value == null ? fallback : value;
    }

    private static Boolean boxedBool(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return element.getAsBoolean();
    }
}
