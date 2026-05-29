package io.revenium.metering.openai.provider.extraction;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.StopReason;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.AzureModelResolver;
import io.revenium.metering.openai.provider.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

/**
 * Extracts a {@link MeteringEvent} from an OpenAI SDK Responses API {@link Response} object.
 *
 * <p>Stateless utility class — all fields are extracted from the response and caller-supplied
 * parameters. Adapts the {@link ChatCompletionUsageExtractor} pattern for the three critical
 * SDK type differences in the Responses API:
 *
 * <ol>
 *   <li>{@code ResponseUsage} uses {@code inputTokens}/{@code outputTokens} (not
 *       {@code promptTokens}/{@code completionTokens} like {@code CompletionUsage}).</li>
 *   <li>{@code Response.model()} returns {@code ResponsesModel} (a union type) — use
 *       {@code .asString()} for a plain string model name.</li>
 *   <li>{@code ResponseStatus} replaces {@code finishReason} — values are "completed",
 *       "failed", "incomplete", "cancelled", etc. Mapped via {@link #mapResponseStatus(String)},
 *       NOT via {@link StopReason#fromFinishReason(String)} which expects chat completion values.</li>
 * </ol>
 *
 * <p>Usage fields ({@code inputTokens}, {@code outputTokens}, {@code totalTokens}) are null-safe:
 * if the response contains no usage data, token counts are null and a DEBUG log is emitted.
 *
 * <p>When provider is {@link Provider#AZURE}, the model name is resolved from Azure deployment
 * name to canonical OpenAI model name via {@link AzureModelResolver#resolve(String)}.
 *
 * <p>Provider string is always lowercase (e.g., "openai", "azure", "ollama") to match the
 * Python middleware convention and Revenium API expectations.
 *
 * <p>Operation type is always "RESPONSE" (not "CHAT") so metering payloads are distinguishable.
 *
 * <p>Thread-safe: no mutable state. Suitable for concurrent use.
 */
public final class ResponseUsageExtractor {

    private static final Logger log = LoggerFactory.getLogger(ResponseUsageExtractor.class);

    private ResponseUsageExtractor() {
        // Utility class — not instantiable
    }

    /**
     * Extracts a {@link MeteringEvent} from a Responses API {@link Response} SDK object.
     *
     * <p>If usage is absent ({@code Optional.empty()}), token counts are null and a DEBUG log
     * is emitted. This is expected for providers that omit usage data.
     *
     * @param response     the SDK Response object (never null)
     * @param provider     the detected AI provider (never null)
     * @param metadata     optional business context; may be null
     * @param requestTime  epoch millis at start of the SDK call
     * @param responseTime epoch millis at end of the SDK call
     * @return a populated {@link MeteringEvent} ready for {@code MeteringClient.send()}
     */
    public static MeteringEvent extract(
            Response response,
            Provider provider,
            UsageMetadata metadata,
            long requestTime,
            long responseTime) {

        // Extract token counts — ResponseUsage uses inputTokens/outputTokens (not promptTokens/completionTokens)
        Optional<ResponseUsage> usageOpt = response.usage();
        Long inputTokens = null;
        Long outputTokens = null;
        Long totalTokens = null;

        if (usageOpt.isPresent()) {
            ResponseUsage usage = usageOpt.get();
            inputTokens = usage.inputTokens();    // long → Long autobox
            outputTokens = usage.outputTokens();  // long → Long autobox
            totalTokens = usage.totalTokens();    // long → Long autobox
        } else {
            log.debug("Response has no usage field — provider may omit usage data");
        }

        // Extract stop reason — ResponseStatus, NOT finishReason (different string values)
        // "completed" -> END, "failed" -> ERROR, "incomplete" -> TOKEN_LIMIT, "cancelled" -> ERROR
        String statusStr = response.status().map(ResponseStatus::asString).orElse(null);
        String stopReason = mapResponseStatus(statusStr).name();

        // Model extraction — ResponsesModel.asString() for correct string representation
        String rawModel = response.model().asString();

        // Azure deployment name resolution
        String resolvedModel = rawModel;
        if (provider == Provider.AZURE) {
            resolvedModel = AzureModelResolver.resolve(rawModel);
        }

        // Provider string must be lowercase
        String providerStr = provider.name().toLowerCase(Locale.ROOT);

        MeteringEvent.Builder builder = MeteringEvent.builder()
                .model(resolvedModel)
                .provider(providerStr)
                .modelSource(providerStr)
                .inputTokenCount(inputTokens)
                .outputTokenCount(outputTokens)
                .totalTokenCount(totalTokens)
                .stopReason(stopReason)
                .requestTimeMillis(requestTime)
                .responseTimeMillis(responseTime)
                .completionStartTimeMillis(responseTime)
                .requestDuration(responseTime - requestTime)
                .isStreamed(false)
                .operationType("CHAT");

        // Map metadata fields — null-safe guard
        if (metadata != null) {
            builder.traceId(metadata.traceId())
                   .taskType(metadata.taskType())
                   .organizationName(metadata.organizationName())
                   .subscriptionId(metadata.subscriptionId())
                   .productName(metadata.productName())
                   .agent(metadata.agent())
                   .responseQualityScore(metadata.responseQualityScore())
                   .subscriber(metadata.subscriber());
        }

        return builder.build();
    }

    /**
     * Maps a {@link ResponseStatus} string value to the corresponding {@link StopReason}.
     *
     * <p>Cannot use {@link StopReason#fromFinishReason(String)} directly because the Responses
     * API uses different string values ("completed" vs "stop", "incomplete" vs "length").
     *
     * <p>Mapping:
     * <ul>
     *   <li>{@code null} → {@link StopReason#END} (no status set)</li>
     *   <li>"completed" → {@link StopReason#END} (normal completion)</li>
     *   <li>"failed" → {@link StopReason#ERROR}</li>
     *   <li>"incomplete" → {@link StopReason#TOKEN_LIMIT} (truncated due to token limit)</li>
     *   <li>"cancelled" → {@link StopReason#ERROR}</li>
     *   <li>other → {@link StopReason#ERROR} (unknown status, logged at WARN)</li>
     * </ul>
     *
     * @param status the ResponseStatus string value (from {@code ResponseStatus.asString()}), may be null
     * @return the corresponding {@link StopReason}, never null
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
