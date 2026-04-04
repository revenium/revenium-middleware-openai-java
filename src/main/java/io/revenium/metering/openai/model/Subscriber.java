package io.revenium.metering.openai.model;

/**
 * Immutable value object representing subscriber identity information.
 *
 * <p>Nested within {@link UsageMetadata} to carry subscriber context alongside metered calls.
 * All fields are optional. Use {@link #builder()} to construct instances.
 */
public final class Subscriber {

    private final String id;
    private final String email;
    private final Credential credential;

    private Subscriber(Builder builder) {
        this.id = builder.id;
        this.email = builder.email;
        this.credential = builder.credential;
    }

    /** Returns the subscriber ID, or null if not set. */
    public String id() {
        return id;
    }

    /** Returns the subscriber email address, or null if not set. */
    public String email() {
        return email;
    }

    /** Returns the subscriber's credential, or null if not set. */
    public Credential credential() {
        return credential;
    }

    /** Returns a new {@link Builder} for constructing a {@link Subscriber} instance. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link Subscriber}. All fields are optional. */
    public static final class Builder {

        private String id;
        private String email;
        private Credential credential;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder credential(Credential credential) {
            this.credential = credential;
            return this;
        }

        public Subscriber build() {
            return new Subscriber(this);
        }
    }
}
