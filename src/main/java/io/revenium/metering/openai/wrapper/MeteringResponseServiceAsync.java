package io.revenium.metering.openai.wrapper;

import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.responses.CompactedResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCancelParams;
import com.openai.models.responses.ResponseCompactParams;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseDeleteParams;
import com.openai.models.responses.ResponseRetrieveParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.async.ResponseServiceAsync;
import com.openai.services.async.responses.InputItemServiceAsync;
import com.openai.services.async.responses.InputTokenServiceAsync;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.Provider;
import io.revenium.metering.openai.provider.ProviderDetector;
import io.revenium.metering.openai.provider.extraction.ResponseUsageExtractor;
import io.revenium.metering.openai.transport.MeteringClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * An async {@link ResponseServiceAsync} wrapper that transparently meters all create() calls
 * to Revenium while delegating the actual API work to the wrapped delegate.
 *
 * <p>Non-streaming async calls use {@link CompletableFuture#whenComplete} to fire metering
 * after the future resolves without blocking the caller. Streaming async calls wrap the
 * returned {@link AsyncStreamResponse} in a {@link MeteringAsyncResponseStreamResponse}.
 *
 * <p>Thread-safe: all per-call state is local to each method invocation or lambda closure.
 */
public final class MeteringResponseServiceAsync implements ResponseServiceAsync {

    private static final Logger log = LoggerFactory.getLogger(MeteringResponseServiceAsync.class);

    private final ResponseServiceAsync delegate;
    private final MeteringClient meteringClient;
    private final Provider provider;

    /**
     * Constructs a new {@code MeteringResponseServiceAsync}.
     *
     * @param delegate       the underlying SDK {@link ResponseServiceAsync} to delegate to
     * @param config         Revenium configuration used to detect provider from base URL
     * @param meteringClient the metering client used for fire-and-forget event delivery
     */
    public MeteringResponseServiceAsync(
            ResponseServiceAsync delegate,
            ReveniumConfig config,
            MeteringClient meteringClient) {
        this.delegate = delegate;
        this.meteringClient = meteringClient;
        this.provider = ProviderDetector.detect(config.baseUrl());
    }

    // -----------------------------------------------------------------------
    // Metered: create() — async non-streaming
    // -----------------------------------------------------------------------

    @Override
    public CompletableFuture<Response> create(ResponseCreateParams params, RequestOptions requestOptions) {
        long requestTime = System.currentTimeMillis();
        return delegate.create(params, requestOptions).whenComplete((response, throwable) -> {
            if (throwable != null || response == null) {
                return; // don't meter failed calls
            }
            long responseTime = System.currentTimeMillis();
            try {
                MeteringEvent event = ResponseUsageExtractor.extract(
                        response, provider, null, requestTime, responseTime);
                meteringClient.send(event);
            } catch (Exception e) {
                log.warn("Metering failed for async response create: {}", e.getMessage());
            }
        });
    }

    /**
     * Overloaded create() that accepts {@link UsageMetadata} for business context (D-02).
     * Uses {@link RequestOptions#none()} for request options.
     */
    public CompletableFuture<Response> create(ResponseCreateParams params, UsageMetadata metadata) {
        long requestTime = System.currentTimeMillis();
        return delegate.create(params, RequestOptions.none()).whenComplete((response, throwable) -> {
            if (throwable != null || response == null) {
                return;
            }
            long responseTime = System.currentTimeMillis();
            try {
                MeteringEvent event = ResponseUsageExtractor.extract(
                        response, provider, metadata, requestTime, responseTime);
                meteringClient.send(event);
            } catch (Exception e) {
                log.warn("Metering failed for async response create: {}", e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Metered: createStreaming() — async streaming
    // -----------------------------------------------------------------------

    @Override
    public AsyncStreamResponse<ResponseStreamEvent> createStreaming(
            ResponseCreateParams params, RequestOptions requestOptions) {
        long requestTime = System.currentTimeMillis();
        AsyncStreamResponse<ResponseStreamEvent> raw = delegate.createStreaming(params, requestOptions);
        return new MeteringAsyncResponseStreamResponse(raw, provider, null, requestTime, meteringClient);
    }

    /**
     * Overloaded createStreaming() that accepts {@link UsageMetadata} for business context.
     */
    public AsyncStreamResponse<ResponseStreamEvent> createStreaming(
            ResponseCreateParams params, UsageMetadata metadata) {
        long requestTime = System.currentTimeMillis();
        AsyncStreamResponse<ResponseStreamEvent> raw = delegate.createStreaming(params, RequestOptions.none());
        return new MeteringAsyncResponseStreamResponse(raw, provider, metadata, requestTime, meteringClient);
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): retrieve, retrieveStreaming, delete, cancel, compact
    // -----------------------------------------------------------------------

    @Override
    public CompletableFuture<Response> retrieve(ResponseRetrieveParams params, RequestOptions requestOptions) {
        return delegate.retrieve(params, requestOptions);
    }

    @Override
    public AsyncStreamResponse<ResponseStreamEvent> retrieveStreaming(
            ResponseRetrieveParams params, RequestOptions requestOptions) {
        return delegate.retrieveStreaming(params, requestOptions);
    }

    @Override
    public CompletableFuture<Void> delete(ResponseDeleteParams params, RequestOptions requestOptions) {
        return delegate.delete(params, requestOptions);
    }

    @Override
    public CompletableFuture<Response> cancel(ResponseCancelParams params, RequestOptions requestOptions) {
        return delegate.cancel(params, requestOptions);
    }

    @Override
    public CompletableFuture<CompactedResponse> compact(ResponseCompactParams params, RequestOptions requestOptions) {
        return delegate.compact(params, requestOptions);
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): inputItems, inputTokens
    // -----------------------------------------------------------------------

    @Override
    public InputItemServiceAsync inputItems() {
        return delegate.inputItems();
    }

    @Override
    public InputTokenServiceAsync inputTokens() {
        return delegate.inputTokens();
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): withRawResponse, withOptions
    // -----------------------------------------------------------------------

    @Override
    public ResponseServiceAsync.WithRawResponse withRawResponse() {
        return delegate.withRawResponse();
    }

    @Override
    public ResponseServiceAsync withOptions(Consumer<ClientOptions.Builder> modifier) {
        return delegate.withOptions(modifier);
    }
}
