package io.revenium.metering.openai.config;

import org.assertj.core.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReveniumConfigTest {

    @Test
    void programmaticApiKeyIsReturned() {
        ReveniumConfig config = ReveniumConfig.builder()
                .apiKey("test-key")
                .build();
        assertThat(config.apiKey()).isEqualTo("test-key");
    }

    @Test
    void defaultBaseUrl() {
        ReveniumConfig config = ReveniumConfig.builder()
                .apiKey("k")
                .build();
        assertThat(config.baseUrl()).isEqualTo("https://api.revenium.ai");
    }

    @Test
    void customBaseUrl() {
        ReveniumConfig config = ReveniumConfig.builder()
                .apiKey("k")
                .baseUrl("https://custom.example.com")
                .build();
        assertThat(config.baseUrl()).isEqualTo("https://custom.example.com");
    }

    @Test
    void missingApiKeyThrowsIllegalStateException() {
        // Guard against CI environments where the env var is set
        Assumptions.assumeThat(System.getenv("REVENIUM_METERING_API_KEY")).isNull();
        assertThatThrownBy(() -> ReveniumConfig.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API key is required");
    }

    @Test
    void configClassIsFinal() {
        assertThat(Modifier.isFinal(ReveniumConfig.class.getModifiers())).isTrue();
    }

    @Test
    void noPublicSetters() {
        boolean hasSetters = Arrays.stream(ReveniumConfig.class.getMethods())
                .filter(m -> m.getName().startsWith("set"))
                .findAny()
                .isPresent();
        assertThat(hasSetters).isFalse();
    }

    @Test
    void programmaticApiKeyOverridesEnvVar() {
        ReveniumConfig config = ReveniumConfig.builder()
                .apiKey("explicit")
                .build();
        assertThat(config.apiKey()).isEqualTo("explicit");
    }
}
