package io.revenium.metering.openai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable configuration for the Revenium middleware.
 *
 * <p>Create instances via the builder:
 * <pre>{@code
 * ReveniumConfig config = ReveniumConfig.builder()
 *     .apiKey("your-api-key")
 *     .build();
 * }</pre>
 *
 * <p>The builder automatically reads {@code REVENIUM_METERING_API_KEY} and
 * {@code REVENIUM_METERING_BASE_URL} environment variables on construction.
 * Programmatic values set via the builder override any env var values.
 */
public final class ReveniumConfig {

    private static final Logger log = LoggerFactory.getLogger(ReveniumConfig.class);

    private final String apiKey;
    private final String baseUrl;

    private ReveniumConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
    }

    /**
     * Returns the Revenium metering API key.
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * Returns the Revenium metering base URL.
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Creates a new builder instance. Env vars are read immediately upon builder construction.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link ReveniumConfig}. Env vars are read on construction;
     * explicit setter calls override env var values (D-03).
     */
    public static final class Builder {

        private String apiKey;
        private String baseUrl = "https://api.revenium.ai";

        private Builder() {
            // Auto-read env vars on construction (D-02)
            // Programmatic calls to apiKey()/baseUrl() will override these values (D-03)
            String envKey = System.getenv("REVENIUM_METERING_API_KEY");
            String envUrl = System.getenv("REVENIUM_METERING_BASE_URL");
            if (envKey != null && !envKey.isEmpty()) {
                this.apiKey = envKey;
            }
            if (envUrl != null && !envUrl.isEmpty()) {
                this.baseUrl = envUrl;
            }
        }

        /**
         * Sets the Revenium metering API key, overriding any env var value.
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the Revenium metering base URL, overriding any env var value.
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Builds the {@link ReveniumConfig}.
         *
         * @throws IllegalStateException if no API key was provided via env var or programmatic call
         */
        public ReveniumConfig build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException(
                        "API key is required. Set REVENIUM_METERING_API_KEY or call .apiKey(\"...\")");
            }
            return new ReveniumConfig(this);
        }
    }
}
