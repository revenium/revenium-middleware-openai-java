package io.revenium.metering.openai.wrapper;

import com.openai.core.http.AsyncStreamResponse;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * An {@link AsyncStreamResponse} wrapper that intercepts subscriber callbacks to observe
 * {@link ChatCompletionChunk} events and fires a Revenium metering event on stream completion.
 *
 * <p>Per-stream state (TTFT, model, finish reason, usage) is held in local effectively-final
 * arrays inside {@link #subscribe} — never in named instance fields — ensuring thread safety
 * when the same wrapper class is used concurrently (D-07, RESIL-02).
 *
 * <p>The subscriber interception pattern wraps the caller's {@code Handler} with a decorated
 * handler that observes each chunk via {@code onNext} and fires metering in {@code onComplete}.
 * The user's callbacks are ALWAYS invoked — metering failures are caught and logged, never
 * propagated (RESIL-01).
 *
 * <p>{@link #subscribe(Handler)} returns {@code this} (not the delegate's return value) so
 * callers using the fluent API get back the same metered wrapper (Pitfall 7).
 */
final class MeteringAsyncStreamResponse implements AsyncStreamResponse<ChatCompletionChunk> {

    private static final Logger log = LoggerFactory.getLogger(MeteringAsyncStreamResponse.class);

    // Immutable instance fields
    private final AsyncStreamResponse<ChatCompletionChunk> delegate;
    private final Provider provider;
    private final UsageMetadata metadata;   // nullable
    private final long requestTime;
    private final MeteringClient meteringClient;

    /**
     * Constructs a new {@code MeteringAsyncStreamResponse}.
     *
     * @param delegate       the underlying SDK async stream response to wrap
     * @param provider       the detected AI provider
     * @param metadata       optional business context metadata; may be null
     * @param requestTime    epoch millis when the streaming request was initiated
     * @param meteringClient client used for fire-and-forget metering delivery
     */
    MeteringAsyncStreamResponse(
            AsyncStreamResponse<ChatCompletionChunk> delegate,
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
     * Subscribes to the stream with the given handler, using {@code Runnable::run} as the executor
     * (inline execution). Returns {@code this} to support fluent API chaining (Pitfall 7).
     *
     * @param userHandler the caller's handler to receive chunk events
     * @return this wrapper instance (not the delegate's return value)
     */
    @Override
    public AsyncStreamResponse<ChatCompletionChunk> subscribe(
            Handler<? super ChatCompletionChunk> userHandler) {
        return subscribe(userHandler, Runnable::run);
    }

    /**
     * Subscribes to the stream with the given handler and executor. Per-call state (TTFT, model,
     * finish reason, usage) is in local effectively-final arrays (D-07 thread safety). The
     * decorated handler observes each chunk and fires metering in {@code onComplete}.
     *
     * <p>User callbacks ({@code onNext} / {@code onComplete}) are ALWAYS invoked regardless of
     * metering outcome (RESIL-01). Returns {@code this} to support fluent API chaining (Pitfall 7).
     *
     * @param userHandler the caller's handler to receive chunk events
     * @param executor    the executor on which to invoke handler callbacks
     * @return this wrapper instance (not the delegate's return value)
     */
    @Override
    public AsyncStreamResponse<ChatCompletionChunk> subscribe(
            Handler<? super ChatCompletionChunk> userHandler,
            Executor executor) {
        // Per-call state in local effectively-final arrays (D-07 thread safety)
        long[] firstTokenTime = {-1L};
        String[] model = {null};
        String[] finishReason = {null};
        CompletionUsage[] usage = {null};
        long requestTimeCaptured = this.requestTime;

        // Capture final references so the anonymous class and fireAsyncMetering can see them
        final long[] firstTokenTimeRef = firstTokenTime;
        final String[] modelRef = model;
        final String[] finishReasonRef = finishReason;
        final CompletionUsage[] usageRef = usage;

        delegate.subscribe(new Handler<ChatCompletionChunk>() {
            @Override
            public void onNext(ChatCompletionChunk chunk) {
                try {
                    // Capture model from first non-null/non-empty chunk
                    if (modelRef[0] == null && chunk.model() != null && !chunk.model().isEmpty()) {
                        modelRef[0] = chunk.model();
                    }

                    // Capture time-to-first-token from first chunk with non-empty content (CHAT-04)
                    if (firstTokenTimeRef[0] < 0
                            && !chunk.choices().isEmpty()
                            && chunk.choices().get(0).delta().content().isPresent()
                            && !chunk.choices().get(0).delta().content().get().isEmpty()) {
                        firstTokenTimeRef[0] = System.currentTimeMillis();
                    }

                    // Capture finish reason from final choice chunk
                    if (!chunk.choices().isEmpty()
                            && chunk.choices().get(0).finishReason().isPresent()) {
                        finishReasonRef[0] = chunk.choices().get(0).finishReason().get().asString();
                    }

                    // Capture usage from final chunk (requires stream_options.include_usage=true)
                    if (chunk.usage().isPresent()) {
                        usageRef[0] = chunk.usage().get();
                    }
                } catch (Exception ignored) {
                    // Chunk observation errors must not affect delivery — RESIL-01
                }
                // ALWAYS pass through to user — RESIL-01
                userHandler.onNext(chunk);
            }

            @Override
            public void onComplete(Optional<Throwable> error) {
                try {
                    fireAsyncMetering(
                            requestTimeCaptured,
                            firstTokenTimeRef[0],
                            modelRef[0],
                            finishReasonRef[0],
                            usageRef[0],
                            metadata,
                            provider,
                            meteringClient);
                } catch (Exception e) {
                    log.warn("Metering failed for async streaming chat: {}", e.getMessage());
                }
                // ALWAYS pass through to user — RESIL-01
                userHandler.onComplete(error);
            }
        }, executor);

        // MUST return this, not delegate's result (Pitfall 7)
        return this;
    }

    /**
     * Returns the underlying delegate's completion future.
     */
    @Override
    public CompletableFuture<Void> onCompleteFuture() {
        return delegate.onCompleteFuture();
    }

    /**
     * Closes the underlying delegate stream.
     */
    @Override
    public void close() {
        delegate.close();
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
     * @param metadataVal          optional business context metadata; may be null
     * @param providerVal          the detected AI provider
     * @param meteringClient       the metering client to send the event to
     */
    private static void fireAsyncMetering(
            long requestTimeCaptured,
            long firstTokenTimeVal,
            String modelVal,
            String finishReasonVal,
            CompletionUsage usageVal,
            UsageMetadata metadataVal,
            Provider providerVal,
            MeteringClient meteringClient) {
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
            if (providerVal == Provider.AZURE) {
                resolvedModel = AzureModelResolver.resolve(modelVal);
            }

            // Map finish reason to Revenium stop reason
            String stopReason = StopReason.fromFinishReason(finishReasonVal).name();

            // TTFT: millis from request start to first content token (CHAT-04)
            long ttft = firstTokenTimeVal > 0 ? firstTokenTimeVal - requestTimeCaptured : 0L;

            // Provider string must be lowercase (Pitfall 5)
            String providerStr = providerVal.name().toLowerCase(Locale.ROOT);

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
            if (metadataVal != null) {
                builder.traceId(metadataVal.traceId())
                        .taskType(metadataVal.taskType())
                        .organizationName(metadataVal.organizationName())
                        .subscriptionId(metadataVal.subscriptionId())
                        .productName(metadataVal.productName())
                        .agent(metadataVal.agent())
                        .responseQualityScore(metadataVal.responseQualityScore())
                        .subscriber(metadataVal.subscriber());
            }

            meteringClient.send(builder.build());

        } catch (Exception e) {
            log.warn("Metering failed for async streaming chat create: {}", e.getMessage());
        }
    }
}
