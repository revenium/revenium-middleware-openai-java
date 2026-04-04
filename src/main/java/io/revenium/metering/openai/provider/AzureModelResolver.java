package io.revenium.metering.openai.provider;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Heuristic-based resolver that maps Azure deployment names to canonical OpenAI model names.
 *
 * <p>Azure OpenAI deployments use customer-defined names (e.g., {@code "my-gpt4o-deployment"})
 * rather than canonical model names (e.g., {@code "gpt-4o"}). This class applies ordered
 * regex heuristics to resolve deployment names to their canonical equivalents.
 *
 * <p>If no heuristic matches, the deployment name is returned unchanged (safe passthrough).
 * This ensures unknown deployment names degrade gracefully rather than causing errors.
 *
 * <p>Heuristic-only strategy (no async Azure Management API fallback) is intentional for v1 —
 * covers the common cases without external dependencies or blocking calls (decision D-06).
 *
 * <p>Stateless utility class — patterns are compiled once at class load time.
 * Thread-safe with no mutable state.
 */
public final class AzureModelResolver {

    private AzureModelResolver() {
        // Utility class — not instantiable
    }

    /**
     * Ordered list of regex heuristics mapping deployment name patterns to canonical model names.
     *
     * <p>Order matters: more specific patterns (gpt-4-turbo, gpt-4o) must precede less specific
     * ones (gpt-4) to avoid false positive matches. GPT-4o is checked before GPT-4 Turbo,
     * which is checked before GPT-4.
     */
    private static final List<Map.Entry<Pattern, String>> HEURISTICS = Arrays.asList(
            // GPT-4o and GPT-4o Mini — checked before gpt-4-turbo and gpt-4 to avoid false matches
            entry(Pattern.compile("(?i)gpt[-_]?4[-_]?o(?:[-_]mini)?"), "gpt-4o"),
            // GPT-4 Turbo — checked before plain gpt-4
            entry(Pattern.compile("(?i)gpt[-_]?4[-_]?turbo"), "gpt-4-turbo"),
            // GPT-4 (non-o, non-turbo) — negative lookahead (?!o) prevents matching "gpt4o"
            entry(Pattern.compile("(?i)gpt[-_]?4(?!o)"), "gpt-4"),
            // GPT-3.5 Turbo
            entry(Pattern.compile("(?i)gpt[-_]?3\\.?5[-_]?turbo"), "gpt-3.5-turbo"),
            // text-embedding-3-large — checked before small and ada
            entry(Pattern.compile("(?i)text[-_]?embed(?:ding)?[-_]?3[-_]?large"), "text-embedding-3-large"),
            // text-embedding-3-small
            entry(Pattern.compile("(?i)text[-_]?embed(?:ding)?[-_]?3[-_]?small"), "text-embedding-3-small"),
            // text-embedding-ada-002 (matches "ada" in embedding context)
            entry(Pattern.compile("(?i)text[-_]?embed(?:ding)?[-_]?ada"), "text-embedding-ada-002"),
            // ada shorthand (e.g., "ada-embedding-v1") — fallback after full text-embedding-ada
            entry(Pattern.compile("(?i)ada[-_]embed"), "text-embedding-ada-002")
    );

    /**
     * Resolves an Azure deployment name to its canonical OpenAI model name.
     *
     * <p>Iterates heuristics in order and returns the canonical name on the first match.
     * Returns the input unchanged if no heuristic matches (safe passthrough).
     * Null and empty inputs are returned as-is without error.
     *
     * @param deploymentName the Azure deployment name (may be null or empty)
     * @return canonical model name, or {@code deploymentName} unchanged if no match
     */
    public static String resolve(String deploymentName) {
        if (deploymentName == null || deploymentName.isEmpty()) {
            return deploymentName; // safe passthrough
        }

        for (Map.Entry<Pattern, String> entry : HEURISTICS) {
            if (entry.getKey().matcher(deploymentName).find()) {
                return entry.getValue();
            }
        }

        return deploymentName; // no match — safe passthrough
    }

    private static Map.Entry<Pattern, String> entry(Pattern pattern, String canonical) {
        return new AbstractMap.SimpleImmutableEntry<>(pattern, canonical);
    }
}
