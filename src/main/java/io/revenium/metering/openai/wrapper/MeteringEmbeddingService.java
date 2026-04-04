package io.revenium.metering.openai.wrapper;

import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.services.blocking.EmbeddingService;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.Provider;
import io.revenium.metering.openai.provider.ProviderDetector;
import io.revenium.metering.openai.provider.extraction.EmbeddingUsageExtractor;
import io.revenium.metering.openai.transport.MeteringClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * A {@link EmbeddingService} wrapper that transparently meters all embedding create() calls
 * to Revenium while delegating the actual API work to the wrapped delegate.
 *
 * <p>All {@link #create} calls are intercepted to capture timing, token usage, model
 * information, and optional business metadata, then fire metering events asynchronously
 * via {@link MeteringClient}. Metering failures never propagate to the caller — the
 * delegate response is always returned (RESIL-01).
 *
 * <p>Thread-safe: all per-call state (requestTime, responseTime) is local to each method
 * invocation. All instance fields are private final (immutable). Safe for concurrent use
 * across multiple threads (RESIL-02).
 *
 * <p>Note: {@link #withRawResponse()} and {@link #withOptions(Consumer)} return un-metered
 * instances from the delegate — metering is only applied through this wrapper's methods.
 */
public final class MeteringEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(MeteringEmbeddingService.class);

    private final EmbeddingService delegate;
    private final MeteringClient meteringClient;
    private final Provider provider;

    /**
     * Constructs a new {@code MeteringEmbeddingService}.
     *
     * @param delegate       the underlying SDK {@link EmbeddingService} to delegate to
     * @param config         Revenium configuration used to detect provider from base URL
     * @param meteringClient the metering client used for fire-and-forget event delivery
     */
    public MeteringEmbeddingService(
            EmbeddingService delegate,
            ReveniumConfig config,
            MeteringClient meteringClient) {
        this.delegate = delegate;
        this.meteringClient = meteringClient;
        this.provider = ProviderDetector.detect(config.baseUrl());
    }

    // -----------------------------------------------------------------------
    // Metered: create() — sync (EMBED-01, EMBED-02, RESIL-01)
    // -----------------------------------------------------------------------

    /**
     * Delegates to the wrapped service and meters the response. Metering failures are caught
     * and logged — the delegate response is always returned (RESIL-01).
     *
     * <p>Per-call timing state is captured in local variables (requestTime, responseTime),
     * ensuring thread safety (RESIL-02).
     */
    @Override
    public CreateEmbeddingResponse create(EmbeddingCreateParams params, RequestOptions requestOptions) {
        long requestTime = System.currentTimeMillis();
        CreateEmbeddingResponse response = delegate.create(params, requestOptions);
        long responseTime = System.currentTimeMillis();
        try {
            MeteringEvent event = EmbeddingUsageExtractor.extract(
                    response, provider, null, requestTime, responseTime);
            meteringClient.send(event);
        } catch (Exception e) {
            log.warn("Metering failed for embedding create: {}", e.getMessage());
        }
        return response;
    }

    /**
     * Overloaded create() that accepts {@link UsageMetadata} for business context.
     * Uses {@link RequestOptions#none()} for request options.
     *
     * <p>Per-call timing state is captured in local variables (requestTime, responseTime),
     * ensuring thread safety (RESIL-02).
     */
    public CreateEmbeddingResponse create(EmbeddingCreateParams params, UsageMetadata metadata) {
        long requestTime = System.currentTimeMillis();
        CreateEmbeddingResponse response = delegate.create(params, RequestOptions.none());
        long responseTime = System.currentTimeMillis();
        try {
            MeteringEvent event = EmbeddingUsageExtractor.extract(
                    response, provider, metadata, requestTime, responseTime);
            meteringClient.send(event);
        } catch (Exception e) {
            log.warn("Metering failed for embedding create: {}", e.getMessage());
        }
        return response;
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): withRawResponse, withOptions
    // -----------------------------------------------------------------------

    /**
     * Returns the delegate's raw-response variant. Note: this returns an un-metered instance —
     * metering is only applied through {@link MeteringEmbeddingService}'s own methods.
     */
    @Override
    public EmbeddingService.WithRawResponse withRawResponse() {
        return delegate.withRawResponse();
    }

    /**
     * Returns the delegate's options-modified variant. Note: this returns an un-metered instance —
     * metering is only applied through {@link MeteringEmbeddingService}'s own methods.
     */
    @Override
    public EmbeddingService withOptions(Consumer<ClientOptions.Builder> modifier) {
        return delegate.withOptions(modifier);
    }
}
