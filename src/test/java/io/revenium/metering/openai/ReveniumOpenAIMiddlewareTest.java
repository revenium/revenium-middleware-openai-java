package io.revenium.metering.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.services.async.EmbeddingServiceAsync;
import com.openai.services.async.ResponseServiceAsync;
import com.openai.services.async.ChatServiceAsync;
import com.openai.services.async.chat.ChatCompletionServiceAsync;
import com.openai.services.blocking.EmbeddingService;
import com.openai.services.blocking.ResponseService;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import io.revenium.metering.openai.config.ReveniumConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReveniumOpenAIMiddleware} factory class.
 * Verifies wrap() overloads produce working containers and the class is a proper utility class.
 */
@ExtendWith(MockitoExtension.class)
class ReveniumOpenAIMiddlewareTest {

    @Mock
    private OpenAIClient mockClient;

    @Mock
    private ChatService mockChatService;

    @Mock
    private ChatCompletionService mockChatCompletionService;

    @Mock
    private OpenAIClientAsync mockAsyncClient;

    @Mock
    private ChatServiceAsync mockChatServiceAsync;

    @Mock
    private ChatCompletionServiceAsync mockChatCompletionServiceAsync;

    @Mock
    private EmbeddingService mockEmbeddingService;

    @Mock
    private EmbeddingServiceAsync mockEmbeddingServiceAsync;

    @Mock
    private ResponseService mockResponseService;

    @Mock
    private ResponseServiceAsync mockResponseServiceAsync;

    private ReveniumConfig config;

    @BeforeEach
    void setUp() {
        // Use lenient() so tests that don't call wrap() don't trigger unnecessary-stubbing errors
        lenient().when(mockClient.chat()).thenReturn(mockChatService);
        lenient().when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        lenient().when(mockClient.async()).thenReturn(mockAsyncClient);
        lenient().when(mockAsyncClient.chat()).thenReturn(mockChatServiceAsync);
        lenient().when(mockChatServiceAsync.completions()).thenReturn(mockChatCompletionServiceAsync);
        lenient().when(mockClient.embeddings()).thenReturn(mockEmbeddingService);
        lenient().when(mockAsyncClient.embeddings()).thenReturn(mockEmbeddingServiceAsync);
        lenient().when(mockClient.responses()).thenReturn(mockResponseService);
        lenient().when(mockAsyncClient.responses()).thenReturn(mockResponseServiceAsync);

        config = ReveniumConfig.builder()
                .apiKey("test-api-key")
                .baseUrl("https://api.openai.com")
                .build();
    }

    // -----------------------------------------------------------------------
    // Test 10: wrap(client, config) returns non-null ReveniumInstrumentedClient
    // -----------------------------------------------------------------------

    @Test
    void wrap_withClientAndConfig_returnsNonNullContainer() {
        ReveniumInstrumentedClient container = ReveniumOpenAIMiddleware.wrap(mockClient, config);
        assertThat(container).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Test 11: wrap(client, config) returned container's chatCompletions() is not null
    // -----------------------------------------------------------------------

    @Test
    void wrap_withClientAndConfig_containerHasNonNullChatCompletions() {
        ReveniumInstrumentedClient container = ReveniumOpenAIMiddleware.wrap(mockClient, config);
        assertThat(container.chatCompletions()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Test 12: wrap(client) env-var overload returns non-null ReveniumInstrumentedClient
    // -----------------------------------------------------------------------

    @Test
    void wrap_withClientOnly_usesEnvVarConfig_returnsNonNullContainer() {
        // This test requires REVENIUM_METERING_API_KEY env var or falls back to
        // a config that reads env vars. We provide a config via explicit builder
        // to simulate the env-var overload behavior by using a system property workaround.
        // The wrap(client) overload calls ReveniumConfig.builder().build() which reads env vars.
        // We use assumeThat to guard against missing env var.
        String apiKey = System.getenv("REVENIUM_METERING_API_KEY");
        org.assertj.core.api.Assumptions.assumeThat(apiKey)
                .as("REVENIUM_METERING_API_KEY env var must be set for this test")
                .isNotNull()
                .isNotEmpty();

        ReveniumInstrumentedClient container = ReveniumOpenAIMiddleware.wrap(mockClient);
        assertThat(container).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Test 13: ReveniumOpenAIMiddleware has private constructor (utility class)
    // -----------------------------------------------------------------------

    @Test
    void reveniumOpenAIMiddleware_hasPrivateConstructor() throws Exception {
        Constructor<ReveniumOpenAIMiddleware> constructor =
                ReveniumOpenAIMiddleware.class.getDeclaredConstructor();
        assertThat(constructor.canAccess(null)).isFalse();
        constructor.setAccessible(true);
        assertThatThrownBy(constructor::newInstance)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
