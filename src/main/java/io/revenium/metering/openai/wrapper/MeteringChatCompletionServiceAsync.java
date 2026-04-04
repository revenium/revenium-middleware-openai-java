package io.revenium.metering.openai.wrapper;

import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionDeleteParams;
import com.openai.models.chat.completions.ChatCompletionDeleted;
import com.openai.models.chat.completions.ChatCompletionListPageAsync;
import com.openai.models.chat.completions.ChatCompletionListParams;
import com.openai.models.chat.completions.ChatCompletionRetrieveParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionUpdateParams;
import com.openai.services.async.chat.ChatCompletionServiceAsync;
import com.openai.services.async.chat.completions.MessageServiceAsync;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.Provider;
import io.revenium.metering.openai.provider.ProviderDetector;
import io.revenium.metering.openai.provider.extraction.ChatCompletionUsageExtractor;
import io.revenium.metering.openai.transport.MeteringClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * An async {@link ChatCompletionServiceAsync} wrapper that transparently meters all chat
 * completion calls to Revenium while delegating the actual API work to the wrapped delegate.
 *
 * <p>Non-streaming async calls use {@link CompletableFuture#whenComplete} to fire metering
 * after the future resolves without blocking the caller. Metering failures are caught and
 * logged — the user's future resolves normally (RESIL-01).
 *
 * <p>Streaming async calls wrap the returned {@link AsyncStreamResponse} in a
 * {@link MeteringAsyncStreamResponse} that intercepts subscriber callbacks ({@code onNext} /
 * {@code onComplete}) to capture TTFT and final-chunk token usage, firing metering on stream
 * completion (CHAT-04, CHAT-05).
 *
 * <p>Thread-safe: all per-call state is local to each method invocation or captured in local
 * lambda closures. No mutable instance fields. Safe for concurrent use (RESIL-02, D-07).
 *
 * <p>Note: {@link #withRawResponse()} and {@link #withOptions(Consumer)} return un-metered
 * instances from the delegate — metering is only applied through this wrapper's methods.
 */
public final class MeteringChatCompletionServiceAsync implements ChatCompletionServiceAsync {

    private static final Logger log = LoggerFactory.getLogger(MeteringChatCompletionServiceAsync.class);

    private final ChatCompletionServiceAsync delegate;
    private final MeteringClient meteringClient;
    private final Provider provider;

    /**
     * Constructs a new {@code MeteringChatCompletionServiceAsync}.
     *
     * @param delegate       the underlying SDK {@link ChatCompletionServiceAsync} to delegate to
     * @param config         Revenium configuration used to detect provider from base URL
     * @param meteringClient the metering client used for fire-and-forget event delivery
     */
    public MeteringChatCompletionServiceAsync(
            ChatCompletionServiceAsync delegate,
            ReveniumConfig config,
            MeteringClient meteringClient) {
        this.delegate = delegate;
        this.meteringClient = meteringClient;
        this.provider = ProviderDetector.detect(config.baseUrl());
    }

    // -----------------------------------------------------------------------
    // Metered: create() — non-streaming async (CHAT-05, RESIL-01)
    // -----------------------------------------------------------------------

    /**
     * Delegates to the wrapped service and meters the response via {@code whenComplete()}.
     * Metering failures are caught and logged — the delegate CompletableFuture resolves normally
     * (RESIL-01). No per-call state in instance fields (D-07, RESIL-02).
     */
    @Override
    public CompletableFuture<ChatCompletion> create(
            ChatCompletionCreateParams params, RequestOptions requestOptions) {
        long requestTime = System.currentTimeMillis();
        return delegate.create(params, requestOptions).whenComplete((response, error) -> {
            if (response != null) {
                try {
                    long responseTime = System.currentTimeMillis();
                    MeteringEvent event = ChatCompletionUsageExtractor.extract(
                            params, response, provider, null, requestTime, responseTime);
                    meteringClient.send(event);
                } catch (Exception e) {
                    log.warn("Metering failed for async chat create: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Overloaded create() that accepts {@link UsageMetadata} for business context (D-02).
     * Uses {@link RequestOptions#none()} for request options.
     */
    public CompletableFuture<ChatCompletion> create(
            ChatCompletionCreateParams params, UsageMetadata metadata) {
        long requestTime = System.currentTimeMillis();
        return delegate.create(params, RequestOptions.none()).whenComplete((response, error) -> {
            if (response != null) {
                try {
                    long responseTime = System.currentTimeMillis();
                    MeteringEvent event = ChatCompletionUsageExtractor.extract(
                            params, response, provider, metadata, requestTime, responseTime);
                    meteringClient.send(event);
                } catch (Exception e) {
                    log.warn("Metering failed for async chat create: {}", e.getMessage());
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // Metered: createStreaming() — async streaming (CHAT-03, CHAT-04, CHAT-05)
    // -----------------------------------------------------------------------

    /**
     * Injects {@code stream_options.include_usage=true} (unless already set), delegates to
     * the wrapped service, and wraps the returned {@link AsyncStreamResponse} in a
     * {@link MeteringAsyncStreamResponse} that captures TTFT and final-chunk token usage,
     * firing metering on stream completion.
     */
    @Override
    public AsyncStreamResponse<ChatCompletionChunk> createStreaming(
            ChatCompletionCreateParams params, RequestOptions requestOptions) {
        ChatCompletionCreateParams enriched = injectStreamUsage(params);
        long requestTime = System.currentTimeMillis();
        AsyncStreamResponse<ChatCompletionChunk> raw = delegate.createStreaming(enriched, requestOptions);
        return new MeteringAsyncStreamResponse(raw, provider, null, requestTime, meteringClient);
    }

    /**
     * Overloaded createStreaming() that accepts {@link UsageMetadata} for business context.
     * Uses {@link RequestOptions#none()} for request options.
     */
    public AsyncStreamResponse<ChatCompletionChunk> createStreaming(
            ChatCompletionCreateParams params, UsageMetadata metadata) {
        ChatCompletionCreateParams enriched = injectStreamUsage(params);
        long requestTime = System.currentTimeMillis();
        AsyncStreamResponse<ChatCompletionChunk> raw = delegate.createStreaming(enriched, RequestOptions.none());
        return new MeteringAsyncStreamResponse(raw, provider, metadata, requestTime, meteringClient);
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): retrieve, update, list, delete, messages
    // -----------------------------------------------------------------------

    @Override
    public CompletableFuture<ChatCompletion> retrieve(
            ChatCompletionRetrieveParams params, RequestOptions requestOptions) {
        return delegate.retrieve(params, requestOptions);
    }

    @Override
    public CompletableFuture<ChatCompletion> update(
            ChatCompletionUpdateParams params, RequestOptions requestOptions) {
        return delegate.update(params, requestOptions);
    }

    @Override
    public CompletableFuture<ChatCompletionListPageAsync> list(
            ChatCompletionListParams params, RequestOptions requestOptions) {
        return delegate.list(params, requestOptions);
    }

    @Override
    public CompletableFuture<ChatCompletionDeleted> delete(
            ChatCompletionDeleteParams params, RequestOptions requestOptions) {
        return delegate.delete(params, requestOptions);
    }

    @Override
    public MessageServiceAsync messages() {
        return delegate.messages();
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): withRawResponse, withOptions
    // -----------------------------------------------------------------------

    /**
     * Returns the delegate's raw-response variant. Note: this returns an un-metered instance —
     * metering is only applied through {@link MeteringChatCompletionServiceAsync}'s own methods.
     */
    @Override
    public ChatCompletionServiceAsync.WithRawResponse withRawResponse() {
        return delegate.withRawResponse();
    }

    /**
     * Returns the delegate's options-modified variant. Note: this returns an un-metered instance —
     * metering is only applied through {@link MeteringChatCompletionServiceAsync}'s own methods.
     */
    @Override
    public ChatCompletionServiceAsync withOptions(Consumer<ClientOptions.Builder> modifier) {
        return delegate.withOptions(modifier);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Injects {@code stream_options.include_usage=true} into the params unless the caller
     * has already configured stream options (D-04).
     */
    static ChatCompletionCreateParams injectStreamUsage(ChatCompletionCreateParams params) {
        if (params.streamOptions().isPresent()
                && params.streamOptions().get().includeUsage().isPresent()) {
            return params; // user already configured it — respect their settings
        }
        ChatCompletionStreamOptions streamOptions = ChatCompletionStreamOptions.builder()
                .includeUsage(true)
                .build();
        return params.toBuilder().streamOptions(streamOptions).build();
    }
}
