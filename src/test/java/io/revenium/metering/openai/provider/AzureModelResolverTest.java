package io.revenium.metering.openai.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AzureModelResolver}.
 *
 * Covers all deployment-name-to-canonical-model mapping behaviors specified in 03-01-PLAN.md.
 */
class AzureModelResolverTest {

    // GPT-4o variants

    @Test
    void resolveGpt4oDeployment() {
        assertThat(AzureModelResolver.resolve("my-gpt4o-deployment"))
                .isEqualTo("gpt-4o");
    }

    @Test
    void resolveGpt4oMiniDeployment() {
        assertThat(AzureModelResolver.resolve("gpt-4o-mini-deploy"))
                .isEqualTo("gpt-4o");
    }

    @Test
    void resolveCanonicalGpt4o() {
        // Canonical names should pass through correctly (they match the pattern and return canonical)
        assertThat(AzureModelResolver.resolve("gpt-4o"))
                .isEqualTo("gpt-4o");
    }

    // GPT-4 Turbo

    @Test
    void resolveGpt4TurboDeployment() {
        assertThat(AzureModelResolver.resolve("prod-gpt-4-turbo"))
                .isEqualTo("gpt-4-turbo");
    }

    // GPT-4 (non-o)

    @Test
    void resolveGpt4Deployment() {
        assertThat(AzureModelResolver.resolve("gpt4"))
                .isEqualTo("gpt-4");
    }

    // GPT-3.5 Turbo

    @Test
    void resolveGpt35TurboDeployment() {
        assertThat(AzureModelResolver.resolve("my-gpt-3.5-turbo-v2"))
                .isEqualTo("gpt-3.5-turbo");
    }

    // Text embeddings large

    @Test
    void resolveTextEmbedding3Large() {
        assertThat(AzureModelResolver.resolve("text-embedding-3-large-prod"))
                .isEqualTo("text-embedding-3-large");
    }

    // Text embeddings small

    @Test
    void resolveTextEmbedding3Small() {
        assertThat(AzureModelResolver.resolve("text-embedding-3-small"))
                .isEqualTo("text-embedding-3-small");
    }

    // Ada embedding

    @Test
    void resolveAdaEmbedding() {
        assertThat(AzureModelResolver.resolve("ada-embedding-v1"))
                .isEqualTo("text-embedding-ada-002");
    }

    // Passthrough cases

    @Test
    void resolveUnknownDeploymentPassthrough() {
        assertThat(AzureModelResolver.resolve("unknown-deployment"))
                .isEqualTo("unknown-deployment");
    }

    @Test
    void resolveNullPassthrough() {
        assertThat(AzureModelResolver.resolve(null))
                .isNull();
    }

    @Test
    void resolveEmptyStringPassthrough() {
        assertThat(AzureModelResolver.resolve(""))
                .isEqualTo("");
    }
}
