package io.revenium.metering.openai.wrapper;

import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseCreatedEvent;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
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
 * A {@link StreamResponse} wrapper that observes {@link ResponseStreamEvent} events via
 * {@link Stream#peek} and fires a Revenium metering event when the stream is closed.
 *
 * <p>Per-stream state (TTFT, model, usage, status) is held in local effectively-final arrays
 * inside {@link #stream()} — never in named instance fields — ensuring thread safety (D-07).
 * After {@link #stream()} is called, the accumulated metering action is stored in an
 * {@link AtomicReference} so that {@link #close()} can trigger it if needed.
 *
 * <p>Key difference from {@link MeteringStreamResponse}: usage is NOT delivered as a trailing
 * chunk. Instead, it arrives embedded inside {@code ResponseCompletedEvent.response().usage()}.
 * TTFT comes from the first non-empty {@code ResponseTextDeltaEvent.delta()}.
 *
 * <p>Metering is fired exactly once per stream via {@link #close()}.
 * Metering failures are caught and logged; they never propagate to the caller.
 *
 * <p>Thread-safe: all per-call mutable state lives in local variables in {@link #stream()}.
 */
final class MeteringResponseStreamResponse implements StreamResponse<ResponseStreamEvent> {

    private static final Logger log = LoggerFactory.getLogger(MeteringResponseStreamResponse.class);

    // Immutable instance fields
    private final StreamResponse<ResponseStreamEvent> delegate;
    private final Provider provider;
    private final UsageMetadata metadata;   // nullable
    private final long requestTime;
    private final MeteringClient meteringClient;

    /**
     * Holds the accumulated metering action, set by {@link #stream()} after the stream is
     * built. Called by {@link #close()} to fire metering. Cleared after first invocation
     * to ensure at-most-once semantics.
     */
    private final AtomicReference<Runnable> pendingMeteringAction = new AtomicReference<>(null);

    /**
     * Constructs a new {@code MeteringResponseStreamResponse}.
     *
     * @param delegate       the underlying SDK stream response to wrap
     * @param provider       the detected AI provider
     * @param metadata       optional business context metadata; may be null
     * @param requestTime    epoch millis when the streaming request was initiated
     * @param meteringClient client used for fire-and-forget metering delivery
     */
    MeteringResponseStreamResponse(
            StreamResponse<ResponseStreamEvent> delegate,
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
     * Returns a {@link Stream} of {@link ResponseStreamEvent} that transparently observes each
     * event via {@code peek()} to capture TTFT, model, usage, and status.
     *
     * <p>Per-call state is held in effectively-final single-element arrays (D-07). After the
     * stream is built, a metering action closure that captures those local arrays is stored in
     * {@link #pendingMeteringAction}, which {@link #close()} will invoke.
     */
    @Override
    public Stream<ResponseStreamEvent> stream() {
        // Per-call state in local effectively-final arrays (D-07)
        long[] firstTokenTime = {-1L};
        String[] model = {null};
        Long[] inputTokens = {null};
        Long[] outputTokens = {null};
        Long[] totalTokens = {null};
        String[] statusStr = {null};
        long requestTimeCaptured = this.requestTime;

        // Capture references for the metering action closure
        final long[] firstTokenTimeRef = firstTokenTime;
        final String[] modelRef = model;
        final Long[] inputTokensRef = inputTokens;
        final Long[] outputTokensRef = outputTokens;
        final Long[] totalTokensRef = totalTokens;
        final String[] statusStrRef = statusStr;

        Runnable meteringAction = () -> fireMeteringIfPossible(
                requestTimeCaptured,
                firstTokenTimeRef[0],
                modelRef[0],
                inputTokensRef[0],
                outputTokensRef[0],
                totalTokensRef[0],
                statusStrRef[0]);

        pendingMeteringAction.set(meteringAction);

        return delegate.stream().peek(event -> {
            try {
                // TTFT: first ResponseTextDeltaEvent with non-empty delta (RESP-04)
                if (event.isOutputTextDelta()) {
                    ResponseTextDeltaEvent deltaEvent = event.outputTextDelta().get();
                    String text = deltaEvent.delta();
                    if (firstTokenTime[0] < 0 && text != null && !text.isEmpty()) {
                        firstTokenTime[0] = System.currentTimeMillis();
                    }
                }

                // Model from first ResponseCreatedEvent (early in stream, no usage yet)
                if (event.isCreated() && model[0] == null) {
                    ResponseCreatedEvent createdEvent = event.created().get();
                    model[0] = createdEvent.response().model().asString();
                }

                // Usage + model + status from ResponseCompletedEvent (terminal event with full Response)
                if (event.isCompleted()) {
                    ResponseCompletedEvent completedEvent = event.completed().get();
                    Response r = completedEvent.response();
                    model[0] = r.model().asString(); // override with confirmed value
                    r.usage().ifPresent(u -> {
                        inputTokens[0] = u.inputTokens();
                        outputTokens[0] = u.outputTokens();
                        totalTokens[0] = u.totalTokens();
                    });
                    statusStr[0] = r.status().map(ResponseStatus::asString).orElse(null);
                }

                // Track failed/incomplete terminal events for stop reason
                if (event.isFailed()) {
                    statusStr[0] = "failed";
                }
                if (event.isIncomplete()) {
                    statusStr[0] = "incomplete";
                }
            } catch (Exception e) {
                log.debug("Error processing response stream event: {}", e.getMessage());
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
     * All metering failures are caught and logged.
     */
    private void fireMeteringIfPossible(
            long requestTimeCaptured,
            long firstTokenTimeVal,
            String modelVal,
            Long inputTokensVal,
            Long outputTokensVal,
            Long totalTokensVal,
            String statusStrVal) {
        try {
            // No events were processed — skip metering (empty stream)
            if (modelVal == null) {
                return;
            }

            long responseTime = System.currentTimeMillis();

            // Azure deployment name resolution
            String resolvedModel = modelVal;
            if (provider == Provider.AZURE) {
                resolvedModel = AzureModelResolver.resolve(modelVal);
            }

            // Map ResponseStatus to StopReason
            String stopReason = mapResponseStatus(statusStrVal).name();

            // TTFT: millis from request start to first content token (RESP-04)
            long ttft = firstTokenTimeVal > 0 ? firstTokenTimeVal - requestTimeCaptured : 0L;

            // Provider string must be lowercase
            String providerStr = provider.name().toLowerCase(Locale.ROOT);

            long completionStartTime = firstTokenTimeVal > 0 ? firstTokenTimeVal : responseTime;

            MeteringEvent.Builder builder = MeteringEvent.builder()
                    .model(resolvedModel)
                    .provider(providerStr)
                    .modelSource(providerStr)
                    .inputTokenCount(inputTokensVal)
                    .outputTokenCount(outputTokensVal)
                    .totalTokenCount(totalTokensVal)
                    .stopReason(stopReason)
                    .requestTimeMillis(requestTimeCaptured)
                    .responseTimeMillis(responseTime)
                    .completionStartTimeMillis(completionStartTime)
                    .requestDuration(responseTime - requestTimeCaptured)
                    .isStreamed(true)
                    .timeToFirstToken(ttft)
                    .operationType("CHAT");

            // Map optional business context metadata
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
            log.warn("Metering failed for streaming response create: {}", e.getMessage());
        }
    }

    /**
     * Maps a {@link ResponseStatus} string value to the corresponding {@link StopReason}.
     *
     * <p>Mirrors {@code ResponseUsageExtractor.mapResponseStatus()} for streaming context.
     * Cannot use {@link StopReason#fromFinishReason(String)} because the Responses API uses
     * different string values than chat completions.
     */
    private static StopReason mapResponseStatus(String status) {
        if (status == null) {
            return StopReason.END;
        }
        switch (status) {
            case "completed":  return StopReason.END;
            case "failed":     return StopReason.ERROR;
            case "incomplete": return StopReason.TOKEN_LIMIT;
            case "cancelled":  return StopReason.ERROR;
            default:
                log.warn("Unknown ResponseStatus '{}', defaulting to ERROR", status);
                return StopReason.ERROR;
        }
    }
}
