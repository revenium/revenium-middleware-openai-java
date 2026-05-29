package io.revenium.metering.openai.wrapper;

import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseStreamEvent;
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
 * {@link ResponseStreamEvent} events and fires a Revenium metering event on stream completion.
 */
final class MeteringAsyncResponseStreamResponse implements AsyncStreamResponse<ResponseStreamEvent> {

    private static final Logger log = LoggerFactory.getLogger(MeteringAsyncResponseStreamResponse.class);

    private final AsyncStreamResponse<ResponseStreamEvent> delegate;
    private final Provider provider;
    private final UsageMetadata metadata;
    private final long requestTime;
    private final MeteringClient meteringClient;

    MeteringAsyncResponseStreamResponse(
            AsyncStreamResponse<ResponseStreamEvent> delegate,
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

    @Override
    public AsyncStreamResponse<ResponseStreamEvent> subscribe(
            Handler<? super ResponseStreamEvent> userHandler) {
        return subscribe(userHandler, Runnable::run);
    }

    @Override
    public AsyncStreamResponse<ResponseStreamEvent> subscribe(
            Handler<? super ResponseStreamEvent> userHandler,
            Executor executor) {
        long[] firstTokenTime = {-1L};
        String[] model = {null};
        String[] status = {null};
        Long[] inputTokens = {null};
        Long[] outputTokens = {null};
        Long[] totalTokens = {null};
        long requestTimeCaptured = this.requestTime;

        final long[] firstTokenTimeRef = firstTokenTime;
        final String[] modelRef = model;
        final String[] statusRef = status;
        final Long[] inputTokensRef = inputTokens;
        final Long[] outputTokensRef = outputTokens;
        final Long[] totalTokensRef = totalTokens;

        delegate.subscribe(new Handler<ResponseStreamEvent>() {
            @Override
            public void onNext(ResponseStreamEvent event) {
                try {
                    if (event.isOutputTextDelta()) {
                        event.outputTextDelta().ifPresent(deltaEvent -> {
                            String text = deltaEvent.delta();
                            if (firstTokenTimeRef[0] < 0 && text != null && !text.isEmpty()) {
                                firstTokenTimeRef[0] = System.currentTimeMillis();
                            }
                        });
                    }
                    if (event.isCreated() && modelRef[0] == null) {
                        event.created().ifPresent(createdEvent ->
                                modelRef[0] = createdEvent.response().model().asString());
                    }
                    if (event.isCompleted()) {
                        event.completed().ifPresent(completedEvent -> {
                            Response response = completedEvent.response();
                            modelRef[0] = response.model().asString();
                            response.status().ifPresent(s -> statusRef[0] = s.asString());
                            response.usage().ifPresent(u -> {
                                inputTokensRef[0] = u.inputTokens();
                                outputTokensRef[0] = u.outputTokens();
                                totalTokensRef[0] = u.totalTokens();
                            });
                        });
                    }
                    if (event.isFailed()) {
                        statusRef[0] = "failed";
                        if (modelRef[0] == null) {
                            event.failed().ifPresent(failedEvent ->
                                    modelRef[0] = failedEvent.response().model().asString());
                        }
                    }
                    if (event.isIncomplete()) {
                        statusRef[0] = "incomplete";
                        if (modelRef[0] == null) {
                            event.incomplete().ifPresent(incompleteEvent ->
                                    modelRef[0] = incompleteEvent.response().model().asString());
                        }
                    }
                } catch (Exception ignored) {
                    // Event observation errors must not affect delivery — RESIL-01
                }
                userHandler.onNext(event);
            }

            @Override
            public void onComplete(Optional<Throwable> error) {
                try {
                    fireAsyncMetering(
                            requestTimeCaptured,
                            firstTokenTimeRef[0],
                            modelRef[0],
                            statusRef[0],
                            inputTokensRef[0],
                            outputTokensRef[0],
                            totalTokensRef[0],
                            metadata,
                            provider,
                            meteringClient);
                } catch (Exception e) {
                    log.warn("Metering failed for async streaming response: {}", e.getMessage());
                }
                userHandler.onComplete(error);
            }
        }, executor);

        return this;
    }

    @Override
    public CompletableFuture<Void> onCompleteFuture() {
        return delegate.onCompleteFuture();
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static void fireAsyncMetering(
            long requestTimeCaptured,
            long firstTokenTimeVal,
            String modelVal,
            String statusVal,
            Long inputTokensVal,
            Long outputTokensVal,
            Long totalTokensVal,
            UsageMetadata metadataVal,
            Provider providerVal,
            MeteringClient meteringClient) {
        try {
            if (modelVal == null) {
                return;
            }
            long responseTime = System.currentTimeMillis();
            String resolvedModel = modelVal;
            if (providerVal == Provider.AZURE) {
                resolvedModel = AzureModelResolver.resolve(modelVal);
            }
            String stopReason = mapResponseStatus(statusVal).name();
            long ttft = firstTokenTimeVal > 0 ? firstTokenTimeVal - requestTimeCaptured : 0L;
            String providerStr = providerVal.name().toLowerCase(Locale.ROOT);

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
            log.warn("Metering failed for async streaming response create: {}", e.getMessage());
        }
    }

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
