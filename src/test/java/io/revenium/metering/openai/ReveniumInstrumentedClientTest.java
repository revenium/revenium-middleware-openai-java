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
import io.revenium.metering.openai.transport.MeteringClient;
import io.revenium.metering.openai.wrapper.MeteringChatCompletionService;
import io.revenium.metering.openai.wrapper.MeteringChatCompletionServiceAsync;
import io.revenium.metering.openai.wrapper.MeteringEmbeddingService;
import io.revenium.metering.openai.wrapper.MeteringEmbeddingServiceAsync;
import io.revenium.metering.openai.wrapper.MeteringResponseService;
import io.revenium.metering.openai.wrapper.MeteringResponseServiceAsync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReveniumInstrumentedClient}.
 * Verifies all six metered service accessors, unwrap(), and close() ownership semantics.
 */
@ExtendWith(MockitoExtension.class)
class ReveniumInstrumentedClientTest {

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

    @Mock
    private MeteringClient mockMeteringClient;

    private ReveniumConfig config;
    private ReveniumInstrumentedClient container;

    @BeforeEach
    void setUp() {
        // Set up the OpenAI SDK mock chain
        when(mockClient.chat()).thenReturn(mockChatService);
        when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        when(mockClient.async()).thenReturn(mockAsyncClient);
        when(mockAsyncClient.chat()).thenReturn(mockChatServiceAsync);
        when(mockChatServiceAsync.completions()).thenReturn(mockChatCompletionServiceAsync);
        when(mockClient.embeddings()).thenReturn(mockEmbeddingService);
        when(mockAsyncClient.embeddings()).thenReturn(mockEmbeddingServiceAsync);
        when(mockClient.responses()).thenReturn(mockResponseService);
        when(mockAsyncClient.responses()).thenReturn(mockResponseServiceAsync);

        config = ReveniumConfig.builder()
                .apiKey("test-api-key")
                .baseUrl("https://api.openai.com")
                .build();

        container = new ReveniumInstrumentedClient(mockClient, config, mockMeteringClient);
    }

    // -----------------------------------------------------------------------
    // Test 1: chatCompletions() returns MeteringChatCompletionService
    // -----------------------------------------------------------------------

    @Test
    void chatCompletions_returnsMeteringChatCompletionService() {
        assertThat(container.chatCompletions())
                .isNotNull()
                .isInstanceOf(MeteringChatCompletionService.class);
    }

    // -----------------------------------------------------------------------
    // Test 2: chatCompletionsAsync() returns MeteringChatCompletionServiceAsync
    // -----------------------------------------------------------------------

    @Test
    void chatCompletionsAsync_returnsMeteringChatCompletionServiceAsync() {
        assertThat(container.chatCompletionsAsync())
                .isNotNull()
                .isInstanceOf(MeteringChatCompletionServiceAsync.class);
    }

    // -----------------------------------------------------------------------
    // Test 3: embeddings() returns MeteringEmbeddingService
    // -----------------------------------------------------------------------

    @Test
    void embeddings_returnsMeteringEmbeddingService() {
        assertThat(container.embeddings())
                .isNotNull()
                .isInstanceOf(MeteringEmbeddingService.class);
    }

    // -----------------------------------------------------------------------
    // Test 4: embeddingsAsync() returns MeteringEmbeddingServiceAsync
    // -----------------------------------------------------------------------

    @Test
    void embeddingsAsync_returnsMeteringEmbeddingServiceAsync() {
        assertThat(container.embeddingsAsync())
                .isNotNull()
                .isInstanceOf(MeteringEmbeddingServiceAsync.class);
    }

    // -----------------------------------------------------------------------
    // Test 5: responses() returns MeteringResponseService
    // -----------------------------------------------------------------------

    @Test
    void responses_returnsMeteringResponseService() {
        assertThat(container.responses())
                .isNotNull()
                .isInstanceOf(MeteringResponseService.class);
    }

    // -----------------------------------------------------------------------
    // Test 6: responsesAsync() returns MeteringResponseServiceAsync
    // -----------------------------------------------------------------------

    @Test
    void responsesAsync_returnsMeteringResponseServiceAsync() {
        assertThat(container.responsesAsync())
                .isNotNull()
                .isInstanceOf(MeteringResponseServiceAsync.class);
    }

    // -----------------------------------------------------------------------
    // Test 7: unwrap() returns original OpenAIClient instance
    // -----------------------------------------------------------------------

    @Test
    void unwrap_returnsOriginalDelegate() {
        assertThat(container.unwrap()).isSameAs(mockClient);
    }

    // -----------------------------------------------------------------------
    // Test 8: close() calls MeteringClient.close()
    // -----------------------------------------------------------------------

    @Test
    void close_callsMeteringClientClose() {
        container.close();
        verify(mockMeteringClient).close();
    }

    // -----------------------------------------------------------------------
    // Test 9: close() does NOT call close/shutdown on the delegate OpenAIClient
    // -----------------------------------------------------------------------

    @Test
    void close_doesNotCloseDelegateOpenAIClient() {
        // Clear all invocations that happened during construction (setUp)
        // so we only verify that close() itself does not interact with the delegate
        clearInvocations(mockClient, mockAsyncClient);

        container.close();

        // Verify close() does NOT cause any interaction with the delegate OpenAIClient
        verify(mockClient, never()).chat();
        verify(mockClient, never()).embeddings();
        verify(mockClient, never()).responses();
        verify(mockClient, never()).async();
        verify(mockAsyncClient, never()).chat();
        verify(mockAsyncClient, never()).embeddings();
        verify(mockAsyncClient, never()).responses();
    }
}
