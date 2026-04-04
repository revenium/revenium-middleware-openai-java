package io.revenium.metering.openai.wrapper;

import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.services.async.EmbeddingServiceAsync;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.Provider;
import io.revenium.metering.openai.provider.ProviderDetector;
import io.revenium.metering.openai.provider.extraction.EmbeddingUsageExtractor;
import io.revenium.metering.openai.transport.MeteringClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * An {@link EmbeddingServiceAsync} wrapper that transparently meters all async embedding
 * create() calls to Revenium while delegating the actual API work to the wrapped delegate.
 *
 * <p>All {@link #create} calls are intercepted. Metering occurs in a {@code whenComplete}
 * callback on the returned {@link CompletableFuture} — capturing timing, token usage, model
 * information, and optional business metadata, then firing metering events via
 * {@link MeteringClient}. Metering failures never propagate to the caller — the delegate
 * future is always returned resolved normally (RESIL-01).
 *
 * <p>Thread-safe: all per-call state (requestTime) is a local variable captured in the
 * {@code whenComplete} closure. All instance fields are private final (immutable). Safe
 * for concurrent use across multiple threads (RESIL-02).
 *
 * <p>Note: {@link #withRawResponse()} and {@link #withOptions(Consumer)} return un-metered
 * instances from the delegate — metering is only applied through this wrapper's methods.
 */
public final class MeteringEmbeddingServiceAsync implements EmbeddingServiceAsync {

    private static final Logger log = LoggerFactory.getLogger(MeteringEmbeddingServiceAsync.class);

    private final EmbeddingServiceAsync delegate;
    private final MeteringClient meteringClient;
    private final Provider provider;

    /**
     * Constructs a new {@code MeteringEmbeddingServiceAsync}.
     *
     * @param delegate       the underlying SDK {@link EmbeddingServiceAsync} to delegate to
     * @param config         Revenium configuration used to detect provider from base URL
     * @param meteringClient the metering client used for fire-and-forget event delivery
     */
    public MeteringEmbeddingServiceAsync(
            EmbeddingServiceAsync delegate,
            ReveniumConfig config,
            MeteringClient meteringClient) {
        this.delegate = delegate;
        this.meteringClient = meteringClient;
        this.provider = ProviderDetector.detect(config.baseUrl());
    }

    // -----------------------------------------------------------------------
    // Metered: create() — async (EMBED-03, RESIL-01)
    // -----------------------------------------------------------------------

    /**
     * Delegates to the wrapped service and attaches a {@code whenComplete} callback for metering.
     * Metering failures are caught and logged — the future resolves normally regardless (RESIL-01).
     *
     * <p>Per-call timing state is captured in a local variable (requestTime) that is effectively
     * final in the whenComplete closure, ensuring thread safety (RESIL-02).
     */
    @Override
    public CompletableFuture<CreateEmbeddingResponse> create(
            EmbeddingCreateParams params, RequestOptions requestOptions) {
        long requestTime = System.currentTimeMillis();
        return delegate.create(params, requestOptions).whenComplete((response, error) -> {
            if (response != null) {
                try {
                    long responseTime = System.currentTimeMillis();
                    MeteringEvent event = EmbeddingUsageExtractor.extract(
                            response, provider, null, requestTime, responseTime);
                    meteringClient.send(event);
                } catch (Exception e) {
                    log.warn("Metering failed for async embedding create: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Overloaded async create() that accepts {@link UsageMetadata} for business context.
     * Uses {@link RequestOptions#none()} for request options.
     *
     * <p>Per-call timing state is captured in a local variable (requestTime) that is effectively
     * final in the whenComplete closure, ensuring thread safety (RESIL-02).
     */
    public CompletableFuture<CreateEmbeddingResponse> create(
            EmbeddingCreateParams params, UsageMetadata metadata) {
        long requestTime = System.currentTimeMillis();
        return delegate.create(params, RequestOptions.none()).whenComplete((response, error) -> {
            if (response != null) {
                try {
                    long responseTime = System.currentTimeMillis();
                    MeteringEvent event = EmbeddingUsageExtractor.extract(
                            response, provider, metadata, requestTime, responseTime);
                    meteringClient.send(event);
                } catch (Exception e) {
                    log.warn("Metering failed for async embedding create: {}", e.getMessage());
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // Delegating (un-metered): withRawResponse, withOptions
    // -----------------------------------------------------------------------

    /**
     * Returns the delegate's raw-response variant. Note: this returns an un-metered instance —
     * metering is only applied through {@link MeteringEmbeddingServiceAsync}'s own methods.
     */
    @Override
    public EmbeddingServiceAsync.WithRawResponse withRawResponse() {
        return delegate.withRawResponse();
    }

    /**
     * Returns the delegate's options-modified variant. Note: this returns an un-metered instance —
     * metering is only applied through {@link MeteringEmbeddingServiceAsync}'s own methods.
     */
    @Override
    public EmbeddingServiceAsync withOptions(Consumer<ClientOptions.Builder> modifier) {
        return delegate.withOptions(modifier);
    }
}
