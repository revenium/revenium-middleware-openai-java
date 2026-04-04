package io.revenium.metering.openai;

import com.openai.client.OpenAIClient;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.transport.MeteringClient;

/**
 * Static factory entry point for Revenium OpenAI middleware.
 *
 * <p>Use {@link #wrap(OpenAIClient, ReveniumConfig)} or {@link #wrap(OpenAIClient)} to
 * instrument an {@link OpenAIClient} with automatic AI usage metering. The returned
 * {@link ReveniumInstrumentedClient} exposes all six metered service accessors
 * (sync/async chat completions, embeddings, and responses) as a drop-in replacement
 * for the original client's service accessors.
 *
 * <p>Example — explicit configuration:
 * <pre>{@code
 * ReveniumConfig config = ReveniumConfig.builder()
 *     .apiKey("your-revenium-api-key")
 *     .build();
 *
 * try (ReveniumInstrumentedClient metered = ReveniumOpenAIMiddleware.wrap(openai, config)) {
 *     ChatCompletion result = metered.chatCompletions()
 *         .create(ChatCompletionCreateParams.builder()
 *             .model(ChatModel.GPT_4O)
 *             .addUserMessage("Hello!")
 *             .build());
 * }
 * }</pre>
 *
 * <p>Example — environment variable configuration (REVENIUM_METERING_API_KEY,
 * REVENIUM_METERING_BASE_URL):
 * <pre>{@code
 * try (ReveniumInstrumentedClient metered = ReveniumOpenAIMiddleware.wrap(openai)) {
 *     // same usage as above
 * }
 * }</pre>
 *
 * <p>This is a utility class and cannot be instantiated.
 */
public final class ReveniumOpenAIMiddleware {

    private ReveniumOpenAIMiddleware() {
        throw new UnsupportedOperationException("ReveniumOpenAIMiddleware is a utility class");
    }

    /**
     * Wraps the given {@link OpenAIClient} with Revenium metering using the provided configuration.
     *
     * <p>Creates a new {@link MeteringClient} backed by the given config, then constructs a
     * {@link ReveniumInstrumentedClient} that eagerly builds all six metered service wrappers.
     * The returned container owns the {@link MeteringClient} lifecycle — call
     * {@link ReveniumInstrumentedClient#close()} (or use try-with-resources) to shut it down.
     * The delegate {@code client} is NOT closed by the container.
     *
     * @param client the {@link OpenAIClient} to instrument; must not be null
     * @param config the Revenium configuration (API key, base URL); must not be null
     * @return a {@link ReveniumInstrumentedClient} wrapping the provided client
     */
    public static ReveniumInstrumentedClient wrap(OpenAIClient client, ReveniumConfig config) {
        MeteringClient meteringClient = new MeteringClient(config);
        return new ReveniumInstrumentedClient(client, config, meteringClient);
    }

    /**
     * Wraps the given {@link OpenAIClient} with Revenium metering using environment variable
     * configuration.
     *
     * <p>Reads {@code REVENIUM_METERING_API_KEY} and (optionally) {@code REVENIUM_METERING_BASE_URL}
     * environment variables automatically via {@link ReveniumConfig#builder()}. This is the
     * zero-configuration overload — set the env vars and call this method.
     *
     * @param client the {@link OpenAIClient} to instrument; must not be null
     * @return a {@link ReveniumInstrumentedClient} wrapping the provided client
     * @throws IllegalStateException if {@code REVENIUM_METERING_API_KEY} is not set
     */
    public static ReveniumInstrumentedClient wrap(OpenAIClient client) {
        return wrap(client, ReveniumConfig.builder().build());
    }
}
