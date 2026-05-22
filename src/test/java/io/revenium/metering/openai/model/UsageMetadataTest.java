package io.revenium.metering.openai.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class UsageMetadataTest {

    @Test
    void emptyMetadataBuildsSuccessfully() {
        UsageMetadata meta = UsageMetadata.builder().build();
        assertThat(meta).isNotNull();
    }

    @Test
    void emptyMetadataHasAllNullFields() {
        UsageMetadata meta = UsageMetadata.builder().build();
        assertThat(meta.traceId()).isNull();
        assertThat(meta.taskType()).isNull();
        assertThat(meta.subscriber()).isNull();
        assertThat(meta.organizationName()).isNull();
        assertThat(meta.subscriptionId()).isNull();
        assertThat(meta.productName()).isNull();
        assertThat(meta.agent()).isNull();
        assertThat(meta.responseQualityScore()).isNull();
    }

    @Test
    void allFieldsSetAndRetrievable() {
        UsageMetadata meta = UsageMetadata.builder()
            .traceId("trace-123")
            .taskType("summarization")
            .organizationName("org-abc")
            .subscriptionId("sub-xyz")
            .productName("prod-1")
            .agent("agent-name")
            .responseQualityScore(0.95)
            .subscriber(Subscriber.builder()
                .id("user-1")
                .email("user@example.com")
                .credential(Credential.builder()
                    .name("api-key-name")
                    .value("credential-value")
                    .build())
                .build())
            .build();

        assertThat(meta.traceId()).isEqualTo("trace-123");
        assertThat(meta.taskType()).isEqualTo("summarization");
        assertThat(meta.organizationName()).isEqualTo("org-abc");
        assertThat(meta.subscriptionId()).isEqualTo("sub-xyz");
        assertThat(meta.productName()).isEqualTo("prod-1");
        assertThat(meta.agent()).isEqualTo("agent-name");
        assertThat(meta.responseQualityScore()).isEqualTo(0.95);
        assertThat(meta.subscriber().id()).isEqualTo("user-1");
        assertThat(meta.subscriber().email()).isEqualTo("user@example.com");
        assertThat(meta.subscriber().credential().name()).isEqualTo("api-key-name");
        assertThat(meta.subscriber().credential().value()).isEqualTo("credential-value");
    }

    @Test
    void usageMetadataClassIsFinal() {
        assertThat(Modifier.isFinal(UsageMetadata.class.getModifiers())).isTrue();
    }

    @Test
    void subscriberClassIsFinal() {
        assertThat(Modifier.isFinal(Subscriber.class.getModifiers())).isTrue();
    }

    @Test
    void credentialClassIsFinal() {
        assertThat(Modifier.isFinal(Credential.class.getModifiers())).isTrue();
    }

    @Test
    void noPublicSettersOnUsageMetadata() {
        boolean hasSetters = Arrays.stream(UsageMetadata.class.getMethods())
            .anyMatch(m -> m.getName().startsWith("set"));
        assertThat(hasSetters).isFalse();
    }
}
