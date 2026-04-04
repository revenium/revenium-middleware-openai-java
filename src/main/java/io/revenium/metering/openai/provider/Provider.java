package io.revenium.metering.openai.provider;

/**
 * Supported AI provider types for metering.
 *
 * <p>Values are intentionally uppercase enum constants. When setting the provider field
 * on a metering event, use {@code provider.name().toLowerCase(java.util.Locale.ROOT)}
 * to match the Revenium API's lowercase string convention (matching Python middleware).
 *
 * <p>Exactly three values per decision D-04: OPENAI, AZURE, OLLAMA.
 */
public enum Provider {

    /** Standard OpenAI API (api.openai.com). */
    OPENAI,

    /** Azure OpenAI Service (*.openai.azure.com). */
    AZURE,

    /** Ollama local model server (localhost / 127.0.0.1). */
    OLLAMA
}
