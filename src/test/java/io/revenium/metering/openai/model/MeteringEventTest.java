package io.revenium.metering.openai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MeteringEvent} JSON serialization behavior.
 */
class MeteringEventTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
    }

    @Test
    void allFieldsSerializeToCorrectJsonKeys() throws Exception {
        Subscriber subscriber = Subscriber.builder()
                .id("sub-123")
                .email("user@example.com")
                .credential(Credential.builder().name("apiKey").value("secret-key").build())
                .build();

        MeteringEvent event = MeteringEvent.builder()
                .model("gpt-4o")
                .inputTokenCount(100L)
                .outputTokenCount(200L)
                .totalTokenCount(300L)
                .cacheCreationTokenCount(50L)
                .stopReason("END")
                .provider("OPENAI")
                .modelSource("openai")
                .operationType("chat_completion")
                .transactionId("tx-abc")
                .requestTimeMillis(1700000000000L)
                .responseTimeMillis(1700000001000L)
                .completionStartTimeMillis(1700000001000L)
                .requestDuration(1000L)
                .isStreamed(false)
                .timeToFirstToken(100L)
                .traceId("trace-123")
                .taskType("summarization")
                .organizationName("org-1")
                .subscriptionId("sub-1")
                .productName("prod-1")
                .agent("myagent")
                .responseQualityScore(0.95)
                .subscriber(subscriber)
                .build();

        String json = mapper.writeValueAsString(event);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("model")).isTrue();
        assertThat(node.get("model").asText()).isEqualTo("gpt-4o");
        assertThat(node.has("inputTokenCount")).isTrue();
        assertThat(node.has("outputTokenCount")).isTrue();
        assertThat(node.has("totalTokenCount")).isTrue();
        assertThat(node.has("cacheCreationTokenCount")).isTrue();
        assertThat(node.has("stopReason")).isTrue();
        assertThat(node.has("provider")).isTrue();
        assertThat(node.has("modelSource")).isTrue();
        assertThat(node.has("operationType")).isTrue();
        assertThat(node.has("transactionId")).isTrue();
        assertThat(node.has("requestTime")).isTrue();
        assertThat(node.get("requestTime").asText()).startsWith("2023-11-14T");
        assertThat(node.has("responseTime")).isTrue();
        assertThat(node.has("completionStartTime")).isTrue();
        assertThat(node.has("requestDuration")).isTrue();
        assertThat(node.has("isStreamed")).isTrue();
        assertThat(node.has("timeToFirstToken")).isTrue();
        assertThat(node.has("middlewareSource")).isTrue();
        assertThat(node.has("costType")).isTrue();
        assertThat(node.has("traceId")).isTrue();
        assertThat(node.has("taskType")).isTrue();
        assertThat(node.has("organizationName")).isTrue();
        assertThat(node.has("subscriptionId")).isTrue();
        assertThat(node.has("productName")).isTrue();
        assertThat(node.has("agent")).isTrue();
        assertThat(node.has("responseQualityScore")).isTrue();
        assertThat(node.has("subscriber")).isTrue();
    }

    @Test
    void nullFieldsOmittedFromJson() throws Exception {
        MeteringEvent event = MeteringEvent.builder()
                .model("gpt-4o")
                .stopReason("END")
                .build();

        String json = mapper.writeValueAsString(event);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("model")).isTrue();
        assertThat(node.has("stopReason")).isTrue();
        assertThat(node.has("middlewareSource")).isTrue();
        assertThat(node.has("costType")).isTrue();

        // Null fields must NOT be present
        assertThat(node.has("inputTokenCount")).isFalse();
        assertThat(node.has("outputTokenCount")).isFalse();
        assertThat(node.has("totalTokenCount")).isFalse();
        assertThat(node.has("cacheCreationTokenCount")).isFalse();
        assertThat(node.has("cacheReadTokenCount")).isFalse();
        assertThat(node.has("reasoningTokenCount")).isFalse();
        assertThat(node.has("completionStartTime")).isFalse();
        assertThat(node.has("provider")).isFalse();
        assertThat(node.has("modelSource")).isFalse();
        assertThat(node.has("operationType")).isFalse();
        assertThat(node.has("transactionId")).isFalse();
        assertThat(node.has("requestTime")).isFalse();
        assertThat(node.has("responseTime")).isFalse();
        assertThat(node.has("requestDuration")).isFalse();
        assertThat(node.has("isStreamed")).isFalse();
        assertThat(node.has("timeToFirstToken")).isFalse();
        assertThat(node.has("traceId")).isFalse();
        assertThat(node.has("taskType")).isFalse();
        assertThat(node.has("organizationName")).isFalse();
        assertThat(node.has("subscriptionId")).isFalse();
        assertThat(node.has("productName")).isFalse();
        assertThat(node.has("agent")).isFalse();
        assertThat(node.has("responseQualityScore")).isFalse();
        assertThat(node.has("subscriber")).isFalse();
    }

    @Test
    void isStreamedKeyPreservesIsPrefix() throws Exception {
        MeteringEvent event = MeteringEvent.builder()
                .model("gpt-4o")
                .isStreamed(true)
                .build();

        String json = mapper.writeValueAsString(event);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("isStreamed")).isTrue();
        assertThat(node.get("isStreamed").asBoolean()).isTrue();
        assertThat(node.has("streamed")).isFalse();
    }

    @Test
    void middlewareSourceIsJava() throws Exception {
        MeteringEvent event = MeteringEvent.builder()
                .model("gpt-4o")
                .build();

        assertThat(event.getMiddlewareSource()).isEqualTo("JAVA");

        String json = mapper.writeValueAsString(event);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("middlewareSource")).isTrue();
        assertThat(node.get("middlewareSource").asText()).isEqualTo("JAVA");
    }

    @Test
    void costTypeIsAI() throws Exception {
        MeteringEvent event = MeteringEvent.builder()
                .model("gpt-4o")
                .build();

        assertThat(event.getCostType()).isEqualTo("AI");

        String json = mapper.writeValueAsString(event);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("costType")).isTrue();
        assertThat(node.get("costType").asText()).isEqualTo("AI");
    }

    @Test
    void subscriberSerializesAsNestedObject() throws Exception {
        Subscriber subscriber = Subscriber.builder()
                .id("sub-456")
                .email("test@example.com")
                .credential(Credential.builder().name("keyName").value("keyValue").build())
                .build();

        MeteringEvent event = MeteringEvent.builder()
                .model("gpt-4o")
                .subscriber(subscriber)
                .build();

        String json = mapper.writeValueAsString(event);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("subscriber")).isTrue();
        JsonNode subscriberNode = node.get("subscriber");
        assertThat(subscriberNode.has("id")).isTrue();
        assertThat(subscriberNode.get("id").asText()).isEqualTo("sub-456");
        assertThat(subscriberNode.has("email")).isTrue();
        assertThat(subscriberNode.get("email").asText()).isEqualTo("test@example.com");
        assertThat(subscriberNode.has("credential")).isTrue();
        JsonNode credentialNode = subscriberNode.get("credential");
        assertThat(credentialNode.has("name")).isTrue();
        assertThat(credentialNode.get("name").asText()).isEqualTo("keyName");
        assertThat(credentialNode.has("value")).isTrue();
        assertThat(credentialNode.get("value").asText()).isEqualTo("keyValue");
    }

    @Test
    void modelIsRequired() {
        assertThatThrownBy(() -> MeteringEvent.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("model is required");
    }
}
