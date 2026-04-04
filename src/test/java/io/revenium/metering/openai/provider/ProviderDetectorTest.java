package io.revenium.metering.openai.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProviderDetector}.
 *
 * Covers all URL detection behaviors specified in 03-01-PLAN.md:
 * OPENAI, AZURE, OLLAMA detection and null/empty/unknown defaults.
 */
class ProviderDetectorTest {

    @Test
    void detectOpenAiUrl() {
        assertThat(ProviderDetector.detect("https://api.openai.com/v1"))
                .isEqualTo(Provider.OPENAI);
    }

    @Test
    void detectAzureUrl() {
        assertThat(ProviderDetector.detect("https://my-resource.openai.azure.com/openai/deployments/gpt4/"))
                .isEqualTo(Provider.AZURE);
    }

    @Test
    void detectAzureUrlShort() {
        assertThat(ProviderDetector.detect("https://my-res.openai.azure.com/openai/v1/"))
                .isEqualTo(Provider.AZURE);
    }

    @Test
    void detectOllamaLocalhost() {
        assertThat(ProviderDetector.detect("http://localhost:11434/v1"))
                .isEqualTo(Provider.OLLAMA);
    }

    @Test
    void detectOllamaLoopback() {
        assertThat(ProviderDetector.detect("http://127.0.0.1:11434/v1"))
                .isEqualTo(Provider.OLLAMA);
    }

    @Test
    void detectUnknownUrlDefaultsToOpenAi() {
        assertThat(ProviderDetector.detect("https://unknown-gateway.example.com"))
                .isEqualTo(Provider.OPENAI);
    }

    @Test
    void detectNullDefaultsToOpenAi() {
        assertThat(ProviderDetector.detect(null))
                .isEqualTo(Provider.OPENAI);
    }

    @Test
    void detectEmptyStringDefaultsToOpenAi() {
        assertThat(ProviderDetector.detect(""))
                .isEqualTo(Provider.OPENAI);
    }

    @Test
    void detectCaseInsensitiveOpenAi() {
        assertThat(ProviderDetector.detect("https://API.OPENAI.COM/v1"))
                .isEqualTo(Provider.OPENAI);
    }

    @Test
    void detectCaseInsensitiveAzure() {
        assertThat(ProviderDetector.detect("https://MY-RESOURCE.OPENAI.AZURE.COM/openai/v1/"))
                .isEqualTo(Provider.AZURE);
    }

    @Test
    void detectCaseInsensitiveOllama() {
        assertThat(ProviderDetector.detect("http://LOCALHOST:11434/v1"))
                .isEqualTo(Provider.OLLAMA);
    }

    @Test
    void providerEnumHasExactlyThreeValues() {
        // Verify enum has exactly OPENAI, AZURE, OLLAMA per D-04
        Provider[] values = Provider.values();
        assertThat(values).hasSize(3);
        assertThat(values).containsExactlyInAnyOrder(Provider.OPENAI, Provider.AZURE, Provider.OLLAMA);
    }
}
