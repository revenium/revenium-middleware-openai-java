package io.revenium.metering.openai.provider.extraction;

import com.openai.models.embeddings.CreateEmbeddingResponse;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.Subscriber;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.Provider;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EmbeddingUsageExtractor}.
 *
 * TDD RED phase: tests written before the implementation class exists.
 * Covers all behaviors specified in 03-02-PLAN.md Task 2.
 */
class EmbeddingUsageExtractorTest {

    private static final long REQUEST_TIME = 2_000_000L;
    private static final long RESPONSE_TIME = 2_000_300L; // 300ms duration

    // --- Helpers ---

    private CreateEmbeddingResponse.Usage buildUsage(long promptTokens, long totalTokens) {
        return CreateEmbeddingResponse.Usage.builder()
                .promptTokens(promptTokens)
                .totalTokens(totalTokens)
                .build();
    }

    private CreateEmbeddingResponse buildResponse(String model, long promptTokens, long totalTokens) {
        return CreateEmbeddingResponse.builder()
                .model(model)
                .usage(buildUsage(promptTokens, totalTokens))
                .data(Collections.emptyList())
                .build();
    }

    // --- Token extraction ---

    @Test
    void extractReturnsCorrectInputTokenCount() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 75L, 75L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getInputTokenCount()).isEqualTo(75L);
    }

    @Test
    void extractReturnsCorrectTotalTokenCount() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 75L, 75L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getTotalTokenCount()).isEqualTo(75L);
    }

    @Test
    void extractDoesNotSetOutputTokenCount() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 75L, 75L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        // Embeddings set output token count to 0
        assertThat(event.getOutputTokenCount()).isEqualTo(0L);
    }

    // --- Model ---

    @Test
    void extractReturnsCorrectModel() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-large", 100L, 100L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("text-embedding-3-large");
    }

    // --- Operation type and streaming ---

    @Test
    void extractSetsOperationTypeToEMBEDDING() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getOperationType()).isEqualTo("EMBED");
    }

    @Test
    void extractSetsIsStreamedFalse() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getIsStreamed()).isFalse();
    }

    @Test
    void extractDoesNotSetStopReason() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        // Embeddings now set stop reason to END
        assertThat(event.getStopReason()).isEqualTo("END");
    }

    // --- Provider string is lowercase ---

    @Test
    void extractWithOpenAiProviderReturnsLowercaseProviderString() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getProvider()).isEqualTo("openai");
    }

    @Test
    void extractWithAzureProviderReturnsLowercaseProviderString() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-large-prod", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.AZURE, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getProvider()).isEqualTo("azure");
    }

    @Test
    void extractWithOllamaProviderReturnsLowercaseProviderString() {
        CreateEmbeddingResponse response = buildResponse("nomic-embed-text", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OLLAMA, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getProvider()).isEqualTo("ollama");
    }

    // --- Azure model resolution ---

    @Test
    void extractWithAzureProviderResolvesDeploymentNameToCanonicalModel() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-large-prod", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.AZURE, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("text-embedding-3-large");
    }

    @Test
    void extractWithOpenAiProviderPassesThroughModelNameUnchanged() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("text-embedding-3-small");
    }

    // --- Timing ---

    @Test
    void extractComputesRequestDuration() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getRequestDuration()).isEqualTo(RESPONSE_TIME - REQUEST_TIME);
    }

    @Test
    void extractSetsRequestAndResponseTime() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getRequestTime()).isNotNull();
        assertThat(event.getResponseTime()).isNotNull();
    }

    // --- UsageMetadata mapping ---

    @Test
    void extractWithMetadataMapsAllFields() {
        Subscriber subscriber = Subscriber.builder()
                .id("sub-embed-1")
                .email("embedder@example.com")
                .build();

        UsageMetadata metadata = UsageMetadata.builder()
                .traceId("embed-trace-xyz")
                .taskType("semantic-search")
                .organizationId("org-embed-123")
                .subscriptionId("sub-embed-plan")
                .productId("prod-embed-789")
                .agent("embed-agent")
                .responseQualityScore(0.85)
                .subscriber(subscriber)
                .build();

        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, metadata, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getTraceId()).isEqualTo("embed-trace-xyz");
        assertThat(event.getTaskType()).isEqualTo("semantic-search");
        assertThat(event.getOrganizationId()).isEqualTo("org-embed-123");
        assertThat(event.getSubscriptionId()).isEqualTo("sub-embed-plan");
        assertThat(event.getProductId()).isEqualTo("prod-embed-789");
        assertThat(event.getAgent()).isEqualTo("embed-agent");
        assertThat(event.getResponseQualityScore()).isEqualTo(0.85);
        assertThat(event.getSubscriber()).isSameAs(subscriber);
    }

    @Test
    void extractWithNullMetadataDoesNotThrowNpe() {
        CreateEmbeddingResponse response = buildResponse("text-embedding-3-small", 50L, 50L);

        MeteringEvent event = EmbeddingUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event).isNotNull();
        assertThat(event.getTraceId()).isNull();
        assertThat(event.getOrganizationId()).isNull();
        assertThat(event.getSubscriber()).isNull();
    }
}
