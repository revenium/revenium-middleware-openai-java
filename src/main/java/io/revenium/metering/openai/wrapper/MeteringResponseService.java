package io.revenium.metering.openai.wrapper;

import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.CompactedResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCancelParams;
import com.openai.models.responses.ResponseCompactParams;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseDeleteParams;
import com.openai.models.responses.ResponseRetrieveParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ResponseService;
import com.openai.services.blocking.responses.InputItemService;
import com.openai.services.blocking.responses.InputTokenService;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.Provider;
import io.revenium.metering.openai.provider.ProviderDetector;
import io.revenium.metering.openai.provider.extraction.ResponseUsageExtractor;
import io.revenium.metering.openai.transport.MeteringClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * A {@link ResponseService} wrapper that transparently meters all create() calls to Revenium
 * while delegating the actual API work to the wrapped delegate.
 *
 * <p>All {@link #create} and {@link #createStreaming} calls are intercepted to capture timing,
 * token usage, model information, and optional business metadata, then fire metering events
 * via {@link MeteringClient}. Metering failures never propagate to the caller — the delegate
 * response is always returned.
 *
 * <p>The streaming path wraps the returned {@link StreamResponse} in a
 * {@link MeteringResponseStreamResponse} that captures TTFT and usage from
 * {@code ResponseCompletedEvent.response().usage()}, firing metering on stream close.
 * No stream_options injection is needed — the Responses API always includes usage in the
 * completed event (Pattern 4 from RESEARCH.md).
 *
 * <p>Thread-safe: all per-call state is local to each method invocation. No mutable instance
 * fields. Safe for concurrent use across multiple threads.
 *
 * <p>Note: {@link #withRawResponse()} and {@link #withOptions(Consumer)} return un-metered
 * instances from the delegate — metering is only applied through this wrapper's methods.
 */
public final class MeteringResponseService implements ResponseService {

    private static final Logger log = LoggerFactory.getLogger(MeteringResponseService.class);

    private final ResponseService delegate;
    private final MeteringClient meteringClient;
    private final Provider provider;

    /**
     * Constructs a new {@code MeteringResponseService}.
     *
     * @param delegate       the underlying SDK {@link ResponseService} to delegate to
     * @param config         Revenium configuration used to detect provider from base URL
     * @param meteringClient the metering client used for fire-and-forget event delivery
     */
    public MeteringResponseService(
            ResponseService delegate,
            ReveniumConfig config,
            MeteringClient meteringClient) {
        this.delegate = delegate;
        this.meteringClient = meteringClient;
        this.provider = ProviderDetector.detect(config.baseUrl());
    }

    // -----------------------------------------------------------------------
    // Metered: create() — non-streaming
    // -----------------------------------------------------------------------

    /**
     * Delegates to the wrapped service and meters the response. Metering failures are caught
     * and logged — the delegate response is always returned.
     */
    @Override
    public Response create(ResponseCreateParams params, RequestOptions requestOptions) {
        long requestTime = System.currentTimeMillis();
        Response response = delegate.create(params, requestOptions);
        long responseTime = System.currentTimeMillis();
        try {
            MeteringEvent event = ResponseUsageExtractor.extract(
                    response, provider, null, requestTime, responseTime);
            meteringClient.send(event);
        } catch (Exception e) {
            log.warn("Metering failed for response create: {}", e.getMessage());
        }
        return response;
    }

    /**
     * Overloaded create() that accepts {@link UsageMetadata} for business context (D-02).
     * Uses {@link RequestOptions#none()} for request options.
     */
    public Response create(ResponseCreateParams params, UsageMetadata metadata) {
        long requestTime = System.currentTimeMillis();
        Response response = delegate.create(params, RequestOptions.none());
        long responseTime = System.currentTimeMillis();
        try {
            MeteringEvent event = ResponseUsageExtractor.extract(
                    response, provider, metadata, requestTime, responseTime);
            meteringClient.send(event);
        } catch (Exception e) {
            log.warn("Metering failed for response create: {}", e.getMessage());
        }
        return response;
    }

    // -----------------------------------------------------------------------
    // Metered: createStreaming() — streaming
    // -----------------------------------------------------------------------

    /**
     * Delegates to the wrapped service and wraps the returned stream in a
     * {@link MeteringResponseStreamResponse} that captures TTFT and usage from the
     * {@code ResponseCompletedEvent}, firing metering on close.
     *
     * <p>No stream_options injection needed — Responses API always includes usage in the
     * completed event.
     */
    @Override
    public StreamResponse<ResponseStreamEvent> createStreaming(
            ResponseCreateParams params, RequestOptions requestOptions) {
        long requestTime = System.currentTimeMillis();
        StreamResponse<ResponseStreamEvent> raw = delegate.createStreaming(params, requestOptions);
        return new MeteringResponseStreamResponse(raw, provider, null, requestTime, meteringClient);
    }

    /**
     * Overloaded createStreaming() that accepts {@link UsageMetadata} for business context.
     * Uses {@link RequestOptions#none()} for request options.
     */
    public StreamResponse<ResponseStreamEvent> createStreaming(
            ResponseCreateParams params, UsageMetadata metadata) {
        long requestTime = System.currentTimeMillis();
        StreamResponse<ResponseStreamEvent> raw = delegate.createStreaming(params, RequestOptions.none());
        return new MeteringResponseStreamResponse(raw, provider, metadata, requestTime, meteringClient);
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): retrieve, retrieveStreaming, delete, cancel, compact
    // -----------------------------------------------------------------------

    @Override
    public Response retrieve(ResponseRetrieveParams params, RequestOptions requestOptions) {
        return delegate.retrieve(params, requestOptions);
    }

    @Override
    public StreamResponse<ResponseStreamEvent> retrieveStreaming(
            ResponseRetrieveParams params, RequestOptions requestOptions) {
        return delegate.retrieveStreaming(params, requestOptions);
    }

    @Override
    public void delete(ResponseDeleteParams params, RequestOptions requestOptions) {
        delegate.delete(params, requestOptions);
    }

    @Override
    public Response cancel(ResponseCancelParams params, RequestOptions requestOptions) {
        return delegate.cancel(params, requestOptions);
    }

    @Override
    public CompactedResponse compact(ResponseCompactParams params, RequestOptions requestOptions) {
        return delegate.compact(params, requestOptions);
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): inputItems, inputTokens
    // -----------------------------------------------------------------------

    @Override
    public InputItemService inputItems() {
        return delegate.inputItems();
    }

    @Override
    public InputTokenService inputTokens() {
        return delegate.inputTokens();
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): withRawResponse, withOptions
    // -----------------------------------------------------------------------

    /**
     * Returns the delegate's raw-response variant. Note: this returns an un-metered instance —
     * metering is only applied through {@link MeteringResponseService}'s own methods.
     */
    @Override
    public ResponseService.WithRawResponse withRawResponse() {
        return delegate.withRawResponse();
    }

    /**
     * Returns the delegate's options-modified variant. Note: this returns an un-metered instance —
     * metering is only applied through {@link MeteringResponseService}'s own methods.
     */
    @Override
    public ResponseService withOptions(Consumer<ClientOptions.Builder> modifier) {
        return delegate.withOptions(modifier);
    }
}
