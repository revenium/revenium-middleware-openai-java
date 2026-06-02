package io.revenium.metering.openai.model;

/**
 * Immutable value object carrying business context alongside metered OpenAI API calls.
 *
 * <p>All fields are optional — calls without metadata still meter successfully (META-03).
 * Pass a {@link UsageMetadata} instance to wrapper methods to include business context
 * in Revenium metering payloads (D-08).
 *
 * <p>Use {@link #builder()} to construct instances via the typed builder API (D-06).
 *
 * <pre>{@code
 * UsageMetadata meta = UsageMetadata.builder()
 *     .traceId("trace-123")
 *     .taskType("summarization")
 *     .subscriber(Subscriber.builder()
 *         .id("user-1")
 *         .email("user@example.com")
 *         .build())
 *     .build();
 * }</pre>
 */
public final class UsageMetadata {

    private final String traceId;
    private final String taskType;
    private final Subscriber subscriber;
    private final String organizationName;
    private final String subscriptionId;
    private final String productName;
    private final String agent;
    private final Double responseQualityScore;

    private UsageMetadata(Builder builder) {
        this.traceId = builder.traceId;
        this.taskType = builder.taskType;
        this.subscriber = builder.subscriber;
        this.organizationName = builder.organizationName;
        this.subscriptionId = builder.subscriptionId;
        this.productName = builder.productName;
        this.agent = builder.agent;
        this.responseQualityScore = builder.responseQualityScore;
    }

    /** Returns the trace ID for distributed tracing, or null if not set. */
    public String traceId() {
        return traceId;
    }

    /** Returns the task type label (e.g., "summarization", "translation"), or null if not set. */
    public String taskType() {
        return taskType;
    }

    /** Returns the subscriber context, or null if not set. */
    public Subscriber subscriber() {
        return subscriber;
    }

    /** Returns the organization name, or null if not set. */
    public String organizationName() {
        return organizationName;
    }

    /** Returns the subscription ID, or null if not set. */
    public String subscriptionId() {
        return subscriptionId;
    }

    /** Returns the product name, or null if not set. */
    public String productName() {
        return productName;
    }

    /** Returns the agent name or identifier, or null if not set. */
    public String agent() {
        return agent;
    }

    /**
     * Returns the response quality score (0.0 to 1.0), or null if not provided.
     * Uses boxed {@link Double} to allow null to represent "not provided" (D-09).
     */
    public Double responseQualityScore() {
        return responseQualityScore;
    }

    /** Returns a new {@link Builder} for constructing a {@link UsageMetadata} instance. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link UsageMetadata}. All fields are optional — {@link #build()} never throws. */
    public static final class Builder {

        private String traceId;
        private String taskType;
        private Subscriber subscriber;
        private String organizationName;
        private String subscriptionId;
        private String productName;
        private String agent;
        private Double responseQualityScore;

        private Builder() {}

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder taskType(String taskType) {
            this.taskType = taskType;
            return this;
        }

        public Builder subscriber(Subscriber subscriber) {
            this.subscriber = subscriber;
            return this;
        }

        public Builder organizationName(String organizationName) {
            this.organizationName = organizationName;
            return this;
        }

        public Builder subscriptionId(String subscriptionId) {
            this.subscriptionId = subscriptionId;
            return this;
        }

        public Builder productName(String productName) {
            this.productName = productName;
            return this;
        }

        public Builder agent(String agent) {
            this.agent = agent;
            return this;
        }

        public Builder responseQualityScore(Double responseQualityScore) {
            this.responseQualityScore = responseQualityScore;
            return this;
        }

        /** Builds an immutable {@link UsageMetadata} with all set fields. Never throws. */
        public UsageMetadata build() {
            return new UsageMetadata(this);
        }
    }
}
