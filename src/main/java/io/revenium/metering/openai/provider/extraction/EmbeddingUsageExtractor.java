package io.revenium.metering.openai.provider.extraction;

import com.openai.models.embeddings.CreateEmbeddingResponse;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.AzureModelResolver;
import io.revenium.metering.openai.provider.Provider;

import java.util.Locale;

/**
 * Extracts a {@link MeteringEvent} from an OpenAI SDK {@link CreateEmbeddingResponse}.
 *
 * <p>Embedding usage is always present (not Optional) — {@code response.usage()} returns
 * {@code CreateEmbeddingResponse.Usage} directly and is never null.
 *
 * <p>Embeddings have no finish reason or output tokens — only {@code promptTokens} (input)
 * and {@code totalTokens} are extracted. {@code stopReason} is not set.
 *
 * <p>When provider is {@link Provider#AZURE}, the model name is resolved from Azure deployment
 * name to canonical OpenAI model name via {@link AzureModelResolver#resolve(String)}.
 *
 * <p>Provider string is always lowercase (e.g., "openai", "azure", "ollama") to match the
 * Python middleware convention and Revenium API expectations.
 *
 * <p>Thread-safe: no mutable state. Suitable for concurrent use.
 */
public final class EmbeddingUsageExtractor {

    private EmbeddingUsageExtractor() {
        // Utility class — not instantiable
    }

    /**
     * Extracts a {@link MeteringEvent} from a {@link CreateEmbeddingResponse} SDK response.
     *
     * <p>Usage is required for embeddings (not Optional) — {@code response.usage()} is always
     * non-null, enforced by the SDK builder's {@code checkRequired("usage", usage)}.
     *
     * @param response     the SDK CreateEmbeddingResponse (never null)
     * @param provider     the detected AI provider (never null)
     * @param metadata     optional business context; may be null
     * @param requestTime  epoch millis at start of the SDK call
     * @param responseTime epoch millis at end of the SDK call
     * @return a populated {@link MeteringEvent} ready for {@code MeteringClient.send()}
     */
    public static MeteringEvent extract(
            CreateEmbeddingResponse response,
            Provider provider,
            UsageMetadata metadata,
            long requestTime,
            long responseTime) {

        // Usage is required for embeddings — direct access, not Optional
        CreateEmbeddingResponse.Usage usage = response.usage();
        Long inputTokens = usage.promptTokens();  // long → Long autobox
        Long totalTokens = usage.totalTokens();   // long → Long autobox
        // No outputTokenCount — embeddings have no completion tokens

        // Model resolution — Azure deployment names → canonical model names
        String resolvedModel = response.model();
        if (provider == Provider.AZURE) {
            resolvedModel = AzureModelResolver.resolve(resolvedModel);
        }

        // Provider string must be lowercase
        String providerStr = provider.name().toLowerCase(Locale.ROOT);

        MeteringEvent.Builder builder = MeteringEvent.builder()
                .model(resolvedModel)
                .provider(providerStr)
                .modelSource(providerStr)
                .inputTokenCount(inputTokens)
                .totalTokenCount(totalTokens)
                .outputTokenCount(0L)
                .stopReason("END")
                .requestTimeMillis(requestTime)
                .responseTimeMillis(responseTime)
                .completionStartTimeMillis(responseTime)
                .requestDuration(responseTime - requestTime)
                .isStreamed(false)
                .operationType("EMBED");

        // Map metadata fields — null-safe guard
        if (metadata != null) {
            builder.traceId(metadata.traceId())
                   .taskType(metadata.taskType())
                   .organizationName(metadata.organizationName())
                   .subscriptionId(metadata.subscriptionId())
                   .productName(metadata.productName())
                   .agent(metadata.agent())
                   .responseQualityScore(metadata.responseQualityScore())
                   .subscriber(metadata.subscriber()); // same model.Subscriber type — pass through directly
        }

        return builder.build();
    }
}
