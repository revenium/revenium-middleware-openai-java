package io.revenium.metering.openai.provider.extraction;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletion.Choice;
import com.openai.models.chat.completions.ChatCompletion.Choice.FinishReason;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.completions.CompletionUsage;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.Subscriber;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.Provider;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChatCompletionUsageExtractor}.
 *
 * TDD RED phase: tests written before the implementation class exists.
 * Covers all behaviors specified in 03-02-PLAN.md Task 1.
 */
class ChatCompletionUsageExtractorTest {

    private static final long REQUEST_TIME = 1_000_000L;
    private static final long RESPONSE_TIME = 1_001_500L; // 1500ms duration

    // --- Helpers ---

    private ChatCompletionMessage buildMessage() {
        return ChatCompletionMessage.builder()
                .content(Optional.of("Hello world"))
                .refusal(Optional.empty())
                .build();
    }

    private Choice buildChoice(FinishReason finishReason) {
        return Choice.builder()
                .finishReason(finishReason)
                .index(0L)
                .logprobs(Optional.empty())
                .message(buildMessage())
                .build();
    }

    private CompletionUsage buildUsage(long prompt, long completion, long total) {
        return CompletionUsage.builder()
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(total)
                .build();
    }

    private CompletionUsage buildUsageWithCacheAndReasoning(
            long prompt, long completion, long total, long cachedTokens, long reasoningTokens) {
        return CompletionUsage.builder()
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(total)
                .promptTokensDetails(CompletionUsage.PromptTokensDetails.builder()
                        .cachedTokens(cachedTokens)
                        .build())
                .completionTokensDetails(CompletionUsage.CompletionTokensDetails.builder()
                        .reasoningTokens(reasoningTokens)
                        .build())
                .build();
    }

    /** Serializes a MeteringEvent the same way the production MeteringClient does. */
    private String toJson(MeteringEvent event) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
        return mapper.writeValueAsString(event);
    }

    private ChatCompletion buildChatCompletionWithUsage(String model, CompletionUsage usage, FinishReason finishReason) {
        return ChatCompletion.builder()
                .id("chat-id-123")
                .model(model)
                .created(System.currentTimeMillis() / 1000)
                .addChoice(buildChoice(finishReason))
                .usage(usage)
                .build();
    }

    private ChatCompletion buildChatCompletionWithoutUsage(String model, FinishReason finishReason) {
        return ChatCompletion.builder()
                .id("chat-id-456")
                .model(model)
                .created(System.currentTimeMillis() / 1000)
                .addChoice(buildChoice(finishReason))
                .build();
    }

    private ChatCompletion buildChatCompletionEmptyChoices(String model) {
        return ChatCompletion.builder()
                .id("chat-id-789")
                .model(model)
                .created(System.currentTimeMillis() / 1000)
                .choices(java.util.Collections.emptyList())
                .build();
    }

    // --- Token extraction with usage present ---

    @Test
    void extractWithUsagePresentReturnsCorrectTokenCounts() {
        CompletionUsage usage = buildUsage(100L, 50L, 150L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getInputTokenCount()).isEqualTo(100L);
        assertThat(event.getOutputTokenCount()).isEqualTo(50L);
        assertThat(event.getTotalTokenCount()).isEqualTo(150L);
    }

    // --- Cache-read and reasoning token extraction (BACK-2413) ---

    @Test
    void extractWithCacheAndReasoningTokens_survivesIntoSerializedPayload() throws Exception {
        // Arrange: usage with non-zero cached and reasoning tokens
        CompletionUsage usage = buildUsageWithCacheAndReasoning(100L, 50L, 150L, 40L, 12L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        // Act
        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        // Assert: builder-level
        assertThat(event.getCacheReadTokenCount()).isEqualTo(40L);
        assertThat(event.getReasoningTokenCount()).isEqualTo(12L);
        // Cache-creation has no OpenAI equivalent — must stay null (see UsageDetailsExtractor)
        assertThat(event.getCacheCreationTokenCount()).isNull();

        // Assert: serialization-level — @JsonInclude(NON_NULL) silently drops null fields, so a
        // builder-only assertion would not catch a field that never reaches the wire payload.
        JsonNode node = new ObjectMapper().readTree(toJson(event));
        assertThat(node.has("cacheReadTokenCount")).isTrue();
        assertThat(node.get("cacheReadTokenCount").asLong()).isEqualTo(40L);
        assertThat(node.has("reasoningTokenCount")).isTrue();
        assertThat(node.get("reasoningTokenCount").asLong()).isEqualTo(12L);
        assertThat(node.has("cacheCreationTokenCount")).isFalse();
    }

    @Test
    void extractWithNoCacheOrReasoningDetails_omitsFieldsFromSerializedPayload() throws Exception {
        // Arrange: usage with no promptTokensDetails/completionTokensDetails at all
        CompletionUsage usage = buildUsage(100L, 50L, 150L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        // Act
        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        // Assert
        assertThat(event.getCacheReadTokenCount()).isNull();
        assertThat(event.getReasoningTokenCount()).isNull();
        JsonNode node = new ObjectMapper().readTree(toJson(event));
        assertThat(node.has("cacheReadTokenCount")).isFalse();
        assertThat(node.has("reasoningTokenCount")).isFalse();
    }

    @Test
    void extractWithUsagePresentReturnsCorrectModel() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void extractSetsOperationTypeToCHAT() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getOperationType()).isEqualTo("CHAT");
    }

    @Test
    void extractSetsIsStreamedFalse() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getIsStreamed()).isFalse();
    }

    @Test
    void extractComputesRequestDuration() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getRequestDuration()).isEqualTo(RESPONSE_TIME - REQUEST_TIME);
    }

    @Test
    void extractWithStopFinishReasonReturnsEndStopReason() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getStopReason()).isEqualTo("END");
    }

    @Test
    void extractWithLengthFinishReasonReturnsTokenLimitStopReason() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.LENGTH);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getStopReason()).isEqualTo("TOKEN_LIMIT");
    }

    // --- Provider string is lowercase ---

    @Test
    void extractWithOpenAiProviderReturnsLowercaseProviderString() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getProvider()).isEqualTo("openai");
    }

    @Test
    void extractWithAzureProviderReturnsLowercaseProviderString() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("my-gpt4o-deployment", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.AZURE, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getProvider()).isEqualTo("azure");
    }

    @Test
    void extractWithOllamaProviderReturnsLowercaseProviderString() {
        ChatCompletion response = buildChatCompletionWithoutUsage("llama3", FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OLLAMA, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getProvider()).isEqualTo("ollama");
    }

    // --- Azure model resolution ---

    @Test
    void extractWithAzureProviderResolvesDeploymentNameToCanonicalModel() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("my-gpt4o-deployment", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.AZURE, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void extractWithOpenAiProviderPassesThroughModelNameUnchanged() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("gpt-4o");
    }

    // --- Absent usage (Optional.empty()) ---

    @Test
    void extractWithAbsentUsageReturnsNullTokenCounts() {
        ChatCompletion response = buildChatCompletionWithoutUsage("llama3", FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OLLAMA, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getInputTokenCount()).isNull();
        assertThat(event.getOutputTokenCount()).isNull();
        assertThat(event.getTotalTokenCount()).isNull();
    }

    @Test
    void extractWithAbsentUsageDoesNotThrowNpe() {
        ChatCompletion response = buildChatCompletionWithoutUsage("llama3", FinishReason.STOP);

        // Must not throw any exception
        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OLLAMA, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event).isNotNull();
    }

    @Test
    void extractWithAbsentUsageStillReturnsValidEvent() {
        ChatCompletion response = buildChatCompletionWithoutUsage("llama3", FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OLLAMA, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getModel()).isEqualTo("llama3");
        assertThat(event.getProvider()).isEqualTo("ollama");
        assertThat(event.getOperationType()).isEqualTo("CHAT");
    }

    // --- Empty choices list ---

    @Test
    void extractWithEmptyChoicesListReturnsEndStopReason() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        // Build with empty choices list
        ChatCompletion response = ChatCompletion.builder()
                .id("chat-id-empty")
                .model("gpt-4o")
                .created(System.currentTimeMillis() / 1000)
                .choices(java.util.Collections.emptyList())
                .usage(usage)
                .build();

        // Should not throw; null finishReason maps to END per StopReason.fromFinishReason(null)
        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getStopReason()).isEqualTo("END");
    }

    @Test
    void extractWithEmptyChoicesListDoesNotThrowNpe() {
        ChatCompletion response = ChatCompletion.builder()
                .id("chat-id-empty")
                .model("gpt-4o")
                .created(System.currentTimeMillis() / 1000)
                .choices(java.util.Collections.emptyList())
                .build();

        assertThat(ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME)).isNotNull();
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
                .organizationName("org-123")
                .subscriptionId("sub-plan-456")
                .productName("prod-789")
                .agent("my-agent")
                .responseQualityScore(0.95)
                .subscriber(subscriber)
                .build();

        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, metadata, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getTraceId()).isEqualTo("trace-abc");
        assertThat(event.getTaskType()).isEqualTo("summarization");
        assertThat(event.getOrganizationName()).isEqualTo("org-123");
        assertThat(event.getSubscriptionId()).isEqualTo("sub-plan-456");
        assertThat(event.getProductName()).isEqualTo("prod-789");
        assertThat(event.getAgent()).isEqualTo("my-agent");
        assertThat(event.getResponseQualityScore()).isEqualTo(0.95);
        assertThat(event.getSubscriber()).isSameAs(subscriber);
    }

    @Test
    void extractWithNullMetadataDoesNotThrowNpe() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event).isNotNull();
        assertThat(event.getTraceId()).isNull();
        assertThat(event.getTaskType()).isNull();
        assertThat(event.getOrganizationName()).isNull();
        assertThat(event.getSubscriber()).isNull();
    }

    // --- Timing fields ---

    @Test
    void extractSetsRequestAndResponseTime() {
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion response = buildChatCompletionWithUsage("gpt-4o", usage, FinishReason.STOP);

        MeteringEvent event = ChatCompletionUsageExtractor.extract(
                null, response, Provider.OPENAI, null, REQUEST_TIME, RESPONSE_TIME);

        assertThat(event.getRequestTime()).isNotNull();
        assertThat(event.getResponseTime()).isNotNull();
    }
}
