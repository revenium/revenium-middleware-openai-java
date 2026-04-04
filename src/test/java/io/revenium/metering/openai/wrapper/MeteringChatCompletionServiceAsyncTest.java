package io.revenium.metering.openai.wrapper;

import com.openai.core.RequestOptions;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletion.Choice;
import com.openai.models.chat.completions.ChatCompletion.Choice.FinishReason;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.services.async.chat.ChatCompletionServiceAsync;
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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MeteringChatCompletionServiceAsync} and {@link MeteringAsyncStreamResponse}.
 *
 * Covers: async non-streaming CompletableFuture metering, failed future (no metering),
 * UsageMetadata passthrough, stream_options injection, async streaming subscriber interception
 * with TTFT and token usage capture, subscribe() returns this, and metering failure resilience.
 */
@ExtendWith(MockitoExtension.class)
class MeteringChatCompletionServiceAsyncTest {

    @Mock
    private ChatCompletionServiceAsync delegate;

    @Mock
    private MeteringClient meteringClient;

    private MeteringChatCompletionServiceAsync wrapper;

    @BeforeEach
    void setUp() {
        ReveniumConfig config = ReveniumConfig.builder().apiKey("test-key").build();
        wrapper = new MeteringChatCompletionServiceAsync(delegate, config, meteringClient);
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
                .id("chat-id-async-123")
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
                .id("chunk-async-1")
                .model(model)
                .created(System.currentTimeMillis() / 1000)
                .addChoice(choiceBuilder.build());

        if (usage != null) {
            chunkBuilder.usage(usage);
        }

        return chunkBuilder.build();
    }

    /**
     * Creates a mock AsyncStreamResponse that invokes the handler's two-arg subscribe
     * (Handler, Executor) inline when subscribed. MeteringAsyncStreamResponse.subscribe(Handler)
     * delegates to subscribe(Handler, Runnable::run), so the mock must stub the two-arg version.
     */
    @SuppressWarnings("unchecked")
    private AsyncStreamResponse<ChatCompletionChunk> buildMockAsyncStream(
            List<ChatCompletionChunk> chunks) {
        AsyncStreamResponse<ChatCompletionChunk> mockStream = mock(AsyncStreamResponse.class);

        // MeteringAsyncStreamResponse.subscribe(handler) calls delegate.subscribe(decorated, executor)
        // so we stub the two-arg version only
        doAnswer(invocation -> {
            AsyncStreamResponse.Handler<ChatCompletionChunk> handler = invocation.getArgument(0);
            for (ChatCompletionChunk chunk : chunks) {
                handler.onNext(chunk);
            }
            handler.onComplete(Optional.empty());
            return mockStream;
        }).when(mockStream).subscribe(any(), any());

        return mockStream;
    }

    // -----------------------------------------------------------------------
    // Non-streaming async create() tests
    // -----------------------------------------------------------------------

    @Test
    void asyncNonStreamingCreate_delegatesAndMeters() {
        // Arrange
        CompletionUsage usage = buildUsage(10L, 20L, 30L);
        ChatCompletion mockResponse = buildChatCompletion("gpt-4o", usage, FinishReason.STOP);
        when(delegate.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act: join() to wait for future and whenComplete callback to fire
        ChatCompletion result = wrapper.create(buildParams(), RequestOptions.none()).join();

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
    void asyncNonStreamingCreate_errorDoesNotMeter() {
        // Arrange: delegate returns failed future
        RuntimeException boom = new RuntimeException("API error");
        when(delegate.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.failedFuture(boom));

        // Act: call create — the future will fail
        CompletableFuture<ChatCompletion> future = wrapper.create(buildParams(), RequestOptions.none());

        // Wait for future to complete (it will be exceptional)
        try {
            future.join();
        } catch (Exception ignored) {
            // Expected — future failed
        }

        // Assert: meteringClient.send() was NOT called (no response to meter)
        verify(meteringClient, never()).send(any());
    }

    @Test
    void asyncNonStreamingCreate_withUsageMetadata() {
        // Arrange
        CompletionUsage usage = buildUsage(10L, 5L, 15L);
        ChatCompletion mockResponse = buildChatCompletion("gpt-4o", usage, FinishReason.STOP);
        when(delegate.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        UsageMetadata metadata = UsageMetadata.builder()
                .traceId("trace-async-456")
                .taskType("classification")
                .build();

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act: use overloaded create with metadata
        wrapper.create(buildParams(), metadata).join();

        // Assert: metadata fields pass through to MeteringEvent
        verify(meteringClient).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getTraceId()).isEqualTo("trace-async-456");
        assertThat(event.getTaskType()).isEqualTo("classification");
    }

    // -----------------------------------------------------------------------
    // Async streaming createStreaming() tests
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void asyncStreamingCreate_injectsStreamOptions() {
        // Arrange: params WITHOUT stream_options set
        ChatCompletionCreateParams params = buildParams();
        AsyncStreamResponse<ChatCompletionChunk> mockStream = mock(AsyncStreamResponse.class);
        ArgumentCaptor<ChatCompletionCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        when(delegate.createStreaming(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        // Act
        wrapper.createStreaming(params, RequestOptions.none());

        // Assert: delegate was called with params containing streamOptions.includeUsage=true
        verify(delegate).createStreaming(paramsCaptor.capture(), any(RequestOptions.class));
        ChatCompletionCreateParams capturedParams = paramsCaptor.getValue();
        assertThat(capturedParams.streamOptions()).isPresent();
        assertThat(capturedParams.streamOptions().get().includeUsage()).isPresent();
        assertThat(capturedParams.streamOptions().get().includeUsage().get()).isTrue();
    }

    @Test
    void asyncStreamingCreate_capturesTokenUsage() {
        // Arrange: two content chunks + final chunk with usage
        CompletionUsage finalUsage = buildUsage(5L, 15L, 20L);
        ChatCompletionChunk chunk1 = buildChunk("gpt-4o", "Hello", false, null);
        ChatCompletionChunk chunk2 = buildChunk("gpt-4o", " world", false, null);
        ChatCompletionChunk finalChunk = buildChunk("gpt-4o", null, true, finalUsage);

        AsyncStreamResponse<ChatCompletionChunk> mockStream =
                buildMockAsyncStream(List.of(chunk1, chunk2, finalChunk));
        when(delegate.createStreaming(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        AtomicInteger onNextCount = new AtomicInteger(0);
        AtomicBoolean onCompleteCalled = new AtomicBoolean(false);

        // Act: get streaming wrapper and subscribe (single-arg calls two-arg internally)
        AsyncStreamResponse<ChatCompletionChunk> result =
                wrapper.createStreaming(buildParams(), RequestOptions.none());
        result.subscribe(new AsyncStreamResponse.Handler<ChatCompletionChunk>() {
            @Override
            public void onNext(ChatCompletionChunk chunk) {
                onNextCount.incrementAndGet();
            }

            @Override
            public void onComplete(Optional<Throwable> error) {
                onCompleteCalled.set(true);
            }
        });

        // Assert: user handler was called for all chunks
        assertThat(onNextCount.get()).isEqualTo(3);
        assertThat(onCompleteCalled.get()).isTrue();

        // Assert: metering event fired with correct token counts
        verify(meteringClient, times(1)).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getInputTokenCount()).isEqualTo(5L);
        assertThat(event.getOutputTokenCount()).isEqualTo(15L);
        assertThat(event.getTotalTokenCount()).isEqualTo(20L);
        assertThat(event.getIsStreamed()).isTrue();
        assertThat(event.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void asyncStreamingCreate_capturesTimeToFirstToken() {
        // Arrange: first chunk has non-empty content to capture TTFT
        ChatCompletionChunk chunk1 = buildChunk("gpt-4o", "Hello", false, null);
        ChatCompletionChunk finalChunk = buildChunk("gpt-4o", null, true, buildUsage(5L, 10L, 15L));

        AsyncStreamResponse<ChatCompletionChunk> mockStream =
                buildMockAsyncStream(List.of(chunk1, finalChunk));
        when(delegate.createStreaming(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.createStreaming(buildParams(), RequestOptions.none()).subscribe(
                new AsyncStreamResponse.Handler<ChatCompletionChunk>() {
                    @Override
                    public void onNext(ChatCompletionChunk chunk) {}

                    @Override
                    public void onComplete(Optional<Throwable> error) {}
                });

        // Assert: TTFT >= 0 (first content token time captured)
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTimeToFirstToken()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncStreamingSubscribe_returnsThis() {
        // Arrange: use lenient mock to avoid unnecessary stubbing warnings
        AsyncStreamResponse<ChatCompletionChunk> mockStream = mock(AsyncStreamResponse.class);
        when(delegate.createStreaming(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        // Stub subscribe(any(), any()) so that the wrapper can call through
        lenient().doAnswer(invocation -> mockStream).when(mockStream).subscribe(any(), any());

        AsyncStreamResponse.Handler<ChatCompletionChunk> handler =
                new AsyncStreamResponse.Handler<ChatCompletionChunk>() {
                    @Override
                    public void onNext(ChatCompletionChunk chunk) {}

                    @Override
                    public void onComplete(Optional<Throwable> error) {}
                };

        // Act: get the wrapper, then subscribe — the result must be the same wrapper
        AsyncStreamResponse<ChatCompletionChunk> streamWrapper =
                wrapper.createStreaming(buildParams(), RequestOptions.none());
        AsyncStreamResponse<ChatCompletionChunk> subscribeResult = streamWrapper.subscribe(handler);

        // Assert: subscribe() returns this (the MeteringAsyncStreamResponse), not the delegate
        assertThat(subscribeResult).isSameAs(streamWrapper);
    }

    @Test
    void asyncMeteringFailure_doesNotBreakUserCallback() {
        // Arrange: configure send() to throw
        CompletionUsage finalUsage = buildUsage(5L, 10L, 15L);
        ChatCompletionChunk chunk1 = buildChunk("gpt-4o", "Hello", false, null);
        ChatCompletionChunk finalChunk = buildChunk("gpt-4o", null, true, finalUsage);

        AsyncStreamResponse<ChatCompletionChunk> mockStream =
                buildMockAsyncStream(List.of(chunk1, finalChunk));
        when(delegate.createStreaming(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        doThrow(new RuntimeException("metering boom")).when(meteringClient).send(any());

        AtomicInteger onNextCount = new AtomicInteger(0);
        AtomicBoolean onCompleteCalled = new AtomicBoolean(false);
        AtomicReference<Optional<Throwable>> onCompleteError = new AtomicReference<>();

        // Act: subscribe with a capturing handler — metering throws, callbacks must still fire
        wrapper.createStreaming(buildParams(), RequestOptions.none()).subscribe(
                new AsyncStreamResponse.Handler<ChatCompletionChunk>() {
                    @Override
                    public void onNext(ChatCompletionChunk chunk) {
                        onNextCount.incrementAndGet();
                    }

                    @Override
                    public void onComplete(Optional<Throwable> error) {
                        onCompleteCalled.set(true);
                        onCompleteError.set(error);
                    }
                });

        // Assert: all user callbacks fired despite metering failure
        assertThat(onNextCount.get()).isEqualTo(2);
        assertThat(onCompleteCalled.get()).isTrue();
        // The onComplete error Optional should be empty (no error from the stream itself)
        assertThat(onCompleteError.get()).isNotNull();
        assertThat(onCompleteError.get().isPresent()).isFalse();
    }
}
