package io.revenium.metering.openai.wrapper;

import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.completions.CompletionUsage;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.StopReason;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.AzureModelResolver;
import io.revenium.metering.openai.provider.Provider;
import io.revenium.metering.openai.transport.MeteringClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * A {@link StreamResponse} wrapper that observes {@link ChatCompletionChunk} events via
 * {@link Stream#peek} and fires a Revenium metering event when the stream is closed.
 *
 * <p>Per-stream state (TTFT, model, finish reason, usage) is held in local effectively-final
 * arrays inside {@link #stream()} — never in named instance fields — ensuring thread safety
 * when the same wrapper class is used concurrently (D-07). After {@link #stream()} is called,
 * the accumulated metering action is stored in an {@link AtomicReference} so that
 * {@link #close()} can trigger it if needed.
 *
 * <p>Metering is fired exactly once per stream via {@link #close()}, which invokes the
 * accumulated metering action (a no-op if the stream was empty / model was null).
 *
 * <p>Metering failures are caught and logged; they never propagate to the caller (RESIL-01).
 *
 * <p>Thread-safe: all per-call mutable state lives in local variables in {@link #stream()}.
 * Only private final immutable fields and the {@link AtomicReference} are instance fields (RESIL-02).
 */
final class MeteringStreamResponse implements StreamResponse<ChatCompletionChunk> {

    private static final Logger log = LoggerFactory.getLogger(MeteringStreamResponse.class);

    // Immutable instance fields
    private final StreamResponse<ChatCompletionChunk> delegate;
    private final Provider provider;
    private final UsageMetadata metadata;   // nullable
    private final long requestTime;
    private final MeteringClient meteringClient;

    /**
     * Holds the accumulated metering action, set by {@link #stream()} after the stream is
     * built. Called by {@link #close()} to fire metering. The AtomicReference is cleared
     * after the first invocation to ensure at-most-once semantics.
     */
    private final AtomicReference<Runnable> pendingMeteringAction = new AtomicReference<>(null);

    /**
     * Constructs a new {@code MeteringStreamResponse}.
     *
     * @param delegate       the underlying SDK stream response to wrap
     * @param provider       the detected AI provider
     * @param metadata       optional business context metadata; may be null
     * @param requestTime    epoch millis when the streaming request was initiated
     * @param meteringClient client used for fire-and-forget metering delivery
     */
    MeteringStreamResponse(
            StreamResponse<ChatCompletionChunk> delegate,
            Provider provider,
            UsageMetadata metadata,
            long requestTime,
            MeteringClient meteringClient) {
        this.delegate = delegate;
        this.provider = provider;
        this.metadata = metadata;
        this.requestTime = requestTime;
        this.meteringClient = meteringClient;
    }

    /**
     * Returns a {@link Stream} of chunks that transparently observes each chunk via
     * {@code peek()} to capture TTFT, model, finish reason, and token usage.
     *
     * <p>Per-call state is held in effectively-final single-element arrays (D-07: never
     * named instance fields). After the stream is built, a metering action closure that
     * captures those local arrays is stored in {@link #pendingMeteringAction}, which
     * {@link #close()} will invoke to fire metering.
     */
    @Override
    public Stream<ChatCompletionChunk> stream() {
        // Per-call state in local effectively-final arrays (D-07: never named instance fields)
        long[] firstTokenTime = {-1L};
        String[] model = {null};
        String[] finishReason = {null};
        CompletionUsage[] usage = {null};
        long requestTimeCaptured = this.requestTime;

        // The metering action captures the local arrays by reference.
        // Stored in AtomicReference so close() can invoke it.
        // Use a final snapshot capture to ensure close() sees the final array values.
        final long[] firstTokenTimeRef = firstTokenTime;
        final String[] modelRef = model;
        final String[] finishReasonRef = finishReason;
        final CompletionUsage[] usageRef = usage;

        Runnable meteringAction = () -> fireMeteringIfPossible(
                requestTimeCaptured,
                firstTokenTimeRef[0],
                modelRef[0],
                finishReasonRef[0],
                usageRef[0]);

        pendingMeteringAction.set(meteringAction);

        return delegate.stream().peek(chunk -> {
            try {
                // Capture model from first non-null/non-empty chunk
                if (model[0] == null && chunk.model() != null && !chunk.model().isEmpty()) {
                    model[0] = chunk.model();
                }

                // Capture time-to-first-token from first chunk with non-empty content (CHAT-04)
                if (firstTokenTime[0] < 0
                        && !chunk.choices().isEmpty()
                        && chunk.choices().get(0).delta().content().isPresent()
                        && !chunk.choices().get(0).delta().content().get().isEmpty()) {
                    firstTokenTime[0] = System.currentTimeMillis();
                }

                // Capture finish reason from final choice chunk
                if (!chunk.choices().isEmpty()
                        && chunk.choices().get(0).finishReason().isPresent()) {
                    finishReason[0] = chunk.choices().get(0).finishReason().get().asString();
                }

                // Capture usage from final chunk (requires stream_options.include_usage=true)
                if (chunk.usage().isPresent()) {
                    usage[0] = chunk.usage().get();
                }
            } catch (Exception e) {
                log.debug("Error processing stream chunk: {}", e.getMessage());
            }
        });
    }

    /**
     * Closes the underlying delegate and fires metering with all state accumulated during
     * stream consumption. Metering fires at-most-once per instance (cleared after first call).
     */
    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            // Fire metering: swap the action out so it fires at most once
            Runnable action = pendingMeteringAction.getAndSet(null);
            if (action != null) {
                action.run();
            }
        }
    }

    /**
     * Fires a metering event if enough data was captured from the stream.
     *
     * <p>All per-call state is received as parameters (not read from named instance fields — D-07).
     * If {@code modelVal} is null (no chunks were processed), metering is skipped silently.
     * All metering failures are caught and logged at WARN level (RESIL-01).
     *
     * @param requestTimeCaptured  epoch millis when the request was initiated
     * @param firstTokenTimeVal    epoch millis of first non-empty content chunk, or -1 if none
     * @param modelVal             model string from first chunk, or null if no chunks
     * @param finishReasonVal      finish reason string from final chunk, or null
     * @param usageVal             CompletionUsage from final chunk (requires include_usage), or null
     */
    private void fireMeteringIfPossible(
            long requestTimeCaptured,
            long firstTokenTimeVal,
            String modelVal,
            String finishReasonVal,
            CompletionUsage usageVal) {
        try {
            // No chunks were processed — skip metering (empty stream)
            if (modelVal == null) {
                return;
            }

            long responseTime = System.currentTimeMillis();

            // Extract token counts from usage (nullable — provider may not send usage)
            Long inputTokens = null;
            Long outputTokens = null;
            Long totalTokens = null;
            if (usageVal != null) {
                inputTokens = usageVal.promptTokens();
                outputTokens = usageVal.completionTokens();
                totalTokens = usageVal.totalTokens();
            }

            // Azure deployment name resolution
            String resolvedModel = modelVal;
            if (provider == Provider.AZURE) {
                resolvedModel = AzureModelResolver.resolve(modelVal);
            }

            // Map finish reason to Revenium stop reason
            String stopReason = StopReason.fromFinishReason(finishReasonVal).name();

            // TTFT: millis from request start to first content token (CHAT-04)
            long ttft = firstTokenTimeVal > 0 ? firstTokenTimeVal - requestTimeCaptured : 0L;

            // Provider string must be lowercase (Pitfall 5)
            String providerStr = provider.name().toLowerCase(Locale.ROOT);

            long completionStartTime = firstTokenTimeVal > 0 ? firstTokenTimeVal : responseTime;

            MeteringEvent.Builder builder = MeteringEvent.builder()
                    .model(resolvedModel)
                    .provider(providerStr)
                    .modelSource(providerStr)
                    .inputTokenCount(inputTokens)
                    .outputTokenCount(outputTokens)
                    .totalTokenCount(totalTokens)
                    .stopReason(stopReason)
                    .requestTimeMillis(requestTimeCaptured)
                    .responseTimeMillis(responseTime)
                    .completionStartTimeMillis(completionStartTime)
                    .requestDuration(responseTime - requestTimeCaptured)
                    .isStreamed(true)
                    .timeToFirstToken(ttft)
                    .operationType("CHAT");

            // Map optional business context metadata (D-08)
            if (metadata != null) {
                builder.traceId(metadata.traceId())
                        .taskType(metadata.taskType())
                        .organizationId(metadata.organizationId())
                        .subscriptionId(metadata.subscriptionId())
                        .productId(metadata.productId())
                        .agent(metadata.agent())
                        .responseQualityScore(metadata.responseQualityScore())
                        .subscriber(metadata.subscriber());
            }

            meteringClient.send(builder.build());

        } catch (Exception e) {
            log.warn("Metering failed for streaming chat create: {}", e.getMessage());
        }
    }
}
