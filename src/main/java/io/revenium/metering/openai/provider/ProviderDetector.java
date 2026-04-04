package io.revenium.metering.openai.provider;

import java.util.Locale;

/**
 * Detects the AI provider from a base URL string.
 *
 * <p>Stateless utility class — no SDK dependency (decision D-05). Detection is URL
 * substring matching using {@link String#contains}, which handles path segments and
 * case variations (Azure URLs include path segments after the hostname, per Pitfall 3).
 *
 * <p>Detection order matters: {@code .openai.azure.com} is checked before
 * {@code api.openai.com} because Azure URLs contain "openai" and would otherwise
 * match the OpenAI check first.
 *
 * <p>Unknown or null/empty URLs default to {@link Provider#OPENAI} (decision D-03).
 */
public final class ProviderDetector {

    private ProviderDetector() {
        // Utility class — not instantiable
    }

    /**
     * Detects the provider from a base URL string.
     *
     * <p>URL matching is case-insensitive. Null or empty URLs return {@link Provider#OPENAI}.
     *
     * @param baseUrl the base URL string from the OpenAI client configuration (may be null)
     * @return the detected {@link Provider}, defaulting to {@link Provider#OPENAI} for
     *         unknown or null URLs
     */
    public static Provider detect(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return Provider.OPENAI; // D-03: default
        }

        String lower = baseUrl.toLowerCase(Locale.ROOT);

        // Check Azure first — Azure URLs contain "openai" so must precede OpenAI check
        if (lower.contains(".openai.azure.com")) {
            return Provider.AZURE;
        }

        if (lower.contains("api.openai.com")) {
            return Provider.OPENAI;
        }

        if (lower.contains("localhost") || lower.contains("127.0.0.1")) {
            return Provider.OLLAMA;
        }

        return Provider.OPENAI; // D-03: unknown defaults to OPENAI
    }
}
