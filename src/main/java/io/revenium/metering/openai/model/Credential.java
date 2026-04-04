package io.revenium.metering.openai.model;

/**
 * Immutable value object representing a subscriber's API credential.
 *
 * <p>Nested within {@link Subscriber} as part of the {@link UsageMetadata} hierarchy.
 * All fields are optional. Use {@link #builder()} to construct instances.
 */
public final class Credential {

    private final String name;
    private final String value;

    private Credential(Builder builder) {
        this.name = builder.name;
        this.value = builder.value;
    }

    /** Returns the credential name (e.g., API key name), or null if not set. */
    public String name() {
        return name;
    }

    /** Returns the credential value (e.g., the API key itself), or null if not set. */
    public String value() {
        return value;
    }

    /** Returns a new {@link Builder} for constructing a {@link Credential} instance. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link Credential}. All fields are optional. */
    public static final class Builder {

        private String name;
        private String value;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder value(String value) {
            this.value = value;
            return this;
        }

        public Credential build() {
            return new Credential(this);
        }
    }
}
