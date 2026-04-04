package io.revenium.metering.openai.provider.extraction;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.ToolChoiceOptions;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.Subscriber;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.Provider;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResponseUsageExtractor}.
 *
 * TDD RED phase: tests written before the implementation class exists.
 * Covers all behaviors specified in 05-01-PLAN.md Task 1.
 */
class ResponseUsageExtractorTest {

    private static final long REQUEST_TIME = 1_000_000L;
    private static final long RESPONSE_TIME = 1_001_500L; // 1500ms duration

    // --- Helpers ---

    private ResponseUsage buildUsage(long input, long output, long total) {
        // SDK 4.20.0 requires inputTokensDetails and outputTokensDetails
        ResponseUsage.InputTokensDetails inputDetails = ResponseUsage.InputTokensDetails.builder()
                .cachedTokens(0L)
                .build();
        ResponseUsage.OutputTokensDetails outputDetails = ResponseUsage.OutputTokensDetails.builder()
                .reasoningTokens(0L)
                .build();
        return ResponseUsage.builder()
                .inputTokens(input)
                .inputTokensDetails(inputDetails)
                .outputTokens(output)
                .outputTokensDetails(outputDetails)
                .totalTokens(total)
                .build();
    }

    /**
     * Builds a minimal valid Response with all SDK-required fields populated.
     * SDK 4.20.0 requires: id, createdAt, error, incompleteDetails, instructions,
     * metadata, model, output, parallelToolCalls, temperature, toolChoice, tools, topP.
     */
    private Response buildResponseWithUsage(String model, ResponseUsage usage, ResponseStatus status) {
        return Response.builder()
                .id("resp-id-123")
                .model(model)
                .createdAt((double) (System.currentTimeMillis() / 1000))
                .error(Optional.empty())
                .incompleteDetails(Optional.empty())
                .instructions(Optional.empty())
                .metadata(Optional.empty())
                .output(Collections.emptyList())
                .parallelToolCalls(true)
                .temperature(Optional.empty())
                .toolChoice(ToolChoiceOptions.AUTO)
                .tools(Collections.emptyList())
                .topP(Optional.empty())
                .status(status)
                .usage(usage)
                .build();
    }

    private Response buildResponseWithoutUsage(String model, ResponseStatus status) {
        return Response.builder()
                .id("resp-id-456")
                .model(model)
                .createdAt((double) (System.currentTimeMillis() / 1000))
                .error(Optional.empty())
                .incompleteDetails(Optional.empty())
                .instructions(Optional.empty())
                .metadata(Optional.empty())
                .output(Collections.emptyList())
                .parallelToolCalls(true)
                .temperature(Optional.empty())
                .toolChoice(ToolChoiceOptions.AUTO)
                .tools(Collections.emptyList())
                .topP(Optional.empty())
                .status(status)
                .build();
    }

    // --- Token extraction with usage present ---

    @Test
    void extractWithUsagePresentReturnsCorrectTokenCounts() {
        ResponseUsage usage = buildUsage(100L, 50L, 150L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getInputTokenCount()).isEqualTo(100L);
        assertThat(event.getOutputTokenCount()).isEqualTo(50L);
        assertThat(event.getTotalTokenCount()).isEqualTo(150L);
    }

    @Test
    void extractWithUsagePresentReturnsCorrectModel() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void extractUsesModelAsString() {
        // ResponsesModel.asString() must be used, not toString()
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o-mini", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("gpt-4o-mini");
    }

    // --- Operation type ---

    @Test
    void extractSetsOperationTypeToRESPONSE() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getOperationType()).isEqualTo("CHAT");
    }

    @Test
    void extractSetsIsStreamedFalse() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getIsStreamed()).isFalse();
    }

    @Test
    void extractComputesRequestDuration() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getRequestDuration()).isEqualTo(RESPONSE_TIME - REQUEST_TIME);
    }

    // --- ResponseStatus -> StopReason mapping ---

    @Test
    void extractWithCompletedStatusReturnsEndStopReason() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getStopReason()).isEqualTo("END");
    }

    @Test
    void extractWithFailedStatusReturnsErrorStopReason() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.FAILED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getStopReason()).isEqualTo("ERROR");
    }

    @Test
    void extractWithIncompleteStatusReturnsTokenLimitStopReason() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.INCOMPLETE);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getStopReason()).isEqualTo("TOKEN_LIMIT");
    }

    @Test
    void extractWithCancelledStatusReturnsErrorStopReason() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.CANCELLED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getStopReason()).isEqualTo("ERROR");
    }

    // --- Absent usage (Optional.empty()) ---

    @Test
    void extractWithAbsentUsageReturnsNullTokenCounts() {
        Response response = buildResponseWithoutUsage("gpt-4o", ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getInputTokenCount()).isNull();
        assertThat(event.getOutputTokenCount()).isNull();
        assertThat(event.getTotalTokenCount()).isNull();
    }

    @Test
    void extractWithAbsentUsageDoesNotThrowNpe() {
        Response response = buildResponseWithoutUsage("gpt-4o", ResponseStatus.COMPLETED);

        // Must not throw any exception
        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event).isNotNull();
    }

    @Test
    void extractWithAbsentUsageStillReturnsValidEvent() {
        Response response = buildResponseWithoutUsage("gpt-4o", ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("gpt-4o");
        assertThat(event.getProvider()).isEqualTo("openai");
        assertThat(event.getOperationType()).isEqualTo("CHAT");
    }

    // --- Provider string is lowercase ---

    @Test
    void extractWithOpenAiProviderReturnsLowercaseProviderString() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getProvider()).isEqualTo("openai");
    }

    @Test
    void extractWithAzureProviderReturnsLowercaseProviderString() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("my-gpt4o-deployment", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.AZURE, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getProvider()).isEqualTo("azure");
    }

    @Test
    void extractWithOllamaProviderReturnsLowercaseProviderString() {
        Response response = buildResponseWithoutUsage("llama3", ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OLLAMA, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getProvider()).isEqualTo("ollama");
    }

    // --- Azure model resolution ---

    @Test
    void extractWithAzureProviderResolvesDeploymentNameToCanonicalModel() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("my-gpt4o-deployment", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.AZURE, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void extractWithOpenAiProviderPassesThroughModelNameUnchanged() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("gpt-4o");
    }

    // --- UsageMetadata mapping ---

    @Test
    void extractWithMetadataMapsAllFields() {
        Subscriber subscriber = Subscriber.builder()
                .id("sub-1")
                .email("user@example.com")
                .build();

        UsageMetadata metadata = UsageMetadata.builder()
                .traceId("trace-abc")
                .taskType("summarization")
                .organizationId("org-123")
                .subscriptionId("sub-plan-456")
                .productId("prod-789")
                .agent("my-agent")
                .responseQualityScore(0.95)
                .subscriber(subscriber)
                .build();

        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, metadata, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getTraceId()).isEqualTo("trace-abc");
        assertThat(event.getTaskType()).isEqualTo("summarization");
        assertThat(event.getOrganizationId()).isEqualTo("org-123");
        assertThat(event.getSubscriptionId()).isEqualTo("sub-plan-456");
        assertThat(event.getProductId()).isEqualTo("prod-789");
        assertThat(event.getAgent()).isEqualTo("my-agent");
        assertThat(event.getResponseQualityScore()).isEqualTo(0.95);
        assertThat(event.getSubscriber()).isSameAs(subscriber);
    }

    @Test
    void extractWithNullMetadataDoesNotThrowNpe() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event).isNotNull();
        assertThat(event.getTraceId()).isNull();
        assertThat(event.getTaskType()).isNull();
        assertThat(event.getOrganizationId()).isNull();
        assertThat(event.getSubscriber()).isNull();
    }

    // --- Timing fields ---

    @Test
    void extractSetsRequestAndResponseTime() {
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response response = buildResponseWithUsage("gpt-4o", usage, ResponseStatus.COMPLETED);

        MeteringEvent event = ResponseUsageExtractor.extract(
                response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getRequestTime()).isNotNull();
        assertThat(event.getResponseTime()).isNotNull();
    }
}
