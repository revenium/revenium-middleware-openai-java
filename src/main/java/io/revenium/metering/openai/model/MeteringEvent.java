package io.revenium.metering.openai.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Jackson-annotated POJO representing the Revenium AI metering API payload.
 *
 * <p>Serializes to JSON with camelCase field names. Null fields are omitted from JSON output
 * via {@code @JsonInclude(NON_NULL)}. The {@code middlewareSource} field is always "JAVA"
 * and {@code costType} is always "AI" — these are hardcoded constants, not builder fields.
 *
 * <p>The {@code isStreamed} boolean field serializes with the key "isStreamed" (not "streamed")
 * because Jackson uses the private field name directly (via field-based ObjectMapper config).
 *
 * <p>Use {@link #builder()} to construct instances. {@code model} is required —
 * {@link Builder#build()} throws {@link NullPointerException} if model is null.
 *
 * <p>Configure ObjectMapper for field-based visibility to serialize nested Phase 1 objects
 * (Subscriber, Credential) that use non-JavaBean accessor methods:
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper();
 * mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
 * mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
 * mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class MeteringEvent {

    // Required
    private final String model;

    // Token counts
    private final Long inputTokenCount;
    private final Long outputTokenCount;
    private final Long totalTokenCount;
    private final Long cacheCreationTokenCount;
    private final Long cacheReadTokenCount;
    private final Long reasoningTokenCount;

    // Completion metadata
    private final String stopReason;
    private final String provider;
    private final String modelSource;
    private final String operationType;
    private final String transactionId;

    // Timing (ISO 8601 strings per API contract)
    private final String requestTime;
    private final String responseTime;
    private final String completionStartTime;
    private final Long requestDuration;

    // Streaming
    private final Boolean isStreamed;
    private final Long timeToFirstToken;

    // Prompt/response content (opt-in on Revenium side — silently dropped if not enabled)
    private final String inputMessages;
    private final String systemPrompt;
    private final String outputResponse;

    // Hardcoded constants (D-04, D-05)
    private final String middlewareSource;
    private final String costType;

    // Business context from UsageMetadata
    private final String traceId;
    private final String taskType;
    private final String organizationName;
    private final String subscriptionId;
    private final String productName;
    private final String agent;
    private final Double responseQualityScore;

    // Nested subscriber identity
    private final Subscriber subscriber;

    private MeteringEvent(Builder builder) {
        this.model = builder.model;
        this.inputTokenCount = builder.inputTokenCount;
        this.outputTokenCount = builder.outputTokenCount;
        this.totalTokenCount = builder.totalTokenCount;
        this.cacheCreationTokenCount = builder.cacheCreationTokenCount;
        this.cacheReadTokenCount = builder.cacheReadTokenCount;
        this.reasoningTokenCount = builder.reasoningTokenCount;
        this.stopReason = builder.stopReason;
        this.provider = builder.provider;
        this.modelSource = builder.modelSource;
        this.operationType = builder.operationType;
        this.transactionId = builder.transactionId;
        this.requestTime = builder.requestTime;
        this.responseTime = builder.responseTime;
        this.completionStartTime = builder.completionStartTime;
        this.requestDuration = builder.requestDuration;
        this.isStreamed = builder.isStreamed;
        this.timeToFirstToken = builder.timeToFirstToken;
        this.inputMessages = builder.inputMessages;
        this.systemPrompt = builder.systemPrompt;
        this.outputResponse = builder.outputResponse;
        this.middlewareSource = "JAVA";
        this.costType = "AI";
        this.traceId = builder.traceId;
        this.taskType = builder.taskType;
        this.organizationName = builder.organizationName;
        this.subscriptionId = builder.subscriptionId;
        this.productName = builder.productName;
        this.agent = builder.agent;
        this.responseQualityScore = builder.responseQualityScore;
        this.subscriber = builder.subscriber;
    }

    public String getModel() { return model; }
    public Long getInputTokenCount() { return inputTokenCount; }
    public Long getOutputTokenCount() { return outputTokenCount; }
    public Long getTotalTokenCount() { return totalTokenCount; }
    public Long getCacheCreationTokenCount() { return cacheCreationTokenCount; }
    public Long getCacheReadTokenCount() { return cacheReadTokenCount; }
    public Long getReasoningTokenCount() { return reasoningTokenCount; }
    public String getStopReason() { return stopReason; }
    public String getProvider() { return provider; }
    public String getModelSource() { return modelSource; }
    public String getOperationType() { return operationType; }
    public String getTransactionId() { return transactionId; }
    public String getRequestTime() { return requestTime; }
    public String getResponseTime() { return responseTime; }
    public String getCompletionStartTime() { return completionStartTime; }
    public Long getRequestDuration() { return requestDuration; }
    public Boolean getIsStreamed() { return isStreamed; }
    public Long getTimeToFirstToken() { return timeToFirstToken; }
    public String getInputMessages() { return inputMessages; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getOutputResponse() { return outputResponse; }
    public String getMiddlewareSource() { return middlewareSource; }
    public String getCostType() { return costType; }
    public String getTraceId() { return traceId; }
    public String getTaskType() { return taskType; }
    public String getOrganizationName() { return organizationName; }
    public String getSubscriptionId() { return subscriptionId; }
    public String getProductName() { return productName; }
    public String getAgent() { return agent; }
    public Double getResponseQualityScore() { return responseQualityScore; }
    public Subscriber getSubscriber() { return subscriber; }

    /** Returns a new {@link Builder} for constructing a {@link MeteringEvent}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link MeteringEvent}.
     * {@link #model} is required — {@link #build()} throws {@link NullPointerException} if null.
     * {@code middlewareSource} and {@code costType} are not settable; they are hardcoded constants.
     */
    public static final class Builder {

        private String model;
        private Long inputTokenCount;
        private Long outputTokenCount;
        private Long totalTokenCount;
        private Long cacheCreationTokenCount;
        private Long cacheReadTokenCount;
        private Long reasoningTokenCount;
        private String stopReason;
        private String provider;
        private String modelSource;
        private String operationType;
        private String transactionId;
        private String requestTime;
        private String responseTime;
        private String completionStartTime;
        private Long requestDuration;
        private Boolean isStreamed;
        private Long timeToFirstToken;
        private String inputMessages;
        private String systemPrompt;
        private String outputResponse;
        private String traceId;
        private String taskType;
        private String organizationName;
        private String subscriptionId;
        private String productName;
        private String agent;
        private Double responseQualityScore;
        private Subscriber subscriber;

        private Builder() {}

        public Builder model(String model) { this.model = model; return this; }
        public Builder inputTokenCount(Long inputTokenCount) { this.inputTokenCount = inputTokenCount; return this; }
        public Builder outputTokenCount(Long outputTokenCount) { this.outputTokenCount = outputTokenCount; return this; }
        public Builder totalTokenCount(Long totalTokenCount) { this.totalTokenCount = totalTokenCount; return this; }
        public Builder cacheCreationTokenCount(Long cacheCreationTokenCount) { this.cacheCreationTokenCount = cacheCreationTokenCount; return this; }
        public Builder cacheReadTokenCount(Long cacheReadTokenCount) { this.cacheReadTokenCount = cacheReadTokenCount; return this; }
        public Builder reasoningTokenCount(Long reasoningTokenCount) { this.reasoningTokenCount = reasoningTokenCount; return this; }
        public Builder stopReason(String stopReason) { this.stopReason = stopReason; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder modelSource(String modelSource) { this.modelSource = modelSource; return this; }
        public Builder operationType(String operationType) { this.operationType = operationType; return this; }
        public Builder transactionId(String transactionId) { this.transactionId = transactionId; return this; }
        public Builder requestTime(String requestTime) { this.requestTime = requestTime; return this; }
        public Builder responseTime(String responseTime) { this.responseTime = responseTime; return this; }
        public Builder completionStartTime(String completionStartTime) { this.completionStartTime = completionStartTime; return this; }
        public Builder requestDuration(Long requestDuration) { this.requestDuration = requestDuration; return this; }
        /** Convenience: convert epoch millis to ISO 8601 UTC string for requestTime. */
        public Builder requestTimeMillis(long epochMillis) { this.requestTime = millisToIso(epochMillis); return this; }
        /** Convenience: convert epoch millis to ISO 8601 UTC string for responseTime. */
        public Builder responseTimeMillis(long epochMillis) { this.responseTime = millisToIso(epochMillis); return this; }
        /** Convenience: convert epoch millis to ISO 8601 UTC string for completionStartTime. */
        public Builder completionStartTimeMillis(long epochMillis) { this.completionStartTime = millisToIso(epochMillis); return this; }
        public Builder isStreamed(Boolean isStreamed) { this.isStreamed = isStreamed; return this; }
        public Builder timeToFirstToken(Long timeToFirstToken) { this.timeToFirstToken = timeToFirstToken; return this; }
        public Builder inputMessages(String inputMessages) { this.inputMessages = inputMessages; return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder outputResponse(String outputResponse) { this.outputResponse = outputResponse; return this; }
        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder taskType(String taskType) { this.taskType = taskType; return this; }
        public Builder organizationName(String organizationName) { this.organizationName = organizationName; return this; }
        public Builder subscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; return this; }
        public Builder productName(String productName) { this.productName = productName; return this; }
        public Builder agent(String agent) { this.agent = agent; return this; }
        public Builder responseQualityScore(Double responseQualityScore) { this.responseQualityScore = responseQualityScore; return this; }
        public Builder subscriber(Subscriber subscriber) { this.subscriber = subscriber; return this; }

        /**
         * Builds an immutable {@link MeteringEvent}.
         *
         * @throws NullPointerException if model is null
         */
        public MeteringEvent build() {
            Objects.requireNonNull(model, "model is required");
            return new MeteringEvent(this);
        }

        private static String millisToIso(long epochMillis) {
            return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
        }
    }
}
