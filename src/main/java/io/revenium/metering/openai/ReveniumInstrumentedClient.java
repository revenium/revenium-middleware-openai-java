package io.revenium.metering.openai;

import com.openai.client.OpenAIClient;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.transport.MeteringClient;
import io.revenium.metering.openai.wrapper.MeteringChatCompletionService;
import io.revenium.metering.openai.wrapper.MeteringChatCompletionServiceAsync;
import io.revenium.metering.openai.wrapper.MeteringEmbeddingService;
import io.revenium.metering.openai.wrapper.MeteringEmbeddingServiceAsync;
import io.revenium.metering.openai.wrapper.MeteringResponseService;
import io.revenium.metering.openai.wrapper.MeteringResponseServiceAsync;

/**
 * Container holding all six metered OpenAI service wrappers, returned by
 * {@link ReveniumOpenAIMiddleware#wrap}.
 *
 * <p>This is the primary object developers interact with after wrapping an {@link OpenAIClient}.
 * All six metered service accessors are eagerly constructed at creation time. Implements
 * {@link AutoCloseable} — calling {@link #close()} shuts down the Revenium metering transport
 * (allowing in-flight events to drain) without closing the underlying delegate {@link OpenAIClient}.
 * The caller retains ownership of the delegate client's lifecycle.
 *
 * <p>Example usage:
 * <pre>{@code
 * OpenAIClient openai = OpenAIClient.builder().build();
 * ReveniumConfig config = ReveniumConfig.builder()
 *     .apiKey("your-revenium-key")
 *     .build();
 *
 * try (ReveniumInstrumentedClient client = ReveniumOpenAIMiddleware.wrap(openai, config)) {
 *     ChatCompletion result = client.chatCompletions()
 *         .create(ChatCompletionCreateParams.builder()
 *             .model(ChatModel.GPT_4O)
 *             .addUserMessage("Hello!")
 *             .build());
 * }
 * // MeteringClient is closed; openai client is still open for reuse
 * }</pre>
 *
 * <p>Thread-safe: all fields are private final and set at construction. Concurrent calls
 * to any accessor method are safe.
 */
public final class ReveniumInstrumentedClient implements AutoCloseable {

    private final OpenAIClient delegate;
    private final MeteringClient meteringClient;

    private final MeteringChatCompletionService chat;
    private final MeteringChatCompletionServiceAsync chatAsync;
    private final MeteringEmbeddingService embeddings;
    private final MeteringEmbeddingServiceAsync embeddingsAsync;
    private final MeteringResponseService responses;
    private final MeteringResponseServiceAsync responsesAsync;

    /**
     * Package-private constructor — use {@link ReveniumOpenAIMiddleware#wrap} to create instances.
     *
     * <p>All six metered wrappers are eagerly constructed, delegating to the respective
     * services accessed from the {@code delegate} client.
     *
     * @param delegate       the original {@link OpenAIClient} to wrap
     * @param config         Revenium configuration (API key, base URL, provider detection)
     * @param meteringClient the shared metering transport for all six wrappers
     */
    ReveniumInstrumentedClient(OpenAIClient delegate, ReveniumConfig config, MeteringClient meteringClient) {
        this.delegate = delegate;
        this.meteringClient = meteringClient;

        this.chat = new MeteringChatCompletionService(
                delegate.chat().completions(), config, meteringClient);
        this.chatAsync = new MeteringChatCompletionServiceAsync(
                delegate.async().chat().completions(), config, meteringClient);
        this.embeddings = new MeteringEmbeddingService(
                delegate.embeddings(), config, meteringClient);
        this.embeddingsAsync = new MeteringEmbeddingServiceAsync(
                delegate.async().embeddings(), config, meteringClient);
        this.responses = new MeteringResponseService(
                delegate.responses(), config, meteringClient);
        this.responsesAsync = new MeteringResponseServiceAsync(
                delegate.async().responses(), config, meteringClient);
    }

    /**
     * Returns the metered sync chat completions service.
     *
     * @return a {@link MeteringChatCompletionService} that transparently meters all chat completion calls
     */
    public MeteringChatCompletionService chatCompletions() {
        return chat;
    }

    /**
     * Returns the metered async chat completions service.
     *
     * @return a {@link MeteringChatCompletionServiceAsync} that transparently meters all async chat completion calls
     */
    public MeteringChatCompletionServiceAsync chatCompletionsAsync() {
        return chatAsync;
    }

    /**
     * Returns the metered sync embeddings service.
     *
     * @return a {@link MeteringEmbeddingService} that transparently meters all embedding create() calls
     */
    public MeteringEmbeddingService embeddings() {
        return embeddings;
    }

    /**
     * Returns the metered async embeddings service.
     *
     * @return a {@link MeteringEmbeddingServiceAsync} that transparently meters all async embedding create() calls
     */
    public MeteringEmbeddingServiceAsync embeddingsAsync() {
        return embeddingsAsync;
    }

    /**
     * Returns the metered sync responses service.
     *
     * @return a {@link MeteringResponseService} that transparently meters all response create() calls
     */
    public MeteringResponseService responses() {
        return responses;
    }

    /**
     * Returns the metered async responses service.
     *
     * @return a {@link MeteringResponseServiceAsync} that transparently meters all async response create() calls
     */
    public MeteringResponseServiceAsync responsesAsync() {
        return responsesAsync;
    }

    /**
     * Returns the original {@link OpenAIClient} that was passed to
     * {@link ReveniumOpenAIMiddleware#wrap}. Useful when direct access to the unwrapped
     * client is needed (e.g., for non-metered operations or testing).
     *
     * @return the delegate {@link OpenAIClient}
     */
    public OpenAIClient unwrap() {
        return delegate;
    }

    /**
     * Closes the Revenium metering transport, allowing a best-effort 5-second drain of any
     * pending metering events before shutting down.
     *
     * <p><strong>Important:</strong> This method does NOT close the underlying delegate
     * {@link OpenAIClient}. The caller retains full ownership of the delegate's lifecycle.
     * This design prevents accidental resource leakage in scenarios where the same
     * {@link OpenAIClient} is shared across multiple instrumented clients or continues
     * to be used after metering is stopped.
     */
    @Override
    public void close() {
        meteringClient.close();
    }
}
