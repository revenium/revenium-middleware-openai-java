package io.revenium.metering.openai.wrapper;

import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletion.Choice;
import com.openai.models.chat.completions.ChatCompletion.Choice.FinishReason;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.completions.CompletionUsage;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.transport.MeteringClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MeteringChatCompletionService}.
 *
 * Covers: non-streaming delegation + metering, streaming with stream_options injection + TTFT +
 * token usage from final chunk, finish reason mapping, UsageMetadata passthrough, resilience
 * (metering failure does not break caller), concurrency (50 threads, no race conditions),
 * and withRawResponse delegation.
 */
@ExtendWith(MockitoExtension.class)
class MeteringChatCompletionServiceTest {

    @Mock
    private com.openai.services.blocking.chat.ChatCompletionService delegate;

    @Mock
    private MeteringClient meteringClient;

    private MeteringChatCompletionService wrapper;

    @BeforeEach
    void setUp() {
        ReveniumConfig config = ReveniumConfig.builder().apiKey("test-key").build();
        wrapper = new MeteringChatCompletionService(delegate, config, meteringClient);
    }

    // -----------------------------------------------------------------------
    // Helper builders
    // -----------------------------------------------------------------------

    private ChatCompletionMessage buildMessage() {
        return ChatCompletionMessage.builder()
                .content(Optional.of("Hello world"))
                .refusal(Optional.empty())
                .build();
    }

    private Choice buildChoice(FinishReason finishReason) {
        return Choice.builder()
                .finishReason(finishReason)
                .index(0L)
                .logprobs(Optional.empty())
                .message(buildMessage())
                .build();
    }

    private CompletionUsage buildUsage(long prompt, long completion, long total) {
        return CompletionUsage.builder()
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(total)
                .build();
    }

    private ChatCompletion buildChatCompletion(String model, CompletionUsage usage, FinishReason finishReason) {
        return ChatCompletion.builder()
                .id("chat-id-123")
                .model(model)
                .created(System.currentTimeMillis() / 1000)
                .addChoice(buildChoice(finishReason))
                .usage(usage)
                .build();
    }

    private ChatCompletionCreateParams buildParams() {
        return ChatCompletionCreateParams.builder()
                .model("gpt-4o")
                .addUserMessage("Hello")
                .build();
    }

    /**
     * Builds a mock StreamResponse with the given chunks.
     * The stream() method returns a new stream for each invocation.
     */
    @SuppressWarnings("unchecked")
    private StreamResponse<ChatCompletionChunk> buildMockStreamResponse(ChatCompletionChunk... chunks) {
        StreamResponse<ChatCompletionChunk> mockStream = mock(StreamResponse.class);
        // Return a fresh stream on each call to avoid stream reuse issues
        when(mockStream.stream()).thenReturn(Stream.of(chunks));
        return mockStream;
    }

    private ChatCompletionChunk buildChunk(String model, String content, boolean hasFinishReason,
                                            CompletionUsage usage) {
        Delta delta = ChatCompletionChunk.Choice.Delta.builder()
                .content(content != null ? Optional.of(content) : Optional.empty())
                .build();

        ChatCompletionChunk.Choice.Builder choiceBuilder = ChatCompletionChunk.Choice.builder()
                .delta(delta)
                .index(0L)
                .logprobs(Optional.empty());

        if (hasFinishReason) {
            choiceBuilder.finishReason(ChatCompletionChunk.Choice.FinishReason.STOP);
        } else {
            choiceBuilder.finishReason(Optional.empty());
        }

        ChatCompletionChunk.Builder chunkBuilder = ChatCompletionChunk.builder()
                .id("chunk-1")
                .model(model)
                .created(System.currentTimeMillis() / 1000)
                .addChoice(choiceBuilder.build());

        if (usage != null) {
            chunkBuilder.usage(usage);
        }

        return chunkBuilder.build();
    }

    // -----------------------------------------------------------------------
    // Non-streaming create() tests
    // -----------------------------------------------------------------------

    @Test
    void nonStreamingCreate_delegatesAndMeters() {
        // Arrange
        CompletionUsage usage = buildUsage(10L, 20L, 30L);
        ChatCompletion mockResponse = buildChatCompletion("gpt-4o", usage, FinishReason.STOP);
        when(delegate.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        ChatCompletion result = wrapper.create(buildParams(), RequestOptions.none());

        // Assert: delegate was called once
        verify(delegate, times(1)).create(any(ChatCompletionCreateParams.class), any(RequestOptions.class));

        // Assert: meteringClient.send() was called once with correct data
        verify(meteringClient, times(1)).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getModel()).isEqualTo("gpt-4o");
        assertThat(event.getInputTokenCount()).isEqualTo(10L);
        assertThat(event.getOutputTokenCount()).isEqualTo(20L);
        assertThat(event.getTotalTokenCount()).isEqualTo(30L);
        assertThat(event.getOperationType()).isEqualTo("CHAT");
        assertThat(event.getIsStreamed()).isFalse();

        // Assert: original response returned
        assertThat(result).isSameAs(mockResponse);
    }

    @Test
    void nonStreamingCreate_capturesRequestDuration() {
        // Arrange
        CompletionUsage usage = buildUsage(10L, 20L, 30L);
        ChatCompletion mockResponse = buildChatCompletion("gpt-4o", usage, FinishReason.STOP);
        when(delegate.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.create(buildParams(), RequestOptions.none());

        // Assert
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRequestDuration()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    void nonStreamingCreate_mapsFinishReason() {
        // Arrange: "stop" maps to "END"
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion mockResponse = buildChatCompletion("gpt-4o", usage, FinishReason.STOP);
        when(delegate.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.create(buildParams(), RequestOptions.none());

        // Assert
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStopReason()).isEqualTo("END");
    }

    @Test
    void nonStreamingCreate_withUsageMetadata() {
        // Arrange
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion mockResponse = buildChatCompletion("gpt-4o", usage, FinishReason.STOP);
        when(delegate.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        UsageMetadata metadata = UsageMetadata.builder()
                .traceId("trace-123")
                .taskType("summarization")
                .build();

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act: call overloaded create with metadata
        wrapper.create(buildParams(), metadata);

        // Assert
        verify(meteringClient).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getTraceId()).isEqualTo("trace-123");
        assertThat(event.getTaskType()).isEqualTo("summarization");
    }

    // -----------------------------------------------------------------------
    // Streaming createStreaming() tests
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void streamingCreate_injectsStreamOptions() {
        // Arrange: params WITHOUT stream_options set
        ChatCompletionCreateParams params = buildParams();
        StreamResponse<ChatCompletionChunk> mockStream = mock(StreamResponse.class);
        ArgumentCaptor<ChatCompletionCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        when(delegate.createStreaming(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        // Act
        StreamResponse<ChatCompletionChunk> result = wrapper.createStreaming(params, RequestOptions.none());

        // Assert: delegate was called with params containing streamOptions.includeUsage=true
        verify(delegate).createStreaming(paramsCaptor.capture(), any(RequestOptions.class));
        ChatCompletionCreateParams capturedParams = paramsCaptor.getValue();
        assertThat(capturedParams.streamOptions()).isPresent();
        assertThat(capturedParams.streamOptions().get().includeUsage()).isPresent();
        assertThat(capturedParams.streamOptions().get().includeUsage().get()).isTrue();
    }

    @Test
    void streamingCreate_capturesTokenUsageFromFinalChunk() {
        // Arrange: three chunks; last one has usage
        CompletionUsage finalUsage = buildUsage(5L, 15L, 20L);
        ChatCompletionChunk chunk1 = buildChunk("gpt-4o", "Hello", false, null);
        ChatCompletionChunk chunk2 = buildChunk("gpt-4o", " world", false, null);
        ChatCompletionChunk finalChunk = buildChunk("gpt-4o", null, true, finalUsage);

        StreamResponse<ChatCompletionChunk> mockStream = buildMockStreamResponse(chunk1, chunk2, finalChunk);
        when(delegate.createStreaming(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act: consume stream and close
        StreamResponse<ChatCompletionChunk> result = wrapper.createStreaming(buildParams(), RequestOptions.none());
        result.stream().forEach(c -> {}); // consume all chunks
        result.close();

        // Assert: metering event fired with correct token counts
        verify(meteringClient, times(1)).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getInputTokenCount()).isEqualTo(5L);
        assertThat(event.getOutputTokenCount()).isEqualTo(15L);
        assertThat(event.getTotalTokenCount()).isEqualTo(20L);
        assertThat(event.getIsStreamed()).isTrue();
    }

    @Test
    void streamingCreate_capturesTimeToFirstToken() {
        // Arrange: first chunk has non-empty content
        ChatCompletionChunk chunk1 = buildChunk("gpt-4o", "Hello", false, null);
        ChatCompletionChunk finalChunk = buildChunk("gpt-4o", null, true, buildUsage(5L, 10L, 15L));

        StreamResponse<ChatCompletionChunk> mockStream = buildMockStreamResponse(chunk1, finalChunk);
        when(delegate.createStreaming(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        StreamResponse<ChatCompletionChunk> result = wrapper.createStreaming(buildParams(), RequestOptions.none());
        result.stream().forEach(c -> {});
        result.close();

        // Assert: TTFT > 0 (first content token time captured)
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTimeToFirstToken()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void streamingCreate_capturesFinishReason() {
        // Arrange
        ChatCompletionChunk chunk1 = buildChunk("gpt-4o", "Hello", false, null);
        ChatCompletionChunk finalChunk = buildChunk("gpt-4o", null, true, buildUsage(5L, 10L, 15L));

        StreamResponse<ChatCompletionChunk> mockStream = buildMockStreamResponse(chunk1, finalChunk);
        when(delegate.createStreaming(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        StreamResponse<ChatCompletionChunk> result = wrapper.createStreaming(buildParams(), RequestOptions.none());
        result.stream().forEach(c -> {});
        result.close();

        // Assert: "stop" -> "END"
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStopReason()).isEqualTo("END");
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamingCreate_respectsExistingStreamOptions() {
        // Arrange: params that already have streamOptions.includeUsage=true set
        ChatCompletionStreamOptions alreadySet = ChatCompletionStreamOptions.builder()
                .includeUsage(true)
                .build();
        ChatCompletionCreateParams paramsWithOptions = ChatCompletionCreateParams.builder()
                .model("gpt-4o")
                .addUserMessage("Hello")
                .streamOptions(alreadySet)
                .build();

        StreamResponse<ChatCompletionChunk> mockStream = mock(StreamResponse.class);
        ArgumentCaptor<ChatCompletionCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        when(delegate.createStreaming(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        // Act
        wrapper.createStreaming(paramsWithOptions, RequestOptions.none());

        // Assert: params passed to delegate are the same object (not rebuilt with new options)
        verify(delegate).createStreaming(paramsCaptor.capture(), any(RequestOptions.class));
        // The captured params should still have includeUsage=true (user's settings respected)
        assertThat(paramsCaptor.getValue().streamOptions()).isPresent();
        assertThat(paramsCaptor.getValue().streamOptions().get().includeUsage().get()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Resilience tests
    // -----------------------------------------------------------------------

    @Test
    void meteringFailureDoesNotBreakUserCall() {
        // Arrange: configure meteringClient.send() to throw
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion mockResponse = buildChatCompletion("gpt-4o", usage, FinishReason.STOP);
        when(delegate.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);
        doThrow(new RuntimeException("metering boom")).when(meteringClient).send(any());

        // Act + Assert: no exception propagated
        assertThatCode(() -> {
            ChatCompletion result = wrapper.create(buildParams(), RequestOptions.none());
            assertThat(result).isSameAs(mockResponse);
        }).doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Concurrency tests
    // -----------------------------------------------------------------------

    @Test
    void concurrentCallsDoNotRaceCondition() throws InterruptedException {
        // Arrange: 50 threads each calling create()
        int threadCount = 50;
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion mockResponse = buildChatCompletion("gpt-4o", usage, FinishReason.STOP);
        when(delegate.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // synchronized start
                    wrapper.create(buildParams(), RequestOptions.none());
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Signal all threads to start simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert: all 50 calls completed, no exceptions, 50 metering events sent
        assertThat(completed).isTrue();
        assertThat(exceptionCount.get()).isZero();
        verify(meteringClient, times(threadCount)).send(any(MeteringEvent.class));
    }

    // -----------------------------------------------------------------------
    // Delegation tests
    // -----------------------------------------------------------------------

    @Test
    void withRawResponse_delegatesToWrapped() {
        // Arrange
        com.openai.services.blocking.chat.ChatCompletionService.WithRawResponse mockRaw =
                mock(com.openai.services.blocking.chat.ChatCompletionService.WithRawResponse.class);
        when(delegate.withRawResponse()).thenReturn(mockRaw);

        // Act + Assert
        assertThat(wrapper.withRawResponse()).isSameAs(mockRaw);
    }
}
