package io.revenium.metering.openai.wrapper;

import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionDeleteParams;
import com.openai.models.chat.completions.ChatCompletionDeleted;
import com.openai.models.chat.completions.ChatCompletionListPage;
import com.openai.models.chat.completions.ChatCompletionListParams;
import com.openai.models.chat.completions.ChatCompletionRetrieveParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionUpdateParams;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.openai.services.blocking.chat.completions.MessageService;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.Provider;
import io.revenium.metering.openai.provider.ProviderDetector;
import io.revenium.metering.openai.provider.extraction.ChatCompletionUsageExtractor;
import io.revenium.metering.openai.transport.MeteringClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * A {@link ChatCompletionService} wrapper that transparently meters all chat completion calls
 * to Revenium while delegating the actual API work to the wrapped delegate.
 *
 * <p>This is the primary user-facing metering interface for sync chat completions. All
 * {@link #create} and {@link #createStreaming} calls are intercepted to capture timing,
 * token usage, model information, and optional business metadata, then fire metering events
 * asynchronously via {@link MeteringClient}. Metering failures never propagate to the caller —
 * the delegate response is always returned.
 *
 * <p>The streaming path injects {@code stream_options.include_usage=true} automatically
 * (unless already set) and wraps the returned {@link StreamResponse} in a
 * {@link MeteringStreamResponse} that captures TTFT and final-chunk token usage, firing
 * metering on stream close.
 *
 * <p>Thread-safe: all per-call state is local to each method invocation. No mutable instance
 * fields. Safe for concurrent use across multiple threads (RESIL-02).
 *
 * <p>Note: {@link #withRawResponse()} and {@link #withOptions(Consumer)} return un-metered
 * instances from the delegate — metering is only applied through this wrapper's methods.
 */
public final class MeteringChatCompletionService implements ChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(MeteringChatCompletionService.class);

    private final ChatCompletionService delegate;
    private final MeteringClient meteringClient;
    private final Provider provider;

    /**
     * Constructs a new {@code MeteringChatCompletionService}.
     *
     * @param delegate       the underlying SDK {@link ChatCompletionService} to delegate to
     * @param config         Revenium configuration used to detect provider from base URL
     * @param meteringClient the metering client used for fire-and-forget event delivery
     */
    public MeteringChatCompletionService(
            ChatCompletionService delegate,
            ReveniumConfig config,
            MeteringClient meteringClient) {
        this.delegate = delegate;
        this.meteringClient = meteringClient;
        this.provider = ProviderDetector.detect(config.baseUrl());
    }

    // -----------------------------------------------------------------------
    // Metered: create() — non-streaming (CHAT-01, CHAT-02, CHAT-06, RESIL-01)
    // -----------------------------------------------------------------------

    /**
     * Delegates to the wrapped service and meters the response. Metering failures are caught
     * and logged — the delegate response is always returned (RESIL-01).
     */
    @Override
    public ChatCompletion create(ChatCompletionCreateParams params, RequestOptions requestOptions) {
        long requestTime = System.currentTimeMillis();
        ChatCompletion response = delegate.create(params, requestOptions);
        long responseTime = System.currentTimeMillis();
        try {
            MeteringEvent event = ChatCompletionUsageExtractor.extract(
                    params, response, provider, null, requestTime, responseTime);
            meteringClient.send(event);
        } catch (Exception e) {
            log.warn("Metering failed for chat create: {}", e.getMessage());
        }
        return response;
    }

    /**
     * Overloaded create() that accepts {@link UsageMetadata} for business context (D-02).
     * Uses {@link RequestOptions#none()} for request options.
     */
    public ChatCompletion create(ChatCompletionCreateParams params, UsageMetadata metadata) {
        long requestTime = System.currentTimeMillis();
        ChatCompletion response = delegate.create(params, RequestOptions.none());
        long responseTime = System.currentTimeMillis();
        try {
            MeteringEvent event = ChatCompletionUsageExtractor.extract(
                    params, response, provider, metadata, requestTime, responseTime);
            meteringClient.send(event);
        } catch (Exception e) {
            log.warn("Metering failed for chat create: {}", e.getMessage());
        }
        return response;
    }

    // -----------------------------------------------------------------------
    // Metered: createStreaming() — streaming (CHAT-03, CHAT-04)
    // -----------------------------------------------------------------------

    /**
     * Injects {@code stream_options.include_usage=true} (unless already set), delegates to
     * the wrapped service, and wraps the returned stream in a {@link MeteringStreamResponse}
     * that captures TTFT and final-chunk token usage, firing metering on close.
     */
    @Override
    public StreamResponse<ChatCompletionChunk> createStreaming(
            ChatCompletionCreateParams params, RequestOptions requestOptions) {
        ChatCompletionCreateParams enriched = injectStreamUsage(params);
        long requestTime = System.currentTimeMillis();
        StreamResponse<ChatCompletionChunk> raw = delegate.createStreaming(enriched, requestOptions);
        return new MeteringStreamResponse(raw, provider, null, requestTime, meteringClient);
    }

    /**
     * Overloaded createStreaming() that accepts {@link UsageMetadata} for business context.
     * Uses {@link RequestOptions#none()} for request options.
     */
    public StreamResponse<ChatCompletionChunk> createStreaming(
            ChatCompletionCreateParams params, UsageMetadata metadata) {
        ChatCompletionCreateParams enriched = injectStreamUsage(params);
        long requestTime = System.currentTimeMillis();
        StreamResponse<ChatCompletionChunk> raw = delegate.createStreaming(enriched, RequestOptions.none());
        return new MeteringStreamResponse(raw, provider, metadata, requestTime, meteringClient);
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): retrieve, update, list, delete, messages
    // -----------------------------------------------------------------------

    @Override
    public ChatCompletion retrieve(ChatCompletionRetrieveParams params, RequestOptions requestOptions) {
        return delegate.retrieve(params, requestOptions);
    }

    @Override
    public ChatCompletion update(ChatCompletionUpdateParams params, RequestOptions requestOptions) {
        return delegate.update(params, requestOptions);
    }

    @Override
    public ChatCompletionListPage list(ChatCompletionListParams params, RequestOptions requestOptions) {
        return delegate.list(params, requestOptions);
    }

    @Override
    public ChatCompletionDeleted delete(ChatCompletionDeleteParams params, RequestOptions requestOptions) {
        return delegate.delete(params, requestOptions);
    }

    @Override
    public MessageService messages() {
        return delegate.messages();
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): withRawResponse, withOptions
    // -----------------------------------------------------------------------

    /**
     * Returns the delegate's raw-response variant. Note: this returns an un-metered instance —
     * metering is only applied through {@link MeteringChatCompletionService}'s own methods.
     */
    @Override
    public ChatCompletionService.WithRawResponse withRawResponse() {
        return delegate.withRawResponse();
    }

    /**
     * Returns the delegate's options-modified variant. Note: this returns an un-metered instance —
     * metering is only applied through {@link MeteringChatCompletionService}'s own methods.
     */
    @Override
    public ChatCompletionService withOptions(Consumer<ClientOptions.Builder> modifier) {
        return delegate.withOptions(modifier);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Injects {@code stream_options.include_usage=true} into the params unless the caller
     * has already configured stream options (D-04).
     */
    private static ChatCompletionCreateParams injectStreamUsage(ChatCompletionCreateParams params) {
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
